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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 触发阈值:水平拖动必须达到控件宽度的 60%。口袋、手套、颠簸产生的误触太多,点击不可靠。 */
private const val CONFIRM_FRACTION = 0.6f

private val TrackPadding = 6.dp

/**
 * 全 App 状态流转的唯一入口(06 §3.5)。
 *
 * 手感约定:
 * - 水平拖动 ≥ 控件宽度 60% 才算数,松手未达阈值以弹簧动画弹回原位;
 * - 达阈值瞬间立刻震动 + 出声并回调 [onConfirm],滑块动画在其后播放,不让骑手等动画;
 * - 触发后自动禁用防重复提交。[text] 变化视为「换了一个动作」,滑块重新武装。
 */
@Composable
fun SlideToConfirm(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    tone: StatusTone = StatusTone.SUCCESS,
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

    val trackHeight = riderControlHeight(RiderDimens.BigButtonHeight)
    val thumbSize = trackHeight - TrackPadding * 2
    val thumbPx = with(density) { thumbSize.toPx() }
    val paddingPx = with(density) { TrackPadding.toPx() }

    val maxOffsetPx = (trackWidthPx - thumbPx - paddingPx * 2).coerceAtLeast(0f)
    // 窄屏上 60% 控件宽度可能超过滑块可行程,收敛到行程末端,保证动作永远可完成
    val thresholdPx = (trackWidthPx * CONFIRM_FRACTION).coerceAtMost(maxOffsetPx)

    val active = enabled && !confirmed
    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val accent = if (active) tone.solidColor() else disabledColor
    val progress = if (maxOffsetPx > 0f) (offsetX.value / maxOffsetPx).coerceIn(0f, 1f) else 0f

    LaunchedEffect(enabled) {
        if (!enabled && !confirmed) offsetX.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .onSizeChanged { trackWidthPx = it.width }
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(accent.copy(alpha = if (active) 0.12f else 0.08f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(trackHeight / 2))
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
        // 已划过的行程作为进度条,给骑手「还差多少」的直观反馈
        Box(
            modifier = Modifier
                .padding(TrackPadding)
                .height(thumbSize)
                .width(with(density) { (thumbPx + offsetX.value).toDp() })
                .clip(RoundedCornerShape(thumbSize / 2))
                .background(accent.copy(alpha = 0.28f)),
        )

        Text(
            text = if (confirmed) "已确认" else text,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = trackHeight)
                .graphicsLayer { alpha = (1f - progress * 1.2f).coerceIn(0f, 1f) },
        )

        SlideHint(
            visible = active,
            accent = accent,
            progress = progress,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp),
        )

        Box(
            modifier = Modifier
                .padding(horizontal = TrackPadding)
                .graphicsLayer { translationX = offsetX.value }
                .size(thumbSize)
                .clip(RoundedCornerShape(thumbSize / 2))
                .background(accent)
                .draggable(
                    enabled = active,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(0f, maxOffsetPx))
                        }
                    },
                    onDragStopped = {
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
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (confirmed) {
                CheckMarkIcon(color = Color.White, modifier = Modifier.size(thumbSize * 0.44f))
            } else {
                Chevrons(
                    color = Color.White,
                    alpha = if (active) 1f else 0.6f,
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
            SlideToConfirm(text = "滑动确认已取货", onConfirm = {})
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
                enabled = false,
                disabledReason = "请先拍摄送达凭证",
                onConfirm = {},
            )
        }
    }
}
