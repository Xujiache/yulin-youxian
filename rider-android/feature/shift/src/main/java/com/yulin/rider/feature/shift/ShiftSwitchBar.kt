package com.yulin.rider.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.model.ShiftCurrent

/**
 * 首页的在岗条。左侧「鲜绿进度脊」是全端的在线状态标志，文字与图标同步表达状态。
 */
@Composable
fun ShiftSwitchBar(
    modifier: Modifier = Modifier,
    viewModel: ShiftViewModel = viewModel(key = "rider-shift"),
) {
    val state by viewModel.state.collectAsState()

    ShiftSwitchContent(
        state = state,
        modifier = modifier,
        onToggle = {
            if (state.shift.onDuty) viewModel.offDuty() else viewModel.openChecklist()
        },
    )

    state.checklist?.let { checklist ->
        OnDutyChecklistDialog(
            checklist = checklist,
            submitting = state.submitting,
            onRecheck = viewModel::recheck,
            onConfirm = viewModel::confirmOnDuty,
            onDismiss = viewModel::dismissChecklist,
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("操作提示", style = MaterialTheme.typography.titleLarge) },
            text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) {
                    Text("知道了", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun ShiftSwitchContent(
    state: ShiftUiState,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val onDuty = state.shift.onDuty
    FreshPanel(
        modifier = modifier.padding(
            horizontal = FreshSpacing.Md,
            vertical = FreshSpacing.Xs,
        ),
        eyebrow = if (onDuty) "配送服务在线" else "配送服务暂停",
        spineTone = if (onDuty) StatusTone.SUCCESS else StatusTone.NORMAL,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                ) {
                    FreshIcon(
                        type = if (onDuty) FreshIconType.RIDER else FreshIconType.CLOCK,
                        contentDescription = null,
                        tint = if (onDuty) RiderColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (onDuty) "已上班" else "未上班",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (onDuty) RiderColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (onDuty) {
                        "在线 ${formatDuration(state.liveOnlineSeconds)}"
                    } else {
                        "上班后才会派单给你"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(FreshSpacing.Sm))
            BigActionButton(
                text = if (onDuty) "下班" else "上班",
                modifier = Modifier.width(136.dp),
                enabled = !state.submitting,
                disabledReason = "正在切换在岗状态",
                tone = if (onDuty) StatusTone.DANGER else StatusTone.SUCCESS,
                icon = if (onDuty) FreshIconType.CLOSE else FreshIconType.CHECK,
                onClick = onToggle,
            )
        }

        if (onDuty) {
            ShiftStatsRow(
                taskCount = state.shift.taskCount,
                deliveredCount = state.shift.deliveredCount,
                onTimeCount = state.shift.onTimeCount,
                mileageMeters = state.shift.mileageMeters.toLong(),
            )
        }
    }
}

@Composable
internal fun ShiftStatsRow(
    taskCount: Int,
    deliveredCount: Int,
    onTimeCount: Int,
    mileageMeters: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            StatCell("今日单量", "$taskCount", Modifier.weight(1f))
            StatCell("已完成", "$deliveredCount", Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            StatCell("准时", "$onTimeCount", Modifier.weight(1f))
            StatCell("里程", formatKm(mileageMeters), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 上班前检查。每项用矢量状态图标与文字双通道表达。 */
@Composable
private fun OnDutyChecklistDialog(
    checklist: OnDutyChecklist,
    submitting: Boolean,
    onRecheck: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上班前检查", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
                if (checklist.unverified) {
                    FreshBanner(
                        text = "保活检测尚未接入，当前按已满足处理。若配送中掉线，请到设置手动检查。",
                        tone = StatusTone.WARNING,
                        icon = FreshIconType.BATTERY,
                    )
                }
                checklist.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(vertical = FreshSpacing.Xs),
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                    ) {
                        FreshIcon(
                            type = if (item.satisfied) FreshIconType.CHECK else FreshIconType.ERROR,
                            contentDescription = if (item.satisfied) "已满足" else "未满足",
                            tint = if (item.satisfied) RiderColors.Success else RiderColors.Danger,
                        )
                        Column {
                            Text(item.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                item.why,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (checklist.blocked) {
                    FreshBanner(
                        text = "定位权限是上班硬性条件，请开启后重新检测。",
                        tone = StatusTone.DANGER,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !checklist.blocked && !submitting,
            ) {
                Text(
                    if (submitting) "提交中…" else "确认上班",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRecheck) {
                    Text("重新检测", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onDismiss) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}

internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0 分钟"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
        hours > 0 -> "$hours 小时"
        else -> "$minutes 分钟"
    }
}

internal fun formatKm(meters: Long): String =
    if (meters < 1000) "$meters m" else
        String.format(java.util.Locale.CHINA, "%.1f km", meters / 1000.0)

@Preview(name = "在岗条 · 上班", showBackground = true)
@Composable
private fun ShiftSwitchOnDutyPreview() {
    RiderTheme {
        ShiftSwitchContent(
            state = ShiftUiState(
                shift = ShiftCurrent(
                    onDuty = true,
                    taskCount = 8,
                    deliveredCount = 5,
                    onTimeCount = 5,
                    mileageMeters = 12_600,
                ),
                liveOnlineSeconds = 9_240,
                loading = false,
            ),
            onToggle = {},
        )
    }
}

@Preview(name = "在岗条 · 下班", showBackground = true)
@Composable
private fun ShiftSwitchOffDutyPreview() {
    RiderTheme {
        ShiftSwitchContent(
            state = ShiftUiState(loading = false),
            onToggle = {},
        )
    }
}
