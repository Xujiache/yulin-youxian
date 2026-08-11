package com.yulin.rider.feature.task.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
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
    WaveDetailContent(current, modifier, onOpenTask)
}

@Composable
private fun WaveDetailContent(
    wave: WaveUi,
    modifier: Modifier = Modifier,
    onOpenTask: (Long) -> Unit = {},
) {
    FreshStackScaffold(
        title = "波次路线",
        subtitle = wave.wave.waveNo,
        modifier = modifier,
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(FreshSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            item {
                val completed = wave.wave.completedCount >= wave.wave.taskCount
                FreshPanel(
                    title = "共 ${wave.wave.taskCount} 站",
                    eyebrow = "本趟进度",
                    spineTone = if (completed) StatusTone.SUCCESS else StatusTone.INFO,
                    action = {
                        FreshStatusBadge(
                            text = "完成 ${wave.wave.completedCount}/${wave.wave.taskCount}",
                            tone = if (completed) StatusTone.SUCCESS else StatusTone.WARNING,
                        )
                    },
                ) {
                    Text(
                        text = buildList {
                            add("全程 ${RiderFormats.distance(wave.wave.planDistanceMeters)}")
                            wave.wave.planDurationSeconds?.let { add(RiderFormats.duration(it)) }
                            RiderFormats.hourMinute(wave.wave.planReturnAt)?.let { add("预计 $it 回店") }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

@Composable
private fun StopRow(stop: TaskCardUi, total: Int, onClick: () -> Unit) {
    val done = stop.displayStatus == TaskStatus.DELIVERED
    val cold = stop.card.coldChainLevel in setOf("FROZEN", "CHILLED")
    FreshPanel(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "第 ${stop.card.seqNo ?: "-"} 站，${stop.card.addressDetail ?: "地址待补充"}"
            },
        eyebrow = "第 ${stop.card.seqNo ?: "-"}/$total 站 · ${stop.card.areaLabel.orEmpty()}",
        spineTone = when {
            done -> StatusTone.SUCCESS
            stop.card.overtimeRisk == "OVERTIME" -> StatusTone.DANGER
            stop.card.overtimeRisk in setOf("HIGH", "MEDIUM") -> StatusTone.WARNING
            cold -> StatusTone.COLD
            else -> StatusTone.NORMAL
        },
        action = {
            FreshStatusBadge(
                text = stop.displayStatusText,
                tone = stop.displayStatus.tone(),
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            FreshIcon(
                type = if (done) FreshIconType.CHECK else FreshIconType.LOCATION,
                contentDescription = null,
                tint = if (done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stop.card.addressDetail ?: "地址待补充",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${stop.card.itemCount} 件 · " +
                        (stop.legDistanceMeters?.let { "本段 ${RiderFormats.distance(it)}" }
                            ?: "距我 ${RiderFormats.distance(stop.card.distanceFromRiderMeters)}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (cold) {
                FreshIcon(
                    FreshIconType.COLD,
                    contentDescription = "冷链",
                    tint = com.yulin.rider.core.designsystem.RiderColors.Ice,
                )
            }
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
