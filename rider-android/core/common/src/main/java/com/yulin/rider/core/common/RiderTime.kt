package com.yulin.rider.core.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间转换。契约(04 §0)约定服务端收发的都是「无时区后缀的 ISO-8601 本地时间」,
 * 语义固定为 Asia/Shanghai —— 直接用 Instant 解析会报错,必须走 LocalDateTime + 固定时区。
 */
object RiderTime {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val ISO_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val MD_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 契约时间串 → epoch 毫秒。解析失败返回 null,绝不抛给 UI。 */
    fun toEpochMillis(isoLocal: String?): Long? {
        if (isoLocal.isNullOrBlank()) return null
        return runCatching {
            LocalDateTime.parse(isoLocal.substringBefore('.').removeSuffix("Z"), ISO_LOCAL)
                .atZone(ZONE)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /** epoch 毫秒 → 契约时间串。上报 clientEventAt / agreedAt 用。 */
    fun toIsoLocal(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).format(ISO_LOCAL)

    fun nowIsoLocal(): String = toIsoLocal(System.currentTimeMillis())

    /** 契约时间串 → "15:42",任务卡片上的送达时刻。 */
    fun toClock(isoLocal: String?): String = format(isoLocal, HH_MM)

    /** 契约时间串 → "08-11 15:42",跨天场景用。 */
    fun toDateClock(isoLocal: String?): String = format(isoLocal, MD_HH_MM)

    /** 距离目标时间还剩多少秒,已过期为负数。 */
    fun remainingSeconds(isoLocal: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val target = toEpochMillis(isoLocal) ?: return null
        return (target - nowMillis) / 1000
    }

    private fun format(isoLocal: String?, formatter: DateTimeFormatter): String {
        val millis = toEpochMillis(isoLocal) ?: return "--"
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE).format(formatter)
    }
}
