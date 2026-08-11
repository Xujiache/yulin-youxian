package com.yulin.rider.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 鲜活运营中枢的状态语义。
 *
 * 颜色始终与图形、文案或进度脊同时出现，避免骑手在强光、色弱或短暂扫视时只靠颜色判断。
 */
enum class StatusTone {
    NORMAL,
    SUCCESS,
    WARNING,
    DANGER,
    COLD,
    INFO,
}

/** 冻结公开色板。值来自 A8 视觉基线，不在 feature 中复制色值。 */
object RiderColors {
    val Primary = Color(0xFF0A7A38)
    val PrimaryContainer = Color(0xFFE3F5EA)
    val Secondary = Color(0xFF00796B)
    val Warning = Color(0xFFE68600)
    val Danger = Color(0xFFD93025)
    val Ice = Color(0xFF0277BD)
    val Background = Color(0xFFFAF8F4)
    val Ink = Color(0xFF1A1F26)

    /** 完成态与品牌同源，保留旧 API 名称以兼容既有业务组件。 */
    val Success = Primary

    /** 深色环境中提高明度，保持强光/低亮度下的可辨识度。 */
    val PrimaryBright = Color(0xFF63D58A)
    val SecondaryBright = Color(0xFF61D8CA)
    val WarningBright = Color(0xFFFFB44A)
    val DangerBright = Color(0xFFFF756B)
    val IceBright = Color(0xFF69C7FF)
}

internal object RiderNeutral {
    val Ink = RiderColors.Ink
    val InkMuted = Color(0xFF5A646F)
    val Line = Color(0xFFD9DDD8)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceLightAlt = Color(0xFFF1F3EF)
    val BackgroundLight = RiderColors.Background

    val Snow = Color(0xFFF4F7F3)
    val SnowMuted = Color(0xFFAAB4AD)
    val LineDark = Color(0xFF3A443E)
    val SurfaceDark = Color(0xFF171C19)
    val SurfaceDarkAlt = Color(0xFF212823)
    val BackgroundDark = Color(0xFF0E1310)
}

/**
 * 实心动作控件(大按钮、滑动确认)的填充色。
 * NORMAL 用品牌主绿:一屏一件事,主行动按钮永远是「下一步该做什么」。
 */
internal fun StatusTone.solidColor(): Color = when (this) {
    StatusTone.NORMAL -> RiderColors.Primary
    StatusTone.SUCCESS -> RiderColors.Success
    StatusTone.WARNING -> RiderColors.Warning
    StatusTone.DANGER -> RiderColors.Danger
    StatusTone.COLD -> RiderColors.Ice
    StatusTone.INFO -> RiderColors.Secondary
}

/** 标签类控件的强调色。NORMAL 走中性灰,避免「普通状态」被误读成「已完成」。 */
@Composable
@ReadOnlyComposable
internal fun StatusTone.accentColor(): Color = when (this) {
    StatusTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.SUCCESS -> if (isDarkScheme()) RiderColors.PrimaryBright else RiderColors.Success
    StatusTone.WARNING -> if (isDarkScheme()) RiderColors.WarningBright else RiderColors.Warning
    StatusTone.DANGER -> if (isDarkScheme()) RiderColors.DangerBright else RiderColors.Danger
    StatusTone.COLD -> if (isDarkScheme()) RiderColors.IceBright else RiderColors.Ice
    StatusTone.INFO -> if (isDarkScheme()) RiderColors.SecondaryBright else RiderColors.Secondary
}

@Composable
@ReadOnlyComposable
internal fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminanceIsDark()

private fun Color.luminanceIsDark(): Boolean = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) < 0.5f
