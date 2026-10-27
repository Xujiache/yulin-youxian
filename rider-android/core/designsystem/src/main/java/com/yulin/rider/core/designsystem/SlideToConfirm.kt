package com.yulin.rider.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 触发阈值:水平拖动必须达到控件宽度的 60%。口袋、手套、颠簸产生的误触太多,点击不可靠。 */
private const val CONFIRM_FRACTION = 0.6f

private val TrackPadding = 6.dp

/** 右端提示箭头的尺寸与外距,文案留白要按它算。 */
private val HintWidth = 30.dp
private val HintEndPadding = 12.dp

/** 缩到这个字号还放不下就只能截断了,实际上 360dp 窄屏也到不了这一步。 */
private val MinLabelFontSize = 12.sp

/**
 * 全 App 状态流转的唯一入口(06 §3.5)。
 *
 * 取货、送达这类动作不可撤销,用点击太容易误触 —— 骑手一手拎货一手操作,
 * 口袋里、手套上、车上颠簸都会点到。所以这里要求一次真实的横向滑动。
 *
 * 外观与 [MtPrimaryButton] 对齐:同样的实底 + 同样的高度,只是左端多一个白色滑块,
 * 骑手不需要重新学习「哪个是主行动」。[action] 决定配色,与地图路线颜色一一对应。
 *
 * 手感约定:
 * - 水平拖动 ≥ 控件宽度 60% 才算数,松手未达阈值以弹簧动画弹回原位;
 * - 轨道整条都能拖,不必精准按住滑块 —— 戴手套时按不准圆点;
 * - 达阈值瞬间立刻震动 + 出声并回调 [onConfirm],滑块动画在其后播放,不让骑手等动画;
 * - 触发后自动禁用防重复提交。[text] 变化视为「换了一个动作」,滑块重新武装。
 */
@Composable
fun SlideToConfirm(
    text: String,
    action: MtAction,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onConfirm: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val soundEnabled = LocalRiderSoundEnabled.current
    val reducedMotion = LocalFreshReducedMotion.current
    val scope = rememberCoroutineScope()

    var confirmed by remember(text) { mutableStateOf(false) }
    val offsetX = remember(text) { Animatable(0f) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var dragJob by remember(text) { mutableStateOf<Job?>(null) }

    val trackHeight = riderControlHeight(RiderDimens.BigButtonHeight)
    val thumbSize = trackHeight - TrackPadding * 2
    val thumbPx = with(density) { thumbSize.toPx() }
    val paddingPx = with(density) { TrackPadding.toPx() }

    // 文案两侧要让开的东西:左边是停在原位的滑块,右边是提示箭头。取两者较大值保持居中。
    val labelInset = maxOf(TrackPadding + thumbSize, HintEndPadding + HintWidth)

    val maxOffsetPx = (trackWidthPx - thumbPx - paddingPx * 2).coerceAtLeast(0f)
    // 窄屏上 60% 控件宽度可能超过滑块可行程,收敛到行程末端,保证动作永远可完成
    val thresholdPx = (trackWidthPx * CONFIRM_FRACTION).coerceAtMost(maxOffsetPx)

    val active = enabled && !confirmed
    val trackColor = if (active) action.fillColor() else MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = if (active) action.contentColor() else RiderNeutral.InkDisabled
    val progress = if (maxOffsetPx > 0f) (offsetX.value / maxOffsetPx).coerceIn(0f, 1f) else 0f

    LaunchedEffect(enabled) {
        if (!enabled && !confirmed) offsetX.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .clip(RoundedCornerShape(FreshRadius.Control))
            .background(trackColor)
            .then(
                if (action == MtAction.SECONDARY) {
                    Modifier.border(
                        FreshBorder.Hairline,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(FreshRadius.Control),
                    )
                } else {
                    Modifier
                }
            )
            .draggable(
                enabled = active,
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // 记住最后一次位移的协程,松手时要等它落地。
                    // Animatable 的操作是互斥的:回弹 animateTo 一旦和还没执行完的 snapTo
                    // 撞上,后到的 snapTo 会把动画取消掉,滑块就钉在手指离开的位置不动了。
                    dragJob = scope.launch {
                        offsetX.snapTo((offsetX.value + delta).coerceIn(0f, maxOffsetPx))
                    }
                },
                onDragStopped = {
                    // 位移协程都在同一个主线程调度器上按序执行,等最后一个就等于等全部
                    dragJob?.join()
                    if (thresholdPx > 0f && offsetX.value >= thresholdPx) {
                        confirmed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        RiderSound.playConfirm(soundEnabled)
                        onConfirm()
                        if (reducedMotion) {
                            offsetX.snapTo(maxOffsetPx)
                        } else {
                            offsetX.animateTo(
                                targetValue = maxOffsetPx,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                            )
                        }
                    } else {
                        if (reducedMotion) {
                            offsetX.snapTo(0f)
                        } else {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow,
                                ),
                            )
                        }
                    }
                },
            )
            .semantics {
                contentDescription = if (active) {
                    "$text，向右滑动确认"
                } else {
                    listOfNotNull(text, disabledReason).joinToString("，不可用：")
                }
                stateDescription = when {
                    confirmed -> "已确认"
                    active -> "等待滑动"
                    else -> "不可用"
                }
                if (!active) disabled()
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 已划过的行程压深一层,给骑手「还差多少」的直观反馈
        Box(
            modifier = Modifier
                .padding(TrackPadding)
                .height(thumbSize)
                .width(with(density) { (thumbPx + offsetX.value).toDp() })
                .clip(RoundedCornerShape(thumbSize / 2))
                .background(Color.Black.copy(alpha = if (active) 0.12f else 0f)),
        )

        // 文案随可用宽度缩放,不截断。
        //
        // 原来两边各留一个 trackHeight(48dp+)再配 Ellipsis,窄屏上「滑动确认已送达」
        // 会被截成「滑动确认已…」—— 骑手看不出这一滑到底是取货还是送达,而这两个动作
        // 都不可撤销。留白改成按真实遮挡物计算(左边滑块、右边提示箭头),剩下的交给缩放:
        // 宁可字小一号,也不能让动作名缺一半。
        Text(
            text = if (confirmed) "已确认" else text,
            style = MaterialTheme.typography.titleMedium,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = MinLabelFontSize,
                maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                stepSize = 0.5.sp,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = labelInset)
                .graphicsLayer { alpha = (1f - progress * 1.2f).coerceIn(0f, 1f) },
        )

        SlideHint(
            visible = active,
            accent = labelColor,
            progress = progress,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = HintEndPadding),
        )

        Box(
            modifier = Modifier
                .padding(horizontal = TrackPadding)
                .graphicsLayer { translationX = offsetX.value }
                .size(thumbSize)
                .clip(RoundedCornerShape(thumbSize / 2))
                .background(if (active) RiderColors.Surface else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (confirmed) {
                CheckMarkIcon(color = trackColor, modifier = Modifier.size(thumbSize * 0.44f))
            } else {
                Chevrons(
                    color = if (active) trackColor else RiderNeutral.InkDisabled,
                    alpha = 1f,
                    modifier = Modifier.size(width = thumbSize * 0.46f, height = thumbSize * 0.34f),
                )
            }
        }
    }
}

@Composable
private fun SlideHint(
    visible: Boolean,
    accent: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // 不可用时连动画都不要起:一个班次十几小时,后台跑着的无限动画是实打实的耗电
    if (!visible) return
    if (LocalFreshReducedMotion.current) {
        Chevrons(
            color = accent,
            alpha = 0.75f,
            modifier = modifier
                .size(width = 30.dp, height = 20.dp)
                .graphicsLayer { alpha = (1f - progress * 1.6f).coerceIn(0f, 1f) },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "slideHint")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "slideHintAlpha",
    )
    Chevrons(
        color = accent,
        alpha = pulse,
        modifier = modifier
            .size(width = 30.dp, height = 20.dp)
            .graphicsLayer { alpha = (1f - progress * 1.6f).coerceIn(0f, 1f) },
    )
}

@Composable
internal fun Chevrons(
    color: Color,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = size.height * 0.15f
        val chevronWidth = size.width / 3.4f
        repeat(3) { index ->
            val startX = index * (size.width - chevronWidth) / 2f
            val midY = size.height / 2f
            val a = alpha * (0.45f + index * 0.275f)
            drawLine(
                color = color,
                alpha = a,
                start = Offset(startX, midY - size.height * 0.3f),
                end = Offset(startX + chevronWidth, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                alpha = a,
                start = Offset(startX + chevronWidth, midY),
                end = Offset(startX, midY + size.height * 0.3f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 对勾图标。项目未引入 material-icons 依赖,几何图形直接画,顺带避免图标包体积。 */
@Composable
fun CheckMarkIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.17f
        drawLine(
            color = color,
            start = Offset(size.width * 0.08f, size.height * 0.55f),
            end = Offset(size.width * 0.38f, size.height * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.38f, size.height * 0.86f),
            end = Offset(size.width * 0.92f, size.height * 0.16f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 控件高度随系统字号同比放大(上限 1.35 倍)。
 * 固定 dp 高度 + 放大的 sp 文字正是「字号 1.3 倍破版」的根因,这里让容器跟着长高。
 */
@Composable
internal fun riderControlHeight(base: Dp): Dp {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.35f)
    return base * fontScale
}

@Preview(name = "SlideToConfirm", showBackground = true)
@Composable
private fun SlideToConfirmPreview() {
    RiderTheme {
        Box(Modifier.padding(FreshSpacing.Md)) {
            SlideToConfirm(text = "滑动确认已取货", action = MtAction.PICKUP, onConfirm = {})
        }
    }
}

@Preview(name = "SlideToConfirm disabled", showBackground = true)
@Composable
private fun SlideToConfirmDisabledPreview() {
    RiderTheme {
        Box(Modifier.padding(FreshSpacing.Md)) {
            SlideToConfirm(
                text = "滑动确认已送达",
                action = MtAction.DELIVER,
                enabled = false,
                disabledReason = "请先拍摄送达凭证",
                onConfirm = {},
            )
        }
    }
}
