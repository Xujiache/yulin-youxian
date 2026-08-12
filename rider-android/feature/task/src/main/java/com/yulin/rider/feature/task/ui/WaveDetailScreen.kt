package com.yulin.rider.feature.task.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtMetric
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.data.WaveUi
import com.yulin.rider.feature.task.ui.components.tone
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WaveDetailViewModel(app: Application, waveId: Long) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _wave = MutableStateFlow<WaveUi?>(null)
    val wave: StateFlow<WaveUi?> = _wave

    init {
        viewModelScope.launch { repository.observeWave(waveId).collect { _wave.value = it } }
        viewModelScope.launch { repository.refresh() }
    }
}

@Composable
fun WaveDetailScreen(
    waveId: Long,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
) {
    val viewModel: WaveDetailViewModel = viewModel(
        key = "wave-$waveId",
        factory = waveFactory(waveId),
    )
    val wave by viewModel.wave.collectAsState()
    val current = wave
    if (current == null) {
        FreshStackScaffold(title = "波次路线", modifier = modifier) { insets ->
            FreshEmpty(
                title = "波次尚未同步",
                message = "返回首页下拉刷新后再试",
                modifier = Modifier.padding(insets).fillMaxSize(),
            )
        }
        return
    }
    WaveDetailContent(current, modifier, onBack, onOpenTask)
}

@Composable
private fun WaveDetailContent(
    wave: WaveUi,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
) {
    val completed = wave.wave.completedCount >= wave.wave.taskCount
    MtScaffold(
        title = "本趟路线",
        subtitle = wave.wave.waveNo,
        modifier = modifier,
        onBack = onBack,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FreshSpacing.Sm,
                end = FreshSpacing.Sm,
                top = FreshSpacing.Xs,
                bottom = FreshSpacing.Xl,
            ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            item {
                MtCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                    ) {
                        MtMetric(
                            label = "本趟站点",
                            value = "${wave.wave.taskCount}",
                            unit = "站",
                            modifier = Modifier.weight(1f),
                        )
                        MtMetric(
                            label = "已完成",
                            value = "${wave.wave.completedCount}",
                            unit = "站",
                            valueColor = if (completed) RiderColors.Deliver else RiderColors.Ink,
                            modifier = Modifier.weight(1f),
                        )
                        MtMetric(
                            label = "全程",
                            value = RiderFormats.distance(wave.wave.planDistanceMeters),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    RiderFormats.hourMinute(wave.wave.planReturnAt)?.let { back ->
                        MtDivider()
                        Text(
                            text = "预计 $back 回店" +
                                (wave.wave.planDurationSeconds
                                    ?.let { " · 在途 ${RiderFormats.duration(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall.tabularFigures(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(FreshSpacing.Sm),
                        )
                    }
                }
            }
            items(wave.stops, key = { it.taskId }) { stop ->
                StopRow(
                    stop = stop,
                    total = wave.wave.taskCount,
                    onClick = { onOpenTask(stop.taskId) },
                )
            }
        }
    }
}

/** 站点行沿用列表的轨道语言：序号圆点 + 地址主信息，完成的整行转灰。 */
@Composable
private fun StopRow(stop: TaskCardUi, total: Int, onClick: () -> Unit) {
    val done = stop.displayStatus == TaskStatus.DELIVERED
    val cold = stop.card.coldChainLevel in setOf("FROZEN", "CHILLED")
    MtCard(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription =
                "第 ${stop.card.seqNo ?: "-"} 站，共 $total 站，${stop.card.addressDetail ?: "地址待补充"}"
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (done) RiderColors.DeliverContainer else RiderColors.Deliver,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    FreshIcon(
                        FreshIconType.CHECK,
                        contentDescription = null,
                        tint = RiderColors.Deliver,
                        size = 14.dp,
                    )
                } else {
                    Text(
                        text = "${stop.card.seqNo ?: 0}",
                        style = MaterialTheme.typography.labelSmall.tabularFigures(),
                        color = Color.White,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stop.card.addressDetail ?: "地址待补充",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else RiderColors.Ink,
                )
                Text(
                    text = "${stop.card.itemCount} 件 · " +
                        (stop.legDistanceMeters?.let { "本段 ${RiderFormats.distance(it)}" }
                            ?: "距我 ${RiderFormats.distance(stop.card.distanceFromRiderMeters)}"),
                    style = MaterialTheme.typography.bodySmall.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cold) {
                    Box(Modifier.padding(top = FreshSpacing.Xxs)) {
                        MtTag(stop.card.coldChainText ?: "冷链", tone = StatusTone.COLD)
                    }
                }
            }
            MtTag(stop.displayStatusText, tone = stop.displayStatus.tone())
        }
    }
}

private fun waveFactory(waveId: Long) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        return WaveDetailViewModel(app, waveId) as T
    }
}

@Preview(name = "波次 · 路线", showBackground = true)
@Composable
private fun WaveDetailPreview() {
    RiderTheme { WaveDetailContent(previewWave()) }
}

@Preview(name = "波次 · 空态", showBackground = true)
@Composable
private fun WaveEmptyPreview() {
    RiderTheme {
        FreshStackScaffold(title = "波次路线") { insets ->
            FreshEmpty("波次尚未同步", Modifier.padding(insets).fillMaxSize())
        }
    }
}
