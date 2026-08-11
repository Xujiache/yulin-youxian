package com.yulin.rider.core.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 展示层格式化。全系统金额一律「分」、距离一律「米」、时长一律「秒」(04 §0),
 * 转换只在这里发生,避免各页面各写一套导致对不上账。
 */
object RiderFormat {

    /** 分 → 元,固定两位小数。380 → "3.80"。 */
    fun money(cents: Int): String {
        val sign = if (cents < 0) "-" else ""
        val abs = abs(cents.toLong())
        return "%s%d.%02d".format(sign, abs / 100, abs % 100)
    }

    /** 分 → 带符号金额。380 → "¥3.80"。 */
    fun moneyWithSymbol(cents: Int): String = "¥" + money(cents)

    /** 米 → 中文距离。850 → "850 米";2100 → "2.1 公里"。 */
    fun distance(meters: Long): String = when {
        meters < 0 -> "--"
        meters < 1000 -> "$meters 米"
        meters < 10_000 -> "%.1f 公里".format(Locale.CHINA, meters / 1000.0)
        else -> "${(meters / 1000.0).roundToLong()} 公里"
    }

    /** 米 → 紧凑距离,用于卡片角标。850 → "850m";2100 → "2.1km"。 */
    fun distanceCompact(meters: Long): String = when {
        meters < 0 -> "--"
        meters < 1000 -> "${meters}m"
        else -> "%.1fkm".format(Locale.CHINA, meters / 1000.0)
    }

    /** 秒 → 倒计时。不足 1 小时用 mm:ss,超过补出小时位。负数按 0 处理。 */
    fun remaining(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    /** 秒 → 中文时长,用于「已在线 4 小时 20 分」这类统计文案。 */
    fun durationCn(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            minutes > 0 -> "$minutes 分钟"
            else -> "$total 秒"
        }
    }

    /** 重量,去掉无意义的小数。6.5 → "6.5kg";3.0 → "3kg"。 */
    fun weightKg(kg: Double?): String {
        if (kg == null) return "--"
        return if (kg % 1.0 == 0.0) "${kg.toLong()}kg" else "%.1fkg".format(Locale.CHINA, kg)
    }

    /** 比率 → 百分比。0.972 → "97.2%"。 */
    fun percent(ratio: Double?): String =
        if (ratio == null) "--" else "%.1f%%".format(Locale.CHINA, ratio * 100)
}
