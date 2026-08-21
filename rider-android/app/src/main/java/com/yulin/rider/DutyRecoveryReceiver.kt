package com.yulin.rider

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yulin.rider.core.location.DutyStateStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 重启后 Android 14+ 不允许静默拉起 location FGS；改为高优先级通知，让骑手一次点击恢复，
 * MainActivity 随后向服务端校验仍在岗再启动定位。
 */
@AndroidEntryPoint
class DutyRecoveryReceiver : BroadcastReceiver() {

    @Inject lateinit var dutyStateStore: DutyStateStore

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS || !dutyStateStore.current().onDuty) return
        // Android 13+ 通知需要运行时授权。骑手拒绝过就发不出这条恢复提醒，
        // 但重启后本来也无法静默拉起定位，只能等下次进 App 时由首页校验班次。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        RiderNotificationChannels.createAll(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.putExtra(EXTRA_RECOVER_DUTY, true)
            ?: return
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_RECOVER,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RiderApplication.CHANNEL_URGENT)
            .setSmallIcon(com.yulin.rider.core.push.R.drawable.ic_notify_rider)
            .setContentTitle("配送服务需要恢复")
            .setContentText("检测到重启前仍在岗，点此校验班次并恢复定位")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_RECOVER, notification)
        }
    }

    companion object {
        const val EXTRA_RECOVER_DUTY = "recover_duty_after_boot"
        private const val REQUEST_RECOVER = 3101
        private const val NOTIFICATION_ID_RECOVER = 3101
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
