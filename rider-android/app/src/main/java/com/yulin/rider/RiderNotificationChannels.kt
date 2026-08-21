package com.yulin.rider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri

/**
 * 通知渠道(06 §3.4)。
 *
 * 两条硬约束决定了这里的写法:
 * 1. 自定义声音只能在「创建渠道那一刻」设定,之后 setSound 无效 —— 想换声音必须换渠道 id;
 * 2. 派单提醒必须能在勿扰、锁屏下响,所以 new_task / urgent 走 IMPORTANCE_HIGH + 绕过勿扰。
 *
 * 提示音资源(res/raw/new_task、res/raw/urgent)尚未就位时,自动回落到系统铃声/闹钟音,
 * 保证「有声音、且和普通通知不一样」这个底线,而不是静默失败。
 */
object RiderNotificationChannels {

    private const val RAW_NEW_TASK = "new_task"
    private const val RAW_URGENT = "urgent_alert"

    fun createAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val alarmAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val newTask = NotificationChannel(
            RiderApplication.CHANNEL_NEW_TASK,
            "新派单",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "有新配送任务时提醒，请保持开启，关闭会漏单"
            setSound(
                soundUri(context, RAW_NEW_TASK, RingtoneManager.TYPE_RINGTONE),
                alarmAudioAttributes,
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            enableLights(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val urgent = NotificationChannel(
            RiderApplication.CHANNEL_URGENT,
            "紧急提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "超时预警、改派、店长催单等紧急消息"
            setSound(
                soundUri(context, RAW_URGENT, RingtoneManager.TYPE_ALARM),
                alarmAudioAttributes,
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250, 150, 250)
            enableLights(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val normal = NotificationChannel(
            RiderApplication.CHANNEL_NORMAL,
            "普通消息",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "公告与普通业务消息"
        }

        // 定位常驻通知不该发出任何声音:一个班次十几个小时,响一次都算骚扰
        val service = NotificationChannel(
            RiderApplication.CHANNEL_SERVICE,
            "配送服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "配送中定位服务的常驻通知，关闭后位置会中断"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(newTask, urgent, normal, service))
    }

    /** 优先用打包的提示音;资源未就位时回落到系统铃声,始终返回一个可用的 Uri。 */
    private fun soundUri(context: Context, rawName: String, fallbackType: Int): Uri {
        @Suppress("DiscouragedApi")
        val resId = context.resources.getIdentifier(rawName, "raw", context.packageName)
        if (resId != 0) {
            return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$resId")
        }
        return RingtoneManager.getDefaultUri(fallbackType)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }
}
