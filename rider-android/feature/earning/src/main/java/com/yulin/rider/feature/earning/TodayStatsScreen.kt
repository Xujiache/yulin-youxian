package com.yulin.rider.feature.earning

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.ErrorRetry
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshError
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.LoadingBox
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.SectionCard
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.model.ShiftCurrent
import com.yulin.rider.core.network.RiderApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class TodayStatsUiState(
    val shift: ShiftCurrent = ShiftCurrent(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** 数据全部取自 shift/current:今日统计的四个数字都在 ShiftCurrent 里,不碰任何金额接口。 */
class TodayStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TodayStatsUiState())
    val state: StateFlow<TodayStatsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            _state.value = when (val result = apis.caller.result { apis.shift.getCurrentShift() }) {
                is RiderResult.Success -> TodayStatsUiState(shift = result.data, loading = false)
                is RiderResult.Failure -> TodayStatsUiState(loading = false, error = result.message)
                RiderResult.Loading -> _state.value
            }
        }
    }
}

/**
 * 今日统计(路由 earning)。
 *
 * 客户是单店家庭自营,配送不结算给自己,所以这里**不显示任何金额**,
 * 也不做收入明细、结算单、服务分、申诉。只回答一句话:今天干了多少活。
 */
@Composable
fun TodayStatsScreen(
    modifier: Modifier = Modifier,
    viewModel: TodayStatsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    TodayStatsContent(state = state, modifier = modifier, onRetry = viewModel::load)
}

@Composable
private fun TodayStatsContent(
    state: TodayStatsUiState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    FreshStackScaffold(
        title = "今日统计",
        subtitle = "当前班次履约概览",
        modifier = modifier,
    ) { insets ->
        when {
            state.loading -> FreshLoading(
                modifier = Modifier.padding(insets).fillMaxSize(),
                label = "正在汇总今日数据",
            )
            state.error != null -> FreshError(
                message = state.error,
                modifier = Modifier.padding(insets).fillMaxSize(),
                onRetry = onRetry,
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(insets).padding(FreshSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BigStat(
                        label = "完成单量",
                        value = "${state.shift.deliveredCount}",
                        unit = "单",
                        icon = FreshIconType.DELIVERY,
                        tone = StatusTone.SUCCESS,
                        modifier = Modifier.weight(1f),
                    )
                    BigStat(
                        label = "配送里程",
                        value = formatKmValue(state.shift.mileageMeters.toLong()),
                        unit = "km",
                        icon = FreshIconType.ROUTE,
                        tone = StatusTone.INFO,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BigStat(
                        label = "在线时长",
                        value = formatHours(state.shift.onlineSeconds.toLong()),
                        unit = "小时",
                        icon = FreshIconType.CLOCK,
                        tone = StatusTone.NORMAL,
                        modifier = Modifier.weight(1f),
                    )
                    BigStat(
                        label = "准时送达",
                        value = "${state.shift.onTimeCount}",
                        unit = "单",
                        icon = FreshIconType.CHECK,
                        tone = StatusTone.SUCCESS,
                        modifier = Modifier.weight(1f),
                    )
                }

                FreshBanner(
                    text = "统计口径为当前班次。派单 ${state.shift.taskCount} 单，已完成 ${state.shift.deliveredCount} 单。",
                    tone = StatusTone.INFO,
                    icon = FreshIconType.STATS,
                )
            }
        }
    }
}

@Composable
private fun BigStat(
    label: String,
    value: String,
    unit: String,
    icon: FreshIconType,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    FreshPanel(
        modifier = modifier,
        eyebrow = label,
        spineTone = tone,
    ) {
        com.yulin.rider.core.designsystem.FreshIcon(
            icon,
            contentDescription = null,
            tint = when (tone) {
                StatusTone.INFO -> com.yulin.rider.core.designsystem.RiderColors.Secondary
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = MaterialTheme.typography.displaySmall)
            Text(
                text = " $unit",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = FreshSpacing.Xs),
            )
        }
    }
}

private fun formatKmValue(meters: Long): String =
    String.format(Locale.CHINA, "%.1f", meters / 1000.0)

private fun formatHours(seconds: Long): String =
    String.format(Locale.CHINA, "%.1f", seconds / 3600.0)

@Preview(name = "统计 · 有数据", showBackground = true)
@Composable
private fun TodayStatsPreview() {
    RiderTheme {
        TodayStatsContent(
            TodayStatsUiState(
                shift = ShiftCurrent(
                    taskCount = 10,
                    deliveredCount = 8,
                    onTimeCount = 8,
                    mileageMeters = 26_400,
                    onlineSeconds = 18_600,
                ),
                loading = false,
            )
        )
    }
}

@Preview(name = "统计 · 加载", showBackground = true)
@Composable
private fun TodayStatsLoadingPreview() {
    RiderTheme { TodayStatsContent(TodayStatsUiState()) }
}

@Preview(name = "统计 · 错误", showBackground = true)
@Composable
private fun TodayStatsErrorPreview() {
    RiderTheme {
        TodayStatsContent(TodayStatsUiState(loading = false, error = "网络不可用"))
    }
}
