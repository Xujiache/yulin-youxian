package com.yulin.rider.feature.task.util

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 04 §0:时间统一为无时区后缀的 ISO-8601 本地时间字符串,语义为 Asia/Shanghai。
 * 显式绑定时区而不用系统默认,避免骑手手机时区被改后 clientEventAt 与服务端对不上。
 */
private val SERVER_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val ISO_LOCAL_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

object RiderFormats {

    /** 生成状态流转用的 clientEventAt:骑手真实操作时刻,不是网络恢复时刻。 */
    fun nowServerLocal(): String = LocalDateTime.now(SERVER_ZONE).format(ISO_LOCAL_SECONDS)

    fun parseEpochMillis(serverLocalTime: String?): Long? {
        if (serverLocalTime.isNullOrBlank()) return null
        return runCatching {
            LocalDateTime.parse(serverLocalTime.take(19)).atZone(SERVER_ZONE).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** "16:00" —— 卡片上的送达时限只需要时分。 */
    fun hourMinute(serverLocalTime: String?): String? {
        if (serverLocalTime.isNullOrBlank()) return null
        return runCatching { LocalDateTime.parse(serverLocalTime.take(19)).format(DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrNull()
    }

    /**
     * 履约时间线用。当天的事件只给时分,跨天的补上月日 ——
     * 一条任务的事件绝大多数发生在同一天,每行都带年月日只会把时间挤成一片噪声。
     */
    fun eventTime(serverLocalTime: String?): String? {
        if (serverLocalTime.isNullOrBlank()) return null
        return runCatching {
            val moment = LocalDateTime.parse(serverLocalTime.take(19))
            val pattern = if (moment.toLocalDate() == LocalDateTime.now(SERVER_ZONE).toLocalDate()) {
                "HH:mm"
            } else {
                "MM-dd HH:mm"
            }
            moment.format(DateTimeFormatter.ofPattern(pattern))
        }.getOrNull()
    }

    /** 距离:1 km 以内用米,颠簸中扫一眼就能读的粒度。 */
    fun distance(meters: Long?): String {
        if (meters == null || meters < 0) return "—"
        return if (meters < 1000) "$meters m" else String.format(Locale.CHINA, "%.1f km", meters / 1000.0)
    }

    fun distance(meters: Int?): String = distance(meters?.toLong())

    /** 时长:"4 小时 12 分"。在线时长要一眼看懂,不用 04:12:33 这种表盘格式。 */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0 分钟"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }

    fun weight(kg: Double?): String? =
        kg?.let { String.format(Locale.CHINA, "%.1f", it).trimEnd('0').trimEnd('.') + " kg" }
}
