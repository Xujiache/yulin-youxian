package com.yulin.rider.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 状态语义。
 *
 * 颜色始终与图形、文案或标签同时出现，避免骑手在强光、色弱或短暂扫视时只靠颜色判断。
 */
enum class StatusTone {
    NORMAL,
    SUCCESS,
    WARNING,
    DANGER,
    COLD,
    INFO,
}

/**
 * 主行动按钮的语义。
 *
 * 骑手端的主按钮不是一种颜色，而是跟着行程段走的三色渐进：
 * 接受类动作用品牌黄，完成取货段用橙红，完成送达段用绿色，
 * 与地图上取货段橙线、送达段绿线以及「取」「送」标记严格对应，
 * 骑手不读文字也能从颜色判断自己处在哪一段。
 */
enum class MtAction {
    /** 上线、接单、上报到店等「接受与进入」类动作。黄底黑字。 */
    ACCEPT,

    /** 我已取货。完成取货段，对应地图橙色路线。橙红底白字。 */
    PICKUP,

    /** 我已送达。完成送达段，对应地图绿色路线。绿底白字。 */
    DELIVER,

    /** 取消、下班等破坏性动作。红底白字。 */
    DANGER,

    /** 刷新、拒绝等次级动作。白底灰边深色字。 */
    SECONDARY,
}

/**
 * 公开色板。业务层不要复制色值。
 *
 * 只有浅色一套：骑手端全程白卡片配浅灰底，没有深色模式。
 */
object RiderColors {
    /** 品牌黄。按钮填充、选中态。黄底一律配近黑文字，白字在黄底上对比度不合格。 */
    val Primary = Color(0xFFFFC300)
    val PrimaryPressed = Color(0xFFF5B800)
    val PrimaryContainer = Color(0xFFFFF8E0)

    /** 黄底上的前景色。 */
    val OnPrimary = Color(0xFF222222)

    /** 取货段橙红。取货按钮、「取」标记、取货路线、预约徽标、倒计时数字。 */
    val Pickup = Color(0xFFFF5722)
    val PickupContainer = Color(0xFFFFF0EB)

    /** 送达段绿。送达按钮、「送」标记、送达路线、在岗状态点。 */
    val Deliver = Color(0xFF00B42A)
    val DeliverContainer = Color(0xFFE8F8EC)

    /** 金额红。只用于钱，不用于错误。 */
    val Money = Color(0xFFFF2D00)

    /** 错误红，同时用作离线状态点。 */
    val Danger = Color(0xFFF5222D)
    val DangerContainer = Color(0xFFFFECEB)

    /** 信息蓝。顶部信息条、同城一类中性标签。 */
    val Info = Color(0xFF3478F6)
    val InfoContainer = Color(0xFFE8F1FF)

    /** 冷链蓝。本业务特有，取比信息蓝更深一档以便区分。 */
    val Ice = Color(0xFF0B72E7)
    val IceContainer = Color(0xFFE3EEFC)

    val Background = Color(0xFFF5F5F5)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF222222)

    /** 兼容旧调用点的别名。语义已按美团重新映射。 */
    val Success = Deliver
    val Warning = Pickup
    val Secondary = Info

    @Deprecated("深色主题已下线，浅色下与基础色相同", ReplaceWith("Primary"))
    val PrimaryBright = Primary

    @Deprecated("深色主题已下线，浅色下与基础色相同", ReplaceWith("Secondary"))
    val SecondaryBright = Info

    @Deprecated("深色主题已下线，浅色下与基础色相同", ReplaceWith("Warning"))
    val WarningBright = Pickup

    @Deprecated("深色主题已下线，浅色下与基础色相同", ReplaceWith("Danger"))
    val DangerBright = Danger

    @Deprecated("深色主题已下线，浅色下与基础色相同", ReplaceWith("Ice"))
    val IceBright = Ice
}

internal object RiderNeutral {
    val Ink = RiderColors.Ink
    val InkMuted = Color(0xFF888888)
    val InkDisabled = Color(0xFFBBBBBB)
    val Line = Color(0xFFEEEEEE)
    val Divider = Color(0xFFF2F2F2)
    val Surface = RiderColors.Surface
    val SurfaceAlt = Color(0xFFF7F7F7)
    val Background = RiderColors.Background
}

/** 主按钮填充色。 */
@Composable
@ReadOnlyComposable
fun MtAction.fillColor(): Color = when (this) {
    MtAction.ACCEPT -> RiderColors.Primary
    MtAction.PICKUP -> RiderColors.Pickup
    MtAction.DELIVER -> RiderColors.Deliver
    MtAction.DANGER -> RiderColors.Danger
    MtAction.SECONDARY -> RiderColors.Surface
}

/** 主按钮前景色。黄底黑字、彩底白字这条规则由这里强制，业务层不需要判断。 */
@Composable
@ReadOnlyComposable
fun MtAction.contentColor(): Color = when (this) {
    MtAction.ACCEPT -> RiderColors.OnPrimary
    MtAction.PICKUP, MtAction.DELIVER, MtAction.DANGER -> Color.White
    MtAction.SECONDARY -> RiderColors.Ink
}

/** 语义强调色，用于文字、图标、圆点。 */
@Composable
@ReadOnlyComposable
fun StatusTone.toneColor(): Color = when (this) {
    StatusTone.NORMAL -> RiderNeutral.InkMuted
    StatusTone.SUCCESS -> RiderColors.Deliver
    StatusTone.WARNING -> RiderColors.Pickup
    StatusTone.DANGER -> RiderColors.Danger
    StatusTone.COLD -> RiderColors.Ice
    StatusTone.INFO -> RiderColors.Info
}

/** 语义容器底色。用实色台阶而不是半透明叠加，避免在白卡上叠出脏色。 */
@Composable
@ReadOnlyComposable
fun StatusTone.toneContainer(): Color = when (this) {
    StatusTone.NORMAL -> RiderNeutral.SurfaceAlt
    StatusTone.SUCCESS -> RiderColors.DeliverContainer
    StatusTone.WARNING -> RiderColors.PickupContainer
    StatusTone.DANGER -> RiderColors.DangerContainer
    StatusTone.COLD -> RiderColors.IceContainer
    StatusTone.INFO -> RiderColors.InfoContainer
}

/** 兼容旧组件的内部别名。 */
@Composable
@ReadOnlyComposable
internal fun StatusTone.accentColor(): Color = toneColor()

@Composable
@ReadOnlyComposable
internal fun StatusTone.solidColor(): Color = when (this) {
    StatusTone.NORMAL -> RiderColors.Primary
    StatusTone.SUCCESS -> RiderColors.Deliver
    StatusTone.WARNING -> RiderColors.Pickup
    StatusTone.DANGER -> RiderColors.Danger
    StatusTone.COLD -> RiderColors.Ice
    StatusTone.INFO -> RiderColors.Info
}

/** 实心控件上的前景色。只有品牌黄需要深色前景。 */
@Composable
@ReadOnlyComposable
internal fun StatusTone.onSolidColor(): Color =
    if (this == StatusTone.NORMAL) RiderColors.OnPrimary else Color.White
