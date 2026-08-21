package com.yulin.rider.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ 顶栏 */

/**
 * 主界面顶栏：抽屉入口 + 在岗状态胶囊 + 右侧动作。
 */
@Composable
fun MtTopBar(
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    statusPill: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            Box(
                modifier = Modifier
                    .size(RiderDimens.TouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onMenu)
                    .semantics {
                        contentDescription = "打开菜单"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                FreshIcon(FreshIconType.MENU, contentDescription = null, size = 22.dp)
            }
            statusPill?.invoke()
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

/**
 * 二级页顶栏：返回 + 标题。白底、发丝分隔线，不投影。
 */
@Composable
fun MtPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = FreshSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        modifier = Modifier
                            .size(RiderDimens.TouchTarget)
                            .clip(CircleShape)
                            .clickable(onClick = onBack)
                            .semantics {
                                contentDescription = "返回"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        FreshIcon(FreshIconType.BACK, contentDescription = null, size = 20.dp)
                    }
                } else {
                    Spacer(Modifier.width(FreshSpacing.Xxs))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = RiderColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.tabularFigures(),
                            color = RiderNeutral.InkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
            MtDivider()
        }
    }
}

/**
 * 二级页脚手架。灰底、顶栏、可选吸底操作条，内容区已避开系统栏。
 */
@Composable
fun MtScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // 底色取白而不是页面灰：状态栏与导航栏区域要和顶栏、吸底条同色，
    // 否则安全区会在白色顶栏上方露出一条灰边。灰底只铺在中间的内容区。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding(),
    ) {
        MtPageHeader(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
        Column(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
        bottomBar()
    }
}

/**
 * 在岗状态胶囊。绿点在岗、红点离线，色点之外还有文字，色弱骑手也能判断。
 */
@Composable
fun MtStatusPill(
    text: String,
    onDuty: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val dot = if (onDuty) RiderColors.Deliver else RiderColors.Danger
    val base = modifier
        .clip(RoundedCornerShape(FreshRadius.Pill))
        .background(MaterialTheme.colorScheme.surface)
        .border(FreshBorder.Hairline, MaterialTheme.colorScheme.outline, RoundedCornerShape(FreshRadius.Pill))
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .heightIn(min = 32.dp)
            .padding(horizontal = FreshSpacing.Sm)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                stateDescription = if (onDuty) "在岗" else "已下线"
                if (onClick != null) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = RiderColors.Ink)
        if (onClick != null) {
            FreshIcon(
                FreshIconType.CHEVRON_DOWN,
                contentDescription = null,
                tint = RiderNeutral.InkMuted,
                size = 16.dp,
            )
        }
    }
}

/* ------------------------------------------------------------------ 文字 Tab */

data class MtTab(val label: String, val count: Int? = null)

/**
 * 文字 Tab。选中态是加粗黑字加一段短下划线，不用整条分割线，也不用色块。
 */
@Composable
fun MtTextTabs(
    tabs: List<MtTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FreshSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selectedIndex
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(FreshRadius.Small))
                        .clickable { if (!active) onSelect(index) }
                        .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs)
                        .semantics {
                            role = Role.Tab
                            stateDescription = if (active) "已选中" else "未选中"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (tab.count != null && tab.count > 0) "${tab.label} ${tab.count}" else tab.label,
                        style = if (active) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        color = if (active) RiderColors.Ink else RiderNeutral.InkMuted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(FreshSpacing.Xxs))
                    Box(
                        Modifier
                            .width(20.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(FreshRadius.Pill))
                            .background(if (active) RiderColors.Ink else Color.Transparent)
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
    }
}

/* ------------------------------------------------------------------ 信息条 */

/**
 * 顶部信息条。蓝底蓝字，可关闭，整宽贴边。
 *
 * [actionText] 是独立的行动槽位，和 [onDismiss] 的关闭叉分开：
 * 把「点此重试」挂在关闭叉上，读屏念出来的是「关闭提示」，骑手按语音提示点下去
 * 实际触发的却是重试，两者语义完全相反。
 */
@Composable
fun MtInfoBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.INFO,
    icon: FreshIconType = FreshIconType.SHIELD,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = tone.toneContainer()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = text },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                FreshIcon(icon, contentDescription = null, tint = tone.toneColor(), size = 18.dp)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tone.toneColor(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (actionText != null && onAction != null) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.titleSmall,
                    color = tone.toneColor(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(FreshRadius.Small))
                        .clickable(onClick = onAction)
                        .heightIn(min = 28.dp)
                        .padding(horizontal = FreshSpacing.Xs, vertical = FreshSpacing.Xxs)
                        .semantics {
                            contentDescription = actionText
                            role = Role.Button
                        },
                )
            }
            if (onDismiss != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .semantics {
                            contentDescription = "关闭提示"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    FreshIcon(
                        FreshIconType.CLOSE,
                        contentDescription = null,
                        tint = tone.toneColor(),
                        size = 16.dp,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 按钮 */

/**
 * 主行动按钮。
 *
 * 颜色由 [action] 语义决定，调用方不需要也不应该自己选色：
 * 接受类黄底黑字、取货橙红、送达绿色。
 */
@Composable
fun MtPrimaryButton(
    text: String,
    action: MtAction,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    icon: FreshIconType? = null,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val fill = action.fillColor()
    val content = action.contentColor()
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(FreshRadius.Control),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = RiderNeutral.InkDisabled,
        ),
        border = if (action == MtAction.SECONDARY) {
            BorderStroke(FreshBorder.Hairline, MaterialTheme.colorScheme.outline)
        } else {
            null
        },
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = FreshSpacing.Md,
            vertical = FreshSpacing.Xs,
        ),
        modifier = modifier
            .defaultMinSize(minHeight = riderControlHeight(RiderDimens.BigButtonHeight))
            .semantics {
                role = Role.Button
                stateDescription = if (enabled) "可用" else disabledReason ?: "不可用"
                if (!enabled) disabled()
            },
    ) {
        if (icon != null) {
            FreshIcon(icon, contentDescription = null, tint = content, size = 18.dp)
            Spacer(Modifier.width(FreshSpacing.Xxs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 竖排次级动作：图标在上、文字在下。
 * 底部操作条左侧的「联系」「遇到问题」「在线对话」都是这个形态。
 */
@Composable
fun MtGhostAction(
    text: String,
    icon: FreshIconType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) RiderColors.Ink else RiderNeutral.InkDisabled
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FreshRadius.Small))
            .clickable(enabled = enabled, onClick = onClick)
            .widthIn(min = 48.dp)
            .padding(horizontal = FreshSpacing.Xs, vertical = FreshSpacing.Xxs)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                role = Role.Button
                if (!enabled) disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FreshIcon(icon, contentDescription = null, tint = tint, size = 20.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) RiderNeutral.InkMuted else RiderNeutral.InkDisabled,
            maxLines = 1,
        )
    }
}

/**
 * 底部操作条：左侧若干竖排次级动作，右侧主按钮占满剩余宽度。
 *
 * 窄屏或大字号下自动换成两行 —— 次级动作在上、主按钮独占一行。
 * 「联系」「遇到问题」这类次级动作各要占掉 60dp 上下，320dp 的机器上再挤一个
 * 滑动条，主按钮的文案就只剩三四十 dp，「滑动确认已送达」会被切成「滑动确」。
 * 骑手看不出这一滑到底是取货还是送达，而这两个动作都不可撤销。
 */
@Composable
fun MtBottomActionBar(
    modifier: Modifier = Modifier,
    hint: String? = null,
    hintTone: StatusTone = StatusTone.WARNING,
    secondaryActions: @Composable RowScope.() -> Unit = {},
    primary: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = FreshElevation.StickyBar,
    ) {
        Column {
            if (hint != null) {
                MtInfoBar(
                    text = hint,
                    tone = hintTone,
                    icon = FreshIconType.PROBLEM,
                )
            }
            BoxWithConstraints {
                // 字号放大时次级动作和滑块一起变宽，阈值要跟着放大
                val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.35f)
                val stacked = maxWidth < SingleRowMinWidth * fontScale
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs)

                if (stacked) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = FreshSpacing.Sm,
                                    end = FreshSpacing.Sm,
                                    top = FreshSpacing.Xxs,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Md),
                        ) {
                            secondaryActions()
                        }
                        Row(
                            modifier = rowModifier,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            primary()
                        }
                    }
                } else {
                    Row(
                        modifier = rowModifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                    ) {
                        secondaryActions()
                        primary()
                    }
                }
            }
        }
    }
}

/** 低于这个宽度就把主按钮挪到单独一行。360dp 的常见机型在标准字号下仍是一行。 */
private val SingleRowMinWidth = 356.dp

/* ------------------------------------------------------------------ 卡片与行程 */

/** 白卡片。美团的卡片不描边不投影，靠页面底灰和间距分隔。 */
@Composable
fun MtCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(FreshRadius.Panel))
        .background(MaterialTheme.colorScheme.surface)
    Column(
        modifier = if (onClick != null) {
            base.clickable(onClick = onClick).semantics { role = Role.Button }
        } else {
            base
        },
    ) {
        content()
    }
}

/** 卡片头行：左侧时限，右侧金额或序号。 */
@Composable
fun MtCardHeader(
    modifier: Modifier = Modifier,
    scheduled: Boolean = false,
    timeText: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
    ) {
        if (scheduled) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(FreshRadius.Pill))
                    .background(RiderColors.Pickup)
                    .padding(horizontal = FreshSpacing.Xs, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FreshIcon(FreshIconType.TIMER, contentDescription = null, tint = Color.White, size = 12.dp)
                Text("预", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        } else {
            FreshIcon(
                FreshIconType.TIMER,
                contentDescription = null,
                tint = RiderColors.Pickup,
                size = 16.dp,
            )
        }
        // 时限里的数字部分用橙色加粗，其余保持深色，扫一眼先看到「还剩多久」
        Text(
            text = highlightNumbers(timeText, RiderColors.Pickup),
            style = MaterialTheme.typography.titleSmall.tabularFigures(),
            color = RiderColors.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** 行程段。取货段与送达段各一，未激活的一段整体转灰。 */
enum class MtLeg { PICKUP, DELIVER }

/**
 * 取送两段地址。
 *
 * 左侧是圆点轨道：当前要去的那一段用彩色圆点配黑色粗体，另一段用灰点配灰字。
 * 骑手扫一眼颜色就知道下一步该去商家还是去顾客，不用读文字。
 */
@Composable
fun MtLegBlock(
    pickupTitle: String,
    deliverTitle: String,
    activeLeg: MtLeg,
    modifier: Modifier = Modifier,
    pickupSubtitle: String? = null,
    deliverSubtitle: String? = null,
    pickupDistance: String? = null,
    deliverDistance: String? = null,
    onNavigatePickup: (() -> Unit)? = null,
    onNavigateDeliver: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MtLegRow(
            leg = MtLeg.PICKUP,
            active = activeLeg == MtLeg.PICKUP,
            title = pickupTitle,
            subtitle = pickupSubtitle,
            distance = pickupDistance,
            showConnectorBelow = true,
            onNavigate = onNavigatePickup,
        )
        MtLegRow(
            leg = MtLeg.DELIVER,
            active = activeLeg == MtLeg.DELIVER,
            title = deliverTitle,
            subtitle = deliverSubtitle,
            distance = deliverDistance,
            showConnectorBelow = false,
            onNavigate = onNavigateDeliver,
        )
    }
}

@Composable
private fun MtLegRow(
    leg: MtLeg,
    active: Boolean,
    title: String,
    subtitle: String?,
    distance: String?,
    showConnectorBelow: Boolean,
    onNavigate: (() -> Unit)?,
) {
    val dotColor = when {
        !active -> RiderNeutral.InkDisabled
        leg == MtLeg.PICKUP -> RiderColors.Pickup
        else -> RiderColors.Deliver
    }
    val titleColor = if (active) RiderColors.Ink else RiderNeutral.InkMuted
    val label = if (leg == MtLeg.PICKUP) "取货点" else "送达点"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 取 IntrinsicSize.Min 才能让左侧连接线随右侧文字高度伸缩；
            // 不给这个约束的话轨道列高度无界，weight 拿不到剩余空间，线会连不到下一个圆点。
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(label, title, subtitle).joinToString("，")
            },
        verticalAlignment = Alignment.Top,
    ) {
        // 轨道列：距离或圆点 + 连接线
        Column(
            modifier = Modifier
                .width(44.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (distance != null) {
                MtDistanceLabel(distance)
            } else {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            if (showConnectorBelow) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .width(FreshBorder.Hairline)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (showConnectorBelow) FreshSpacing.Xs else 0.dp),
        ) {
            Text(
                text = title,
                style = if (active) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RiderNeutral.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onNavigate != null) {
            Box(
                modifier = Modifier
                    .size(RiderDimens.TouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onNavigate)
                    .semantics {
                        contentDescription = "导航到$label"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(FreshBorder.Hairline, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    FreshIcon(
                        FreshIconType.NAVIGATE,
                        contentDescription = null,
                        tint = RiderColors.Ink,
                        size = 16.dp,
                    )
                }
            }
        }
    }
}

/** 距离标签：数字一行、单位一行，和圆点轨道对齐。 */
@Composable
fun MtDistanceLabel(distance: String, modifier: Modifier = Modifier) {
    val (value, unit) = splitDistance(distance)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = distance },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.tabularFigures(),
            color = RiderColors.Ink,
            maxLines = 1,
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = RiderNeutral.InkMuted,
                maxLines = 1,
            )
        }
    }
}

/** 取/送 圆形标记。用在地图气泡和详情页地址行前。 */
@Composable
fun MtLegBadge(leg: MtLeg, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val color = if (leg == MtLeg.PICKUP) RiderColors.Pickup else RiderColors.Deliver
    val label = if (leg == MtLeg.PICKUP) "取" else "送"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = if (leg == MtLeg.PICKUP) "取货点" else "送达点" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/** 联系人条。带边框的一行，尾号高亮，右侧拨号。 */
@Composable
fun MtContactStrip(
    name: String,
    phone: String,
    modifier: Modifier = Modifier,
    onCall: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FreshRadius.Control))
            .border(FreshBorder.Hairline, MaterialTheme.colorScheme.outline, RoundedCornerShape(FreshRadius.Control))
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs)
            .semantics(mergeDescendants = true) { contentDescription = "$name $phone" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        Text(
            text = highlightPhoneTail(name, phone),
            style = MaterialTheme.typography.bodyMedium.tabularFigures(),
            color = RiderColors.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onCall != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCall)
                    .semantics {
                        contentDescription = "拨打电话"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                FreshIcon(
                    FreshIconType.PHONE,
                    contentDescription = null,
                    tint = RiderColors.Ink,
                    size = 18.dp,
                )
            }
        }
    }
}

/** 小标签。同城、冷链、先抢这类。 */
@Composable
fun MtTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.INFO,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(FreshRadius.Tag))
            .background(tone.toneContainer())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = tone.toneColor(),
        maxLines = 1,
    )
}

/** 空态。灰色圆形占位 + 标题 + 说明，不用插画。 */
@Composable
fun MtEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: FreshIconType? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FreshSpacing.Xl, vertical = FreshSpacing.Huge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                FreshIcon(icon, contentDescription = null, tint = RiderNeutral.InkDisabled, size = 34.dp)
            } else {
                Box(
                    Modifier
                        .width(30.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(FreshRadius.Pill))
                        .background(RiderNeutral.InkDisabled)
                )
            }
        }
        Spacer(Modifier.height(FreshSpacing.Md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = RiderColors.Ink,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(FreshSpacing.Xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = RiderNeutral.InkMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(FreshSpacing.Lg))
            action()
        }
    }
}

/** 指标块。数字在上、标签在下，数字等宽避免刷新时横跳。 */
@Composable
fun MtMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    hero: Boolean = false,
    valueColor: Color = RiderColors.Ink,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label $value${unit.orEmpty()}"
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = if (hero) FreshNumerals.Hero else FreshNumerals.Metric,
                color = valueColor,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = RiderNeutral.InkMuted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = RiderNeutral.InkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分区标题。 */
@Composable
fun MtSectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = RiderColors.Ink,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun MtDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = FreshBorder.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 地图底部抽屉。圆角顶 + 拖拽把手，压在地图上方。
 */
@Composable
fun MtMapSheet(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = FreshRadius.Sheet, topEnd = FreshRadius.Sheet),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = FreshElevation.Floating,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FreshSpacing.Xs),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(FreshRadius.Pill))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
            content()
        }
    }
}

/** 地图上的白色圆形控件，例如定位、刷新。 */
@Composable
fun MtMapControl(
    icon: FreshIconType,
    contentDescription: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(FreshRadius.Control))
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        shape = RoundedCornerShape(FreshRadius.Control),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = FreshElevation.StickyBar,
    ) {
        Column(
            modifier = Modifier
                .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                .padding(FreshSpacing.Xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FreshIcon(icon, contentDescription = null, tint = RiderColors.Ink, size = 18.dp)
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = RiderNeutral.InkMuted,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 工具 */

/** 把文本里的连续数字染成强调色并加粗，用于「还剩 20 分钟」这类时限文案。 */
internal fun highlightNumbers(text: String, color: Color) = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch.isDigit()) {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append(text.substring(start, index))
            }
        } else {
            append(ch)
            index++
        }
    }
}

/** 联系人行：姓名保持深色，手机尾号染橙，方便核对。 */
internal fun highlightPhoneTail(name: String, phone: String) = buildAnnotatedString {
    append(name)
    if (phone.isNotBlank()) {
        append("  ")
        val tailStart = (phone.length - 4).coerceAtLeast(0)
        append(phone.substring(0, tailStart))
        withStyle(SpanStyle(color = RiderColors.Pickup, fontWeight = FontWeight.Bold)) {
            append(phone.substring(tailStart))
        }
    }
}

/** 把「1.7 km」拆成数值与单位两行。 */
internal fun splitDistance(distance: String): Pair<String, String> {
    val trimmed = distance.trim()
    val splitIndex = trimmed.indexOfFirst { it.isLetter() || it == '米' || it == '公' || it == '千' }
    if (splitIndex <= 0) return trimmed to ""
    return trimmed.substring(0, splitIndex).trim() to trimmed.substring(splitIndex).trim()
}

/* ------------------------------------------------------------------ 预览 */

@Preview(name = "美团组件 · 任务卡", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun MtTaskCardPreview() {
    RiderTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(FreshSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            MtCard {
                MtCardHeader(scheduled = false, timeText = "还剩83分钟送达")
                MtLegBlock(
                    pickupTitle = "江西面馆（望京店）",
                    pickupSubtitle = "北京市朝阳区望京新荟城购物中心五层直梯口",
                    pickupDistance = "1.7 km",
                    deliverTitle = "奥林匹克花园三期北区312号楼",
                    deliverDistance = "9.8 km",
                    activeLeg = MtLeg.PICKUP,
                    modifier = Modifier.padding(horizontal = FreshSpacing.Sm),
                )
                Row(
                    modifier = Modifier.padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xxs),
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
                ) {
                    MtTag("先抢", tone = StatusTone.WARNING)
                    MtTag("同城")
                }
                Box(Modifier.padding(FreshSpacing.Sm)) {
                    MtPrimaryButton(
                        text = "抢单",
                        action = MtAction.ACCEPT,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {},
                    )
                }
            }

            MtCard {
                MtCardHeader(
                    scheduled = true,
                    timeText = "还剩42分钟(11:45~12:08)送货",
                    trailing = {
                        Text(
                            "# 1",
                            style = MaterialTheme.typography.titleSmall.tabularFigures(),
                            color = RiderNeutral.InkMuted,
                        )
                    },
                )
                MtLegBlock(
                    pickupTitle = "金手勺家常菜",
                    pickupSubtitle = "临桂区山水凤凰城旁规划路58号",
                    deliverTitle = "桂林碧桂园 3 栋 1003",
                    activeLeg = MtLeg.PICKUP,
                    modifier = Modifier.padding(horizontal = FreshSpacing.Sm),
                )
                MtDivider(Modifier.padding(top = FreshSpacing.Xs))
                Row(
                    modifier = Modifier.padding(FreshSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                ) {
                    MtGhostAction("联系", FreshIconType.PHONE) {}
                    MtPrimaryButton(
                        text = "我已取货",
                        action = MtAction.PICKUP,
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "美团组件 · 顶栏与空态", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun MtShellPreview() {
    RiderTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            MtTopBar(
                onMenu = {},
                statusPill = { MtStatusPill("上线中", onDuty = true, onClick = {}) },
                actions = {
                    FreshIcon(FreshIconType.BELL, contentDescription = "消息", size = 22.dp)
                },
            )
            MtTextTabs(
                tabs = listOf(MtTab("新任务"), MtTab("待取货", 3), MtTab("配送中", 1)),
                selectedIndex = 1,
                onSelect = {},
            )
            MtInfoBar(text = "当前保险生效中", onDismiss = {})
            MtEmptyState(
                title = "附近暂时没有任务",
                message = "您处于下线状态，系统不再为您接单，并停止新单提醒",
            )
        }
    }
}

@Preview(name = "美团组件 · 三色按钮", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun MtButtonPreview() {
    RiderTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(FreshSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            MtPrimaryButton("上线", MtAction.ACCEPT, Modifier.fillMaxWidth()) {}
            MtPrimaryButton("我已取货", MtAction.PICKUP, Modifier.fillMaxWidth()) {}
            MtPrimaryButton("我已送达", MtAction.DELIVER, Modifier.fillMaxWidth()) {}
            MtPrimaryButton("刷新列表", MtAction.SECONDARY, Modifier.fillMaxWidth()) {}
            MtContactStrip(name = "抗(先生)", phone = "176****0643", onCall = {})
        }
    }
}
