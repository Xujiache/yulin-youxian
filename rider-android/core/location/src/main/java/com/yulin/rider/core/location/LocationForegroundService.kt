package com.yulin.rider.core.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

/**
 * 定位前台服务(06 §3.1 / §3.2)。
 *
 * `foregroundServiceType="location"` 没有运行时长上限(6 小时限制只针对 dataSync),可以跑完整个班次。
 *
 * **时序硬约束**:只能由骑手在 App 可见时点「上班」启动,绝不从推送、开机广播或后台任务启动,
 * 否则会撞上 Android 的 while-in-use 限制直接抛 SecurityException。入口统一收敛到
 * [LocationController.start],不要在别处 startService。
 */
@AndroidEntryPoint
class LocationForegroundService : Service() {

    @Inject
    lateinit var pipeline: LocationPipeline

    @Inject
    lateinit var dutyStateStore: DutyStateStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pipelineJob: Job? = null
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dutyStateStore.clear()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!hasFineLocationPermission()) {
            // 没有定位权限时 startForeground(location) 会直接抛异常,宁可不启动也不能崩
            Log.e(TAG, "缺少 ACCESS_FINE_LOCATION,拒绝启动定位前台服务")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (notificationJob?.isActive != true) {
            notificationJob = scope.launch { keepNotificationFresh() }
        }
        if (intent?.action == ACTION_PREPARE) {
            // 用户手势窗口内先进入前台；服务端 on-duty 成功前不启动定位管道。
            return START_NOT_STICKY
        }

        val duty = dutyStateStore.current()
        if (!duty.onDuty || duty.shiftId == null || duty.startedAtMillis == null) {
            Log.w(TAG, "没有可恢复的本地班次，停止定位服务")
            stopSelfSafely()
            return START_NOT_STICKY
        }
        pipeline.updateShift(duty.shiftId, duty.startedAtMillis, pipeline.shift.value.activeTaskCount)
        if (pipelineJob?.isActive != true) {
            acquireWakeLock()
            pipelineJob = scope.launch { pipeline.run() }
        }

        // START_STICKY 只是让系统在内存压力回落后重建服务;国产 ROM 上不保证,真正的兜底是保活向导
        return START_STICKY
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        pipelineJob = null
        notificationJob?.cancel()
        notificationJob = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground 失败", e)
        false
    }

    /**
     * 通知内容随在线时长与在途单量变化。
     * 需要一个自走的分钟级 tick:「在线 4.2 小时」这行字没有任何状态变化也会过期。
     */
    private suspend fun keepNotificationFresh() {
        val minuteTick = flow {
            while (true) {
                dutyStateStore.heartbeat()
                emit(System.currentTimeMillis())
                delay(NOTIFICATION_REFRESH_MILLIS)
            }
        }
        var lastText: String? = null
        combine(pipeline.shift, pipeline.state, minuteTick) { shift, state, _ ->
            notificationText(shift, state)
        }.collect { text ->
            if (text != lastText) {
                lastText = text
                notify(text)
            }
        }
    }

    private fun notify(text: String) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
            .onFailure { Log.w(TAG, "刷新常驻通知失败", it) }
    }

    private fun buildNotification(text: String = notificationText(pipeline.shift.value, pipeline.state.value)): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rider_location_service)
            .setContentTitle("配送中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()
    }

    private fun notificationText(
        shift: LocationPipeline.ShiftDisplay,
        state: LocationServiceState,
    ): String {
        if (dutyStateStore.current().waitingForServer) return "正在确认上班，请稍候"
        val parts = mutableListOf("配送中")
        shift.onDutyAtMillis?.let { start ->
            val hours = max(0L, System.currentTimeMillis() - start) / 3_600_000.0
            parts += if (hours < 1) {
                val minutes = max(1L, (System.currentTimeMillis() - start) / 60_000)
                "在线 $minutes 分钟"
            } else {
                String.format("在线 %.1f 小时", hours)
            }
        }
        parts += "${shift.activeTaskCount} 单进行中"
        if (state.pendingUploadCount > PENDING_WARN_THRESHOLD) {
            parts += "${state.pendingUploadCount} 个位置待上传"
        }
        return parts.joinToString(" · ")
    }

    /**
     * 渠道正常由 RiderApplication 统一创建;这里兜底再建一次,
     * 避免宿主初始化顺序变化时 startForeground 因渠道缺失而失败。参数必须与 app 侧保持一致。
     */
    private fun ensureChannel() {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL_ID, "配送服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "配送中定位服务常驻通知"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // 加超时是防御性的:任何漏掉 release 的路径最多熬到一个超长班次结束
            runCatching { acquire(MAX_SHIFT_MILLIS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        pipelineJob?.cancel()
        pipelineJob = null
        notificationJob?.cancel()
        notificationJob = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** 必须与 app 的 RiderApplication.CHANNEL_SERVICE 完全一致。 */
        const val SERVICE_CHANNEL_ID = "channel_service"
        const val ACTION_START = "com.yulin.rider.location.START"
        const val ACTION_PREPARE = "com.yulin.rider.location.PREPARE"
        const val ACTION_STOP = "com.yulin.rider.location.STOP"

        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 1001
        private const val PENDING_WARN_THRESHOLD = 60
        private const val NOTIFICATION_REFRESH_MILLIS = 60_000L
        private const val WAKE_LOCK_TAG = "yulin-rider:location"
        private const val MAX_SHIFT_MILLIS = 14 * 60 * 60 * 1000L
        private const val TAG = "LocationFgService"

        internal fun intent(context: Context, action: String): Intent =
            Intent(context, LocationForegroundService::class.java).setAction(action)
    }
}
