package com.yulin.rider.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtStatusPill
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.toneColor
import com.yulin.rider.core.model.ShiftCurrent

/**
 * 顶栏的在岗胶囊。
 *
 * 美团把在岗状态放在顶栏胶囊里，点开切换上下班；主界面因此不需要占一整张卡片来放开关。
 * 与 [ShiftDutyBar] 共用同一个 ViewModel 实例，两处状态始终一致。
 */
@Composable
fun ShiftStatusPill(
    modifier: Modifier = Modifier,
    viewModel: ShiftViewModel = viewModel(key = SHIFT_VM_KEY),
) {
    val state by viewModel.state.collectAsState()
    var confirmOffDuty by remember { mutableStateOf(false) }

    MtStatusPill(
        text = if (state.shift.onDuty) "上线中" else "下线中",
        onDuty = state.shift.onDuty,
        modifier = modifier,
        onClick = {
            if (state.shift.onDuty) confirmOffDuty = true else viewModel.openChecklist()
        },
    )

    if (confirmOffDuty) {
        AlertDialog(
            onDismissRequest = { confirmOffDuty = false },
            title = { Text("确认下线？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "下线后不再收到派单，正在配送的订单仍需送达。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOffDuty = false
                        viewModel.offDuty()
                    },
                ) {
                    Text("确认下线", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOffDuty = false }) {
                    Text("继续在线", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

/**
 * 底部操作条上的班次动作。
 *
 * 未上线时是黄色「上线」主按钮；已上线时换成白底「刷新列表」，
 * 上下班改由顶栏胶囊承担 —— 这是美团主界面底部的固定形态。
 * 上班前检查与提示弹窗挂在这里，保证首页始终有一个宿主。
 */
@Composable
fun ShiftDutyBar(
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    viewModel: ShiftViewModel = viewModel(key = SHIFT_VM_KEY),
) {
    val state by viewModel.state.collectAsState()

    if (state.shift.onDuty) {
        MtPrimaryButton(
            text = "刷新列表",
            action = MtAction.SECONDARY,
            modifier = modifier.fillMaxWidth(),
            icon = FreshIconType.REFRESH,
            onClick = onRefresh,
        )
    } else {
        MtPrimaryButton(
            text = if (state.submitting) "正在上线…" else "上线",
            action = MtAction.ACCEPT,
            modifier = modifier.fillMaxWidth(),
            enabled = !state.submitting && !state.loading,
            disabledReason = if (state.loading) "正在读取班次状态" else "正在切换在岗状态",
            onClick = viewModel::openChecklist,
        )
    }

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

internal const val SHIFT_VM_KEY = "rider-shift"

@Composable
internal fun ShiftStatsRow(
    taskCount: Int,
    deliveredCount: Int,
    onTimeCount: Int,
    mileageMeters: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm)) {
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
    com.yulin.rider.core.designsystem.MtMetric(
        label = label,
        value = value,
        modifier = modifier,
    )
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
                            tint = if (item.satisfied) {
                                StatusTone.SUCCESS.toneColor()
                            } else {
                                StatusTone.DANGER.toneColor()
                            },
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

@Preview(name = "在岗胶囊与班次动作", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun ShiftPiecesPreview() {
    RiderTheme {
        Column(
            modifier = Modifier.padding(FreshSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            MtStatusPill("上线中", onDuty = true, onClick = {})
            MtStatusPill("下线中", onDuty = false, onClick = {})
            MtPrimaryButton("上线", MtAction.ACCEPT, Modifier.fillMaxWidth()) {}
            ShiftStatsRow(
                taskCount = 8,
                deliveredCount = 5,
                onTimeCount = 5,
                mileageMeters = 12_600,
            )
        }
    }
}

@Suppress("UNUSED")
private val previewShift = ShiftCurrent(onDuty = true)
