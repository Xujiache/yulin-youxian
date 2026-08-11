package com.yulin.rider.feature.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshPageHeader
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshShell
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.LoadingBox
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SectionCard
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.model.ShiftCurrent

/**
 * 班次页:只展示统计,不做疲劳管控。
 * 客户明确关闭 4h/8h/12h 三道疲劳闸门,这里连提示条都不留。
 */
@Composable
fun ShiftScreen(
    modifier: Modifier = Modifier,
    viewModel: ShiftViewModel = viewModel(key = "rider-shift"),
) {
    val state by viewModel.state.collectAsState()

    FreshShell(
        modifier = modifier,
        topBar = { FreshPageHeader("班次中心", subtitle = "在岗、里程与准时情况") },
    ) { insets ->
        if (state.loading) {
            FreshLoading(modifier = Modifier.padding(insets).fillMaxSize(), label = "正在读取班次")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState()),
            ) {
                ShiftSwitchBar(viewModel = viewModel)
                ShiftStatsContent(state = state)
            }
        }
    }
}

@Composable
private fun ShiftStatsContent(state: ShiftUiState) {
    Column(
        modifier = Modifier.padding(FreshSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshPanel(
            title = "本班统计",
            eyebrow = if (state.shift.onDuty) "实时更新" else "最近班次",
            spineTone = if (state.shift.onDuty) StatusTone.SUCCESS else StatusTone.NORMAL,
        ) {
            StatLine("在线时长", formatDuration(state.liveOnlineSeconds))
            StatLine("今日单量", "${state.shift.taskCount} 单")
            StatLine("已完成", "${state.shift.deliveredCount} 单")
            StatLine("准时送达", "${state.shift.onTimeCount} 单")
            StatLine("配送里程", formatKm(state.shift.mileageMeters.toLong()))
        }

        FreshBanner(
            text = "上班后系统才会派单并开始定位。收工请点下班，避免持续耗电。",
            tone = StatusTone.INFO,
            icon = FreshIconType.BATTERY,
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = FreshSpacing.Xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(name = "班次 · 在岗", showBackground = true)
@Composable
private fun ShiftScreenPreview() {
    RiderTheme {
        ShiftStatsContent(
            ShiftUiState(
                shift = ShiftCurrent(
                    onDuty = true,
                    taskCount = 10,
                    deliveredCount = 7,
                    onTimeCount = 7,
                    mileageMeters = 18_400,
                ),
                liveOnlineSeconds = 14_820,
                loading = false,
            )
        )
    }
}

@Preview(name = "班次 · 加载", showBackground = true)
@Composable
private fun ShiftLoadingPreview() {
    RiderTheme { FreshLoading(label = "正在读取班次") }
}
