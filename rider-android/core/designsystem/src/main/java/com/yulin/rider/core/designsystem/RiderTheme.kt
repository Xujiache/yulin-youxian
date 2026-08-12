package com.yulin.rider.core.designsystem

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 4dp 基线网格。页面左右边距与卡片内边距都取 Sm(12dp)。 */
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

/** 圆角。卡片与按钮同为 8dp，标签 4dp，状态胶囊全圆。 */
object FreshRadius {
    val Tag = 4.dp
    val Small = 6.dp
    val Control = 8.dp
    val Panel = 8.dp
    val Sheet = 16.dp
    val Hero = 12.dp
    val Pill = 999.dp
}

object FreshBorder {
    val Hairline = 1.dp
    val Strong = 2.dp
    val Rail = 2.dp
    val ProgressSpine = 3.dp
}

/** 卡片本身不投影，只有吸底条和浮层有极轻的阴影。 */
object FreshElevation {
    val Flat = 0.dp
    val Card = 0.dp
    val StickyBar = 6.dp
    val Floating = 12.dp
}

object FreshMotion {
    const val QuickMillis = 120
    const val StandardMillis = 220
    const val EmphasisMillis = 360
}

val LocalFreshReducedMotion = staticCompositionLocalOf { false }

object RiderDimens {
    /** 主按钮 48dp，和美团一致，同时满足戴手套的最小触达。 */
    val BigButtonHeight: Dp = 48.dp
    val SecondaryButtonHeight: Dp = 40.dp
    val TouchTarget: Dp = 44.dp

    val ScreenPadding: Dp = FreshSpacing.Sm
    val CardPadding: Dp = FreshSpacing.Sm
    val CardSpacing: Dp = FreshSpacing.Xs
    val CardCorner: Dp = FreshRadius.Panel
    val ControlCorner: Dp = FreshRadius.Control
}

/**
 * 确认音开关。语音/提示音在设置页可关(部分骑手在安静环境送货),
 * 由 app 层从 DataStore 读出后 Provide 下来;未提供时默认开启。
 */
val LocalRiderSoundEnabled = staticCompositionLocalOf { true }

private val MtColorScheme = lightColorScheme(
    primary = RiderColors.Primary,
    onPrimary = RiderColors.OnPrimary,
    primaryContainer = RiderColors.PrimaryContainer,
    onPrimaryContainer = RiderColors.Ink,
    secondary = RiderColors.Info,
    onSecondary = Color.White,
    secondaryContainer = RiderColors.InfoContainer,
    onSecondaryContainer = Color(0xFF0B2E66),
    tertiary = RiderColors.Pickup,
    onTertiary = Color.White,
    tertiaryContainer = RiderColors.PickupContainer,
    onTertiaryContainer = Color(0xFF6B1C00),
    error = RiderColors.Danger,
    onError = Color.White,
    errorContainer = RiderColors.DangerContainer,
    onErrorContainer = Color(0xFF6B0F14),
    background = RiderNeutral.Background,
    onBackground = RiderNeutral.Ink,
    surface = RiderNeutral.Surface,
    onSurface = RiderNeutral.Ink,
    surfaceVariant = RiderNeutral.SurfaceAlt,
    onSurfaceVariant = RiderNeutral.InkMuted,
    surfaceContainerLowest = RiderNeutral.Surface,
    surfaceContainerLow = RiderNeutral.Surface,
    surfaceContainer = RiderNeutral.SurfaceAlt,
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFEAEAEA),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF5F5F5),
    outline = RiderNeutral.Line,
    outlineVariant = RiderNeutral.Divider,
    scrim = Color(0xFF000000),
)

/**
 * 字阶。
 *
 * 字重上限固定为 Bold(700)：安卓中文字体在多数机型上只提供到 Bold，
 * 请求 Black(900) 会触发系统合成加粗，大字号下中文会糊成一团。
 *
 * lineHeight 一律给到字号的 1.35 倍以上，系统字号放大到 1.3 倍时行距同比放大不会重叠。
 */
private val MtTypography = Typography(
    // 页面大标题：你有 N 个派单、我的账户
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 32.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 30.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 28.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 金额
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 送达地址，卡片里最大的信息
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 商家名、清单小标题
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 时限行、按钮文字
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    ),
    // 地址详情
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    // 标签、竖排次级动作的文字
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/**
 * 等宽数字。
 *
 * 金额、距离、倒计时、重量、单号在刷新时逐位变化，比例数字会让整行左右横跳；
 * 开启 tnum 后每个数字占同宽，读数稳定也更容易上下对齐。
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

/** 数字指标的成套样式。展示任何会跳变的数值都应该走这里。 */
object FreshNumerals {

    /** 账户余额这类需要一眼扫到的主指标。 */
    val Hero: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.displaySmall.tabularFigures()

    /** 卡片内的次级指标。 */
    val Metric: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium.tabularFigures()

    /** 倒计时、单号、时间戳这类正文中的数字。 */
    val Inline: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.tabularFigures()
}

/**
 * 应用主题。
 *
 * 只有浅色一套。骑手端全程浅灰底白卡片，没有深色模式，
 * 因此也不再提供跟随系统或手动切换。
 */
@Composable
fun RiderTheme(content: @Composable () -> Unit) {
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
            colorScheme = MtColorScheme,
            typography = MtTypography,
            content = content,
        )
    }
}
