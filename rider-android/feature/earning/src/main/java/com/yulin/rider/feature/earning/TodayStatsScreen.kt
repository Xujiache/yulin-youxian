package com.yulin.rider.feature.earning

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.FreshError
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtMetric
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.tabularFigures
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
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    TodayStatsContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onRetry = viewModel::load,
    )
}

@Composable
private fun TodayStatsContent(
    state: TodayStatsUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    MtScaffold(
        title = "我的账户",
        subtitle = "当前班次履约概览",
        modifier = modifier,
        onBack = onBack,
    ) {
        when {
            state.loading -> FreshLoading(
                modifier = Modifier.fillMaxSize(),
                label = "正在汇总今日数据",
            )

            state.error != null -> FreshError(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = FreshSpacing.Sm,
                        end = FreshSpacing.Sm,
                        top = FreshSpacing.Xs,
                        bottom = FreshSpacing.Md,
                    ),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                HeroCard(state.shift)

                MtCard {
                    MtSectionTitle("本班明细")
                    MtDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                    ) {
                        MtMetric(
                            label = "配送里程",
                            value = formatKmValue(state.shift.mileageMeters.toLong()),
                            unit = "km",
                            modifier = Modifier.weight(1f),
                        )
                        MtMetric(
                            label = "在线时长",
                            value = formatHours(state.shift.onlineSeconds.toLong()),
                            unit = "小时",
                            modifier = Modifier.weight(1f),
                        )
                        MtMetric(
                            label = "准时送达",
                            value = "${state.shift.onTimeCount}",
                            unit = "单",
                            valueColor = RiderColors.Deliver,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                MtInfoBar(
                    text = "自营门店配送不做结算，本页只统计工作量，不显示任何金额。",
                    icon = FreshIconType.STATS,
                )
            }
        }
    }
}

/**
 * 账户主卡。
 *
 * 美团这个位置是黄色渐变的余额卡；自营门店不给自己结算，没有余额可显示，
 * 于是把「今日完成单量」放在同一位置，主卡的视觉分量留着，钱的字段一个不造。
 */
@Composable
private fun HeroCard(shift: ShiftCurrent) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FreshRadius.Hero))
            .background(
                Brush.horizontalGradient(
                    listOf(RiderColors.Primary, Color(0xFFFFD84D)),
                ),
            )
            .padding(FreshSpacing.Md),
    ) {
        Column {
            Text(
                text = "今日完成",
                style = MaterialTheme.typography.bodyMedium,
                color = RiderColors.OnPrimary.copy(alpha = .75f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${shift.deliveredCount}",
                    style = MaterialTheme.typography.displayLarge.tabularFigures(),
                    color = RiderColors.OnPrimary,
                )
                Text(
                    text = " 单",
                    style = MaterialTheme.typography.titleMedium,
                    color = RiderColors.OnPrimary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = "本班派单 ${shift.taskCount} 单",
                style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                color = RiderColors.OnPrimary.copy(alpha = .75f),
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
