package com.yulin.rider.core.designsystem

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 4dp 基线网格。 */
object FreshSpacing {
    val Xxs = 4.dp
    val Xs = 8.dp
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 20.dp
    val Xl = 24.dp
    val Xxl = 32.dp
    val Huge = 48.dp
}

/** 圆角按信息层级收敛：操作控件紧、信息面板舒展。 */
object FreshRadius {
    val Small = 8.dp
    val Control = 14.dp
    val Panel = 18.dp
    val Hero = 24.dp
    val Pill = 999.dp
}

object FreshBorder {
    val Hairline = 1.dp
    val Strong = 2.dp
    val ProgressSpine = 6.dp
}

/** 动效只用于状态确认；系统关闭动画时由 [LocalFreshReducedMotion] 统一停用。 */
object FreshMotion {
    const val QuickMillis = 120
    const val StandardMillis = 220
    const val EmphasisMillis = 360
}

val LocalFreshReducedMotion = staticCompositionLocalOf { false }

/** 兼容旧 API 的尺寸别名；新页面优先使用 FreshSpacing/FreshRadius。 */
object RiderDimens {
    val BigButtonHeight: Dp = 68.dp
    val CardPadding: Dp = FreshSpacing.Md
    val ScreenPadding: Dp = FreshSpacing.Md

    val SecondaryButtonHeight: Dp = 52.dp
    val CardSpacing: Dp = FreshSpacing.Sm
    val CardCorner: Dp = FreshRadius.Panel
    val ControlCorner: Dp = FreshRadius.Control
    val TouchTarget: Dp = 48.dp
}

/**
 * 确认音开关。语音/提示音在设置页可关(部分骑手在安静环境送货),
 * 由 app 层从 DataStore 读出后 Provide 下来;未提供时默认开启。
 */
val LocalRiderSoundEnabled = staticCompositionLocalOf { true }

private val LightScheme = lightColorScheme(
    primary = RiderColors.Primary,
    onPrimary = Color.White,
    primaryContainer = RiderColors.PrimaryContainer,
    onPrimaryContainer = Color(0xFF073B20),
    secondary = RiderColors.Secondary,
    onSecondary = Color.White,
    tertiary = RiderColors.Warning,
    onTertiary = Color.White,
    error = RiderColors.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF5F1610),
    background = RiderNeutral.BackgroundLight,
    onBackground = RiderNeutral.Ink,
    surface = RiderNeutral.SurfaceLight,
    onSurface = RiderNeutral.Ink,
    surfaceVariant = RiderNeutral.SurfaceLightAlt,
    onSurfaceVariant = RiderNeutral.InkMuted,
    outline = RiderNeutral.Line,
    outlineVariant = RiderNeutral.Line,
)

private val DarkScheme = darkColorScheme(
    primary = RiderColors.PrimaryBright,
    onPrimary = Color(0xFF04240F),
    primaryContainer = Color(0xFF174E2D),
    onPrimaryContainer = Color(0xFFD9F8E2),
    secondary = RiderColors.SecondaryBright,
    onSecondary = Color(0xFF003731),
    tertiary = RiderColors.WarningBright,
    onTertiary = Color(0xFF2E1A00),
    error = RiderColors.DangerBright,
    onError = Color(0xFF3A0906),
    errorContainer = Color(0xFF5A1712),
    onErrorContainer = Color(0xFFFFDAD5),
    background = RiderNeutral.BackgroundDark,
    onBackground = RiderNeutral.Snow,
    surface = RiderNeutral.SurfaceDark,
    onSurface = RiderNeutral.Snow,
    surfaceVariant = RiderNeutral.SurfaceDarkAlt,
    onSurfaceVariant = RiderNeutral.SnowMuted,
    outline = RiderNeutral.LineDark,
    outlineVariant = RiderNeutral.LineDark,
)

/**
 * 字号整体比 Material 默认大一档:骑手在颠簸、强光、戴手套的条件下读屏。
 * lineHeight 一律给到字号的 1.4 倍以上,系统字号放到 1.3 倍时行距同比放大不会重叠。
 */
private val RiderTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 42.sp,
        lineHeight = 50.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.6).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Black,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 23.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 19.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

@Composable
fun RiderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    CompositionLocalProvider(LocalFreshReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = RiderTypography,
            content = content,
        )
    }
}
