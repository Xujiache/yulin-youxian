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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.yulin.rider.core.designsystem.FreshBottomActionBar
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SlideToConfirm
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.data.WaveUi
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PickupViewModel(app: Application, private val waveId: Long) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _wave = MutableStateFlow<WaveUi?>(null)
    val wave: StateFlow<WaveUi?> = _wave
    private val _checked = MutableStateFlow<Set<Long>>(emptySet())
    val checked: StateFlow<Set<Long>> = _checked

    init {
        viewModelScope.launch { repository.observeWave(waveId).collect { _wave.value = it } }
        viewModelScope.launch { repository.refresh() }
    }

    fun toggle(taskId: Long) {
        _checked.value = if (taskId in _checked.value) _checked.value - taskId else _checked.value + taskId
    }

    fun checkAll(taskIds: List<Long>) {
        _checked.value = if (_checked.value.containsAll(taskIds)) emptySet() else taskIds.toSet()
    }

    fun confirmPickup(onDone: () -> Unit) = viewModelScope.launch {
        val packages = _wave.value?.stops?.sumOf { it.card.packageCount.coerceAtLeast(1) } ?: 0
        repository.pickupWave(waveId, _checked.value.toList(), packages)
        onDone()
    }
}

@Composable
fun PickupScreen(
    waveId: Long,
    modifier: Modifier = Modifier,
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
    onToggle: (Long) -> Unit = {},
    onCheckAll: (List<Long>) -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val stops = wave.stops.filter { it.displayStatus == TaskStatus.ACCEPTED }.ifEmpty { wave.stops }
    val ids = stops.map { it.taskId }
    val allChecked = stops.isNotEmpty() && checked.containsAll(ids)

    FreshStackScaffold(
        title = "取货核对",
        subtitle = wave.wave.waveNo,
        modifier = modifier,
        bottomBar = {
            FreshBottomActionBar(
                reason = if (allChecked) null else "还有 ${stops.size - checked.intersect(ids.toSet()).size} 单未核对",
                reasonTone = StatusTone.WARNING,
            ) {
                SlideToConfirm(
                    text = if (allChecked) "滑动确认已全部取货" else "请先逐单勾选",
                    enabled = allChecked,
                    disabledReason = "需要核对本趟全部订单",
                    tone = StatusTone.SUCCESS,
                    onConfirm = onConfirm,
                )
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(FreshSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            item {
                PickupSummary(
                    stops = stops,
                    checkedCount = checked.intersect(ids.toSet()).size,
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

    FreshPanel(
        title = "本趟共 ${stops.size} 单",
        eyebrow = "门店取货",
        spineTone = if (cold) StatusTone.COLD else StatusTone.SUCCESS,
        action = {
            FreshStatusBadge(
                text = "已核对 $checkedCount/${stops.size}",
                tone = if (checkedCount == stops.size) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        },
    ) {
        Text(
            text = "$totalItems 件 · $totalPackages 袋" +
                (RiderFormats.weight(totalWeight)?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.titleLarge,
        )
        if (cold) {
            FreshStatusBadge(
                text = "含冷链商品，先装保温箱",
                tone = StatusTone.COLD,
                icon = FreshIconType.COLD,
            )
        }
        TextButton(onClick = onCheckAll, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (checkedCount == stops.size) "取消全选" else "核对全部",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PickupRow(task: TaskCardUi, checked: Boolean, onToggle: () -> Unit) {
    val cold = task.card.coldChainLevel in setOf("FROZEN", "CHILLED")
    FreshPanel(
        modifier = Modifier
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics {
                role = Role.Checkbox
                contentDescription = "${task.card.areaLabel ?: task.card.addressDetail ?: "订单"}，" +
                    if (checked) "已核对" else "未核对"
            },
        eyebrow = "第 ${task.card.seqNo ?: "-"} 单",
        spineTone = when {
            checked -> StatusTone.SUCCESS
            cold -> StatusTone.COLD
            else -> StatusTone.NORMAL
        },
        action = {
            if (cold) {
                FreshStatusBadge(
                    text = if (task.card.coldChainLevel == "FROZEN") "冷冻" else "冷藏",
                    tone = StatusTone.COLD,
                    icon = FreshIconType.COLD,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.card.areaLabel ?: task.card.addressDetail.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${task.card.itemCount} 件" +
                        (RiderFormats.weight(task.card.totalWeightKg)?.let { " · $it" } ?: "") +
                        (task.card.goodsSummary?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FreshIcon(
                type = if (checked) FreshIconType.CHECK else FreshIconType.PACKAGE,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pickupFactory(waveId: Long) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        return PickupViewModel(app, waveId) as T
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
