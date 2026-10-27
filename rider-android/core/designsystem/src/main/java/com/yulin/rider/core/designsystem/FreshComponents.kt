package com.yulin.rider.core.designsystem

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Tab/根页面外壳。地图 tab 使用它而不是 [FreshStackScaffold]，因此不会出现返回按钮。
 */
@Composable
fun FreshShell(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = topBar,
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * Stack 页面统一返回栏、安全区和底部拇指热区。
 *
 * 未显式提供 [onBack] 时调用宿主的返回分发器，不要求 feature 感知导航实现。
 */
@Composable
fun FreshStackScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBack: Boolean = true,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    FreshShell(
        modifier = modifier,
        topBar = {
            FreshPageHeader(
                title = title,
                subtitle = subtitle,
                showBack = showBack,
                onBack = onBack ?: {
                    dispatcher?.onBackPressed()
                    Unit
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun FreshPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(
                        start = if (showBack) FreshSpacing.Xs else FreshSpacing.Md,
                        end = FreshSpacing.Xs,
                        top = FreshSpacing.Xs,
                        bottom = FreshSpacing.Xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(RiderDimens.TouchTarget)
                            .semantics {
                                contentDescription = "返回上一页"
                                role = Role.Button
                            },
                    ) {
                        FreshIcon(
                            type = FreshIconType.BACK,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .width(FreshBorder.ProgressSpine)
                            .height(36.dp)
                            .clip(FreshRadius.Pill.asShape())
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(FreshSpacing.Sm))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { heading() },
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
            HorizontalDivider(
                thickness = FreshBorder.Hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * 信息面板。传入 [spineTone] 后左侧出现「鲜绿进度脊」，风险态按冷链蓝/预警橙/超时红切换。
 */
@Composable
fun FreshPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    spineTone: StatusTone? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FreshRadius.Panel),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            FreshBorder.Hairline,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 1.dp,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (spineTone != null) {
                Box(
                    Modifier
                        .width(FreshBorder.ProgressSpine)
                        .fillMaxHeight()
                        .background(spineTone.accentColor())
                )
            }
            Column(
                modifier = Modifier.padding(FreshSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                if (eyebrow != null || title != null || action != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            if (eyebrow != null) {
                                Text(
                                    text = eyebrow.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = spineTone?.accentColor()
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (title != null) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        action?.invoke()
                    }
                }
                content()
            }
        }
    }
}

@Composable
fun FreshProgressSpine(
    progress: Float,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.SUCCESS,
    contentDescription: String? = null,
) {
    val accent = tone.accentColor()
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .width(FreshBorder.ProgressSpine)
            .heightIn(min = 48.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        stateDescription = "进度 ${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        val x = size.width / 2
        drawLine(
            color = track,
            start = androidx.compose.ui.geometry.Offset(x, 0f),
            end = androidx.compose.ui.geometry.Offset(x, size.height),
            strokeWidth = size.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent,
            start = androidx.compose.ui.geometry.Offset(x, 0f),
            end = androidx.compose.ui.geometry.Offset(x, size.height * progress.coerceIn(0f, 1f)),
            strokeWidth = size.width,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun FreshBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.INFO,
    icon: FreshIconType = tone.defaultIcon(),
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = tone.accentColor()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (actionText == null || onAction == null) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = text
                        stateDescription = tone.accessibleLabel()
                    }
                } else {
                    Modifier.semantics { stateDescription = tone.accessibleLabel() }
                }
            ),
        color = tone.toneContainer(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FreshSpacing.Md, vertical = FreshSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            FreshIcon(icon, contentDescription = null, tint = accent)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionText != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.defaultMinSize(minHeight = RiderDimens.TouchTarget),
                ) {
                    Text(actionText, style = MaterialTheme.typography.labelLarge, color = accent)
                }
            }
        }
    }
}

@Composable
fun FreshEmpty(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: FreshIconType = FreshIconType.TASK,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    FreshStateLayout(
        title = title,
        message = message,
        icon = icon,
        tone = StatusTone.NORMAL,
        modifier = modifier,
        actionText = actionText,
        onAction = onAction,
    )
}

@Composable
fun FreshError(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "暂时无法加载",
    onRetry: () -> Unit,
) {
    FreshStateLayout(
        title = title,
        message = message,
        icon = FreshIconType.ERROR,
        tone = StatusTone.DANGER,
        modifier = modifier,
        actionText = "重新加载",
        onAction = onRetry,
    )
}

@Composable
fun FreshLoading(
    modifier: Modifier = Modifier,
    label: String = "正在同步配送数据",
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(FreshSpacing.Xl)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = "加载中"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (LocalFreshReducedMotion.current) {
            Canvas(Modifier.size(40.dp)) {
                drawCircle(
                    color = primary,
                    style = Stroke(width = size.minDimension * .12f),
                )
            }
        } else {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(FreshSpacing.Md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun FreshStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NORMAL,
    icon: FreshIconType? = tone.defaultIcon(),
) {
    val accent = tone.accentColor()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FreshRadius.Pill))
            .background(tone.toneContainer())
            .border(
                FreshBorder.Hairline,
                accent.copy(alpha = .34f),
                RoundedCornerShape(FreshRadius.Pill),
            )
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                stateDescription = tone.accessibleLabel()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        if (icon != null) {
            FreshIcon(icon, contentDescription = null, tint = accent, size = 16.dp)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FreshBottomActionBar(
    modifier: Modifier = Modifier,
    reason: String? = null,
    reasonTone: StatusTone = StatusTone.WARNING,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = FreshSpacing.Md,
                vertical = FreshSpacing.Sm,
            ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            if (reason != null) {
                FreshBanner(
                    text = reason,
                    tone = reasonTone,
                    modifier = Modifier.clip(RoundedCornerShape(FreshRadius.Small)),
                )
            }
            content()
        }
    }
}

@Composable
fun FreshSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: FreshIconType? = null,
    enabled: Boolean = true,
    tone: StatusTone = StatusTone.NORMAL,
    onClick: () -> Unit,
) {
    val accent = tone.accentColor()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = RiderDimens.SecondaryButtonHeight)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(FreshRadius.Control),
        border = androidx.compose.foundation.BorderStroke(FreshBorder.Strong, accent.copy(alpha = .72f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
    ) {
        if (icon != null) {
            FreshIcon(icon, contentDescription = null, tint = accent)
            Spacer(Modifier.width(FreshSpacing.Xs))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FreshStateLayout(
    title: String,
    message: String?,
    icon: FreshIconType,
    tone: StatusTone,
    modifier: Modifier,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    val accent = tone.accentColor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(FreshSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(FreshRadius.Hero))
                .background(accent.copy(alpha = .11f)),
            contentAlignment = Alignment.Center,
        ) {
            FreshIcon(icon, contentDescription = null, tint = accent, size = 34.dp)
        }
        Spacer(Modifier.height(FreshSpacing.Md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(FreshSpacing.Xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(FreshSpacing.Lg))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(FreshRadius.Control),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = RiderDimens.SecondaryButtonHeight),
            ) {
                Text(actionText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun StatusTone.defaultIcon(): FreshIconType = when (this) {
    StatusTone.NORMAL -> FreshIconType.INFO
    StatusTone.SUCCESS -> FreshIconType.CHECK
    StatusTone.WARNING -> FreshIconType.WARNING
    StatusTone.DANGER -> FreshIconType.ERROR
    StatusTone.COLD -> FreshIconType.COLD
    StatusTone.INFO -> FreshIconType.INFO
}

private fun StatusTone.accessibleLabel(): String = when (this) {
    StatusTone.NORMAL -> "普通状态"
    StatusTone.SUCCESS -> "正常或已完成"
    StatusTone.WARNING -> "需要注意"
    StatusTone.DANGER -> "异常或超时"
    StatusTone.COLD -> "冷链"
    StatusTone.INFO -> "信息"
}

private fun androidx.compose.ui.unit.Dp.asShape() = RoundedCornerShape(this)

@Preview(name = "FreshShell", showBackground = true)
@Composable
private fun FreshShellPreview() {
    RiderTheme {
        FreshShell(topBar = { FreshPageHeader("今日配送", subtitle = "8 月 12 日 · 04:20") }) {
            FreshPanel(
                title = "下一站",
                spineTone = StatusTone.SUCCESS,
                modifier = Modifier.padding(it).padding(FreshSpacing.Md),
            ) {
                Text("林荫路 18 号 6 楼", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Preview(name = "FreshStackScaffold", showBackground = true)
@Composable
private fun FreshStackScaffoldPreview() {
    RiderTheme {
        FreshStackScaffold(title = "任务详情") {
            FreshEmpty("任务预览", Modifier.padding(it))
        }
    }
}

@Preview(name = "FreshPageHeader", showBackground = true)
@Composable
private fun FreshPageHeaderPreview() {
    RiderTheme { FreshPageHeader("配送路线", subtitle = "本趟 5 站", showBack = true) }
}

@Preview(name = "FreshPanel", showBackground = true)
@Composable
private fun FreshPanelPreview() {
    RiderTheme {
        FreshPanel(
            title = "冷链任务",
            eyebrow = "第 2 / 5 站",
            spineTone = StatusTone.COLD,
            modifier = Modifier.padding(FreshSpacing.Md),
        ) {
            Text("锦江花园 3 栋 1202", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Preview(name = "FreshProgressSpine", showBackground = true)
@Composable
private fun FreshProgressSpinePreview() {
    RiderTheme {
        Row(
            modifier = Modifier.padding(FreshSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Md),
        ) {
            FreshProgressSpine(.72f, tone = StatusTone.SUCCESS, contentDescription = "配送进度")
            FreshProgressSpine(.42f, tone = StatusTone.COLD, contentDescription = "冷链进度")
            FreshProgressSpine(.18f, tone = StatusTone.DANGER, contentDescription = "超时进度")
        }
    }
}

@Preview(name = "FreshBanner", showBackground = true)
@Composable
private fun FreshBannerPreview() {
    RiderTheme {
        FreshBanner("网络不可用，操作会在联网后自动上报", tone = StatusTone.WARNING)
    }
}

@Preview(name = "FreshEmpty", showBackground = true)
@Composable
private fun FreshEmptyPreview() {
    RiderTheme { FreshEmpty("当前没有待配送任务", message = "下拉刷新可重新同步") }
}

@Preview(name = "FreshError", showBackground = true)
@Composable
private fun FreshErrorPreview() {
    RiderTheme { FreshError("请检查网络后重试", onRetry = {}) }
}

@Preview(name = "FreshLoading", showBackground = true)
@Composable
private fun FreshLoadingPreview() {
    RiderTheme { FreshLoading() }
}

@Preview(name = "FreshStatusBadge", showBackground = true)
@Composable
private fun FreshStatusBadgePreview() {
    RiderTheme {
        Row(
            modifier = Modifier.padding(FreshSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            FreshStatusBadge("在岗", tone = StatusTone.SUCCESS)
            FreshStatusBadge("冷冻", tone = StatusTone.COLD)
            FreshStatusBadge("超时", tone = StatusTone.DANGER)
        }
    }
}

@Preview(name = "FreshIcon", showBackground = true)
@Composable
private fun FreshIconComponentPreview() {
    RiderTheme {
        FreshIcon(
            FreshIconType.DELIVERY,
            contentDescription = "配送",
            modifier = Modifier.padding(FreshSpacing.Md),
            tint = MaterialTheme.colorScheme.primary,
            size = 40.dp,
        )
    }
}
