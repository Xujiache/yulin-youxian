package com.yulin.rider.core.location

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.model.ServerCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定位服务的唯一入口,提供给 A8 在上下班时调用。
 *
 * **[start] 只能在骑手点击「上班」、且 App 处于可见状态时调用。**
 * 绝不要从推送回调、开机广播、WorkManager 或任何后台路径调用:
 * Android 12+ 会抛 ForegroundServiceStartNotAllowedException,
 * Android 10+ 的 while-in-use 限制还会让定位直接拿不到点。
 */
@Singleton
class LocationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pipeline: LocationPipeline,
    private val keepAliveChecker: KeepAliveChecker,
    private val dutyStateStore: DutyStateStore,
) {

    /** 服务运行态、数据源、当前采样间隔、待上传点数,可直接绑到首页状态条。 */
    val state: StateFlow<LocationServiceState> = pipeline.state

    /** 最近一次定位(含被清洗丢弃前的原始点),地图页画骑手箭头用。 */
    val lastFix: StateFlow<RiderLocationFix?> = pipeline.lastFix

    /** 服务端搭车下发的指令(REFRESH_TASKS / MARK_ARRIVED ...)。 */
    val commands: SharedFlow<ServerCommand> = pipeline.commands

    /** 地图页用的轻量投影,避免每个调用方自己写一遍 map。 */
    val riderPoint: Flow<GeoPoint?> = pipeline.lastFix.map { it?.toGeoPoint() }

    /** 骑手朝向,地图上的方向箭头用;无有效方位时为 null。 */
    val riderBearing: Flow<Float?> = pipeline.lastFix.map { fix ->
        fix?.bearing?.takeIf { it != 0.0 }?.toFloat()
    }

    private val _started = MutableStateFlow(false)

    /** 骑手侧的「已请求上班」标记,与服务真实运行态([state].running)分开看,便于定位掉线。 */
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /**
     * 必须直接在“确认上班”的用户手势调用栈中执行：先占住合法的前台服务启动窗口，
     * 服务只显示“正在上班”通知，服务端确认前不会采集或上传位置。
     */
    fun prepareForDuty(): LocationStartResult {
        dutyStateStore.markWaiting()
        return launchForeground(LocationForegroundService.ACTION_PREPARE).also { result ->
            if (result is LocationStartResult.Rejected) dutyStateStore.clear()
        }
    }

    fun confirmDuty(shiftId: Long, startedAtMillis: Long) {
        dutyStateStore.markOnDuty(shiftId, startedAtMillis)
        pipeline.updateShift(shiftId, startedAtMillis, 0)
        runCatching {
            context.startService(
                LocationForegroundService.intent(context, LocationForegroundService.ACTION_START)
            )
        }.onFailure { Log.e(TAG, "确认班次后启动定位管道失败", it) }
        _started.value = true
    }

    /** App 前台恢复服务端仍在岗的班次。 */
    fun start(shiftId: Long, startedAtMillis: Long): LocationStartResult {
        dutyStateStore.markOnDuty(shiftId, startedAtMillis)
        pipeline.updateShift(shiftId, startedAtMillis, 0)
        return launchForeground(LocationForegroundService.ACTION_START)
    }

    private fun launchForeground(action: String): LocationStartResult {
        val status = keepAliveChecker.current()
        if (!status.fineLocationGranted) {
            return LocationStartResult.Rejected(
                reason = LocationStartRejectReason.NO_FINE_LOCATION,
                message = "未授予精确定位权限,无法开始配送",
            )
        }

        return try {
            ContextCompat.startForegroundService(
                context,
                LocationForegroundService.intent(context, action),
            )
            _started.value = true
            LocationStartResult.Started(warnings = buildWarnings(status))
        } catch (e: Exception) {
            Log.e(TAG, "启动定位前台服务失败", e)
            _started.value = false
            LocationStartResult.Rejected(
                reason = if (e is IllegalStateException) {
                    LocationStartRejectReason.BACKGROUND_START_BLOCKED
                } else {
                    LocationStartRejectReason.UNKNOWN
                },
                message = "启动定位服务失败,请回到 App 首页后重试上班",
            )
        }
    }

    fun stop() {
        _started.value = false
        dutyStateStore.clear()
        runCatching {
            // 用 stopService 而不是发 ACTION_STOP:startService 在后台会被 Android 8+ 拒绝,
            // 而下班动作有可能发生在 App 刚被切走的瞬间,停不掉服务就是一直耗电
            context.stopService(
                LocationForegroundService.intent(context, LocationForegroundService.ACTION_STOP),
            )
        }.onFailure {
            Log.d(TAG, "停止定位服务:服务可能已不在运行", it)
        }
    }

    /** 上下班、接单/送达后调用,决定常驻通知上显示的「在线 X 小时 · N 单进行中」。 */
    fun updateShift(shiftId: Long?, onDutyAtMillis: Long?, activeTaskCount: Int) =
        pipeline.updateShift(shiftId, onDutyAtMillis, activeTaskCount)

    /** 当前正在配送的任务/波次,会随位置批量上报一起带给服务端。 */
    fun bindTask(taskId: Long?, waveId: Long?) = pipeline.bindTask(taskId, waveId)

    private fun buildWarnings(status: KeepAliveStatus): List<String> = buildList {
        if (!status.backgroundLocationGranted) {
            add("未开启「始终允许」后台定位,锁屏后可能掉线")
        }
        if (!status.notificationGranted) {
            add("未开启通知权限,收不到新单提醒")
        }
        if (!status.batteryOptimizationIgnored) {
            add("未关闭电池优化,系统可能在后台杀掉配送服务")
        }
        if (!status.keepAliveGuideDone) {
            add("尚未完成保活设置向导,建议先完成一次")
        }
        if (!AmapKeyState.isConfigured(context)) {
            add("未配置地图 Key,当前使用系统定位,精度可能略低")
        }
    }

    private companion object {
        const val TAG = "LocationController"
    }
}
