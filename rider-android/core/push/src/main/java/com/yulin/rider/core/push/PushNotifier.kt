package com.yulin.rider.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yulin.rider.core.common.AndroidRuntimeGates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 派单通知(06 §3.4)。
 *
 * 全屏 Intent 走 App 的 launcher Activity 并带上路由 extra:core:push 不能反向依赖 app 模块,
 * 所以由 app 侧在 MainActivity 里读 [EXTRA_ROUTE] / [EXTRA_TASK_ID] 完成跳转。
 */
@Singleton
class PushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifyNewTask(alert: NewTaskAlert) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_TASK)
            .setSmallIcon(R.drawable.ic_notify_rider)
            .setContentTitle("有新订单")
            .setContentText(alert.notificationText())
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.notificationText()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(routeIntent(alert))
            // 锁屏时直接把确认页顶出来。没有 USE_FULL_SCREEN_INTENT 权限时系统会自动降级成横幅,不会崩
            .apply {
                if (AndroidRuntimeGates.canUseFullScreenIntent(context)) {
                    setFullScreenIntent(routeIntent(alert), true)
                }
            }
            .build()
        post(NOTIFICATION_ID_NEW_TASK, notification)
    }

    fun notifyUrgent(title: String, content: String) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_URGENT)
            .setSmallIcon(R.drawable.ic_notify_rider)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(routeIntent(null))
            .build()
        post(NOTIFICATION_ID_URGENT, notification)
    }

    fun cancelNewTask() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_NEW_TASK)
        }
    }

    private fun post(id: Int, notification: android.app.Notification) {
        try {
            if (!AndroidRuntimeGates.notificationsEnabled(context)) {
                Log.w(TAG, "通知未开启,派单提醒只能靠语音播报")
                return
            }
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "缺少通知权限", e)
        }
    }

    private fun routeIntent(alert: NewTaskAlert?): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (alert != null) {
            launch.putExtra(EXTRA_ROUTE, ROUTE_NEW_TASK)
            alert.taskId?.let { launch.putExtra(EXTRA_TASK_ID, it) }
            alert.taskVersion?.let { launch.putExtra(EXTRA_TASK_VERSION, it) }
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 渠道正常由 RiderApplication 统一创建(自定义声音必须在创建时设置,之后改不了)。
     * 这里只在缺失时补建,参数与 app 侧保持一致。
     */
    private fun ensureChannels() {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_NEW_TASK) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_NEW_TASK, "新派单", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "新配送任务提醒" }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_URGENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_URGENT, "紧急提醒", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "超时预警等紧急消息" }
            )
        }
    }

    companion object {
        /** 必须与 app 的 RiderApplication 渠道 id 完全一致。 */
        const val CHANNEL_NEW_TASK = "channel_new_task"
        const val CHANNEL_URGENT = "channel_urgent"

        /** app 的 MainActivity 从 Intent 里读这两个 extra 完成跳转。 */
        const val EXTRA_ROUTE = "rider_route"
        const val EXTRA_TASK_ID = "rider_task_id"
        const val EXTRA_TASK_VERSION = "rider_task_version"
        const val ROUTE_NEW_TASK = "new_task"

        private const val NOTIFICATION_ID_NEW_TASK = 2001
        private const val NOTIFICATION_ID_URGENT = 2002
        private const val REQUEST_CODE = 2001
        private const val TAG = "PushNotifier"
    }
}
