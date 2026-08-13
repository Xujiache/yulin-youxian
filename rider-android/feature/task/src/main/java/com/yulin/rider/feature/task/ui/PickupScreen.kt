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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtBottomActionBar
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtMetric
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SlideToConfirm
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.data.WaveUi
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PickupViewModel(
    app: Application,
    private val waveId: Long,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _wave = MutableStateFlow<WaveUi?>(null)
    val wave: StateFlow<WaveUi?> = _wave

    // 逐单核对的勾选要扛住进程重建。骑手在门店一件件核对到第八单时被系统杀掉，
    // 回来发现全部清零、得从头再核一遍，是最招人烦的一种数据丢失。
    private val _checked = MutableStateFlow(
        savedStateHandle.get<LongArray>(KEY_CHECKED)?.toSet() ?: emptySet()
    )
    val checked: StateFlow<Set<Long>> = _checked

    init {
        viewModelScope.launch { repository.observeWave(waveId).collect { _wave.value = it } }
        viewModelScope.launch { repository.refresh() }
    }

    private fun updateChecked(next: Set<Long>) {
        _checked.value = next
        savedStateHandle[KEY_CHECKED] = next.toLongArray()
    }

    fun toggle(taskId: Long) {
        updateChecked(
            if (taskId in _checked.value) _checked.value - taskId else _checked.value + taskId
        )
    }

    fun checkAll(taskIds: List<Long>) {
        updateChecked(if (_checked.value.containsAll(taskIds)) emptySet() else taskIds.toSet())
    }

    fun confirmPickup(onDone: () -> Unit) = viewModelScope.launch {
        val packages = _wave.value?.stops?.sumOf { it.card.packageCount.coerceAtLeast(1) } ?: 0
        repository.pickupWave(waveId, _checked.value.toList(), packages)
        savedStateHandle.remove<LongArray>(KEY_CHECKED)
        onDone()
    }

    private companion object {
        const val KEY_CHECKED = "pickup_checked_task_ids"
    }
}

@Composable
fun PickupScreen(
    waveId: Long,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val viewModel: PickupViewModel = viewModel(
        key = "pickup-$waveId",
        factory = pickupFactory(waveId),
    )
    val wave by viewModel.wave.collectAsState()
    val checked by viewModel.checked.collectAsState()
    val current = wave
    if (current == null) {
        FreshStackScaffold(title = "取货核对", modifier = modifier) { insets ->
            FreshEmpty(
                "波次尚未同步",
                Modifier.padding(insets).fillMaxSize(),
                message = "返回首页刷新后再试",
                icon = FreshIconType.PICKUP,
            )
        }
        return
    }

    PickupContent(
        wave = current,
        checked = checked,
        modifier = modifier,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onCheckAll = { viewModel.checkAll(it) },
        onConfirm = { viewModel.confirmPickup(onDone) },
    )
}

@Composable
private fun PickupContent(
    wave: WaveUi,
    checked: Set<Long>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onCheckAll: (List<Long>) -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val stops = wave.stops.filter { it.displayStatus == TaskStatus.ACCEPTED }.ifEmpty { wave.stops }
    val ids = stops.map { it.taskId }
    val checkedCount = checked.intersect(ids.toSet()).size
    val allChecked = stops.isNotEmpty() && checkedCount == stops.size

    MtScaffold(
        title = "取货核对",
        subtitle = wave.wave.waveNo,
        modifier = modifier,
        onBack = onBack,
        bottomBar = {
            MtBottomActionBar(
                hint = if (allChecked) null else "还有 ${stops.size - checkedCount} 单未核对",
            ) {
                SlideToConfirm(
                    text = if (allChecked) "滑动确认已取货" else "请先逐单核对",
                    action = MtAction.PICKUP,
                    modifier = Modifier.weight(1f),
                    enabled = allChecked,
                    disabledReason = "需要核对本趟全部订单",
                    onConfirm = onConfirm,
                )
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FreshSpacing.Sm,
                end = FreshSpacing.Sm,
                top = FreshSpacing.Xs,
                bottom = FreshSpacing.Md,
            ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            item {
                PickupSummary(
                    stops = stops,
                    checkedCount = checkedCount,
                    onCheckAll = { onCheckAll(ids) },
                )
            }
            items(stops, key = { it.taskId }) { task ->
                PickupRow(
                    task = task,
                    checked = task.taskId in checked,
                    onToggle = { onToggle(task.taskId) },
                )
            }
        }
    }
}

@Composable
private fun PickupSummary(
    stops: List<TaskCardUi>,
    checkedCount: Int,
    onCheckAll: () -> Unit,
) {
    val totalItems = stops.sumOf { it.card.itemCount }
    val totalPackages = stops.sumOf { it.card.packageCount.coerceAtLeast(1) }
    val totalWeight = stops.sumOf { it.card.totalWeightKg ?: 0.0 }
    val cold = stops.any { it.card.coldChainLevel in setOf("FROZEN", "CHILLED") }
    val allChecked = checkedCount == stops.size

    MtCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            MtMetric(
                label = "本趟订单",
                value = "$checkedCount/${stops.size}",
                valueColor = if (allChecked) RiderColors.Deliver else RiderColors.Ink,
                modifier = Modifier.weight(1f),
            )
            MtMetric(label = "商品", value = "$totalItems", unit = "件", modifier = Modifier.weight(1f))
            MtMetric(
                label = "包裹",
                value = "$totalPackages",
                unit = RiderFormats.weight(totalWeight)?.let { "袋 · $it" } ?: "袋",
                modifier = Modifier.weight(1f),
            )
        }
        MtDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCheckAll)
                .padding(FreshSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            FreshIcon(
                type = if (allChecked) FreshIconType.CHECK else FreshIconType.ADD,
                contentDescription = null,
                tint = if (allChecked) RiderColors.Deliver else RiderColors.Ink,
                size = 18.dp,
            )
            Text(
                text = if (allChecked) "取消全选" else "核对全部",
                style = MaterialTheme.typography.titleSmall,
                color = RiderColors.Ink,
                modifier = Modifier.weight(1f),
            )
            if (cold) MtTag("含冷链，先装保温箱", tone = StatusTone.COLD)
        }
    }
}

@Composable
private fun PickupRow(task: TaskCardUi, checked: Boolean, onToggle: () -> Unit) {
    val cold = task.card.coldChainLevel in setOf("FROZEN", "CHILLED")
    MtCard(
        modifier = Modifier.semantics {
            role = Role.Checkbox
            contentDescription = "${task.card.areaLabel ?: task.card.addressDetail ?: "订单"}，" +
                if (checked) "已核对" else "未核对"
        },
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = RiderColors.Deliver,
                    checkmarkColor = Color.White,
                ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
                ) {
                    Text(
                        text = "第 ${task.card.seqNo ?: "-"} 单",
                        style = MaterialTheme.typography.labelSmall.tabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cold) {
                        MtTag(
                            if (task.card.coldChainLevel == "FROZEN") "冷冻" else "冷藏",
                            tone = StatusTone.COLD,
                        )
                    }
                }
                Text(
                    text = task.card.areaLabel ?: task.card.addressDetail.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = RiderColors.Ink,
                )
                Text(
                    text = "${task.card.itemCount} 件" +
                        (RiderFormats.weight(task.card.totalWeightKg)?.let { " · $it" } ?: "") +
                        (task.card.goodsSummary?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pickupFactory(waveId: Long) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        return PickupViewModel(app, waveId, extras.createSavedStateHandle()) as T
    }
}

@Preview(name = "取货 · 待核对", showBackground = true)
@Composable
private fun PickupPendingPreview() {
    RiderTheme { PickupContent(previewWave(), checked = setOf(1001L)) }
}

@Preview(name = "取货 · 已核对", showBackground = true)
@Composable
private fun PickupCheckedPreview() {
    val wave = previewWave()
    RiderTheme { PickupContent(wave, checked = wave.stops.map { it.taskId }.toSet()) }
}
