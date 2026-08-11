package com.yulin.rider.feature.task.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.FreshBottomActionBar
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSecondaryButton
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SlideToConfirm
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.model.TaskDetail
import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.ui.components.TaskCardView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TaskDetailViewModel(app: Application, private val taskId: Long) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _task = MutableStateFlow<TaskCardUi?>(null)
    val task: StateFlow<TaskCardUi?> = _task
    private val _detail = MutableStateFlow<TaskDetail?>(null)
    /** 完整详情供 UI 展示商品、事件时间线与凭证。 */
    val detail: StateFlow<TaskDetail?> = _detail

    init {
        viewModelScope.launch { repository.observeTask(taskId).collect { _task.value = it } }
        viewModelScope.launch {
            when (val result = repository.refreshTask(taskId)) {
                is RiderResult.Success -> _detail.value = result.data
                else -> repository.refresh()
            }
        }
    }

    fun advance(step: NextStep) = viewModelScope.launch {
        when (step) {
            NextStep.ACCEPT -> repository.accept(taskId)
            NextStep.DEPART -> repository.depart(taskId)
            NextStep.ARRIVE -> repository.arrive(taskId)
            else -> Unit
        }
    }

    companion object {
        fun factory(taskId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return TaskDetailViewModel(app, taskId) as T
            }
        }
    }
}

@Composable
fun TaskDetailScreen(
    taskId: Long,
    modifier: Modifier = Modifier,
    onOpenPickup: (Long) -> Unit = {},
    onOpenDeliver: (Long) -> Unit = {},
    onOpenException: (Long) -> Unit = {},
    onOpenMap: () -> Unit = {},
) {
    val viewModel: TaskDetailViewModel = viewModel(
        key = "task-detail-$taskId",
        factory = TaskDetailViewModel.factory(taskId),
    )
    val task by viewModel.task.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val current = task

    if (current == null) {
        FreshStackScaffold(title = "任务详情", modifier = modifier) { insets ->
            FreshEmpty(
                title = "任务尚未同步",
                message = "请返回首页下拉刷新后再试",
                modifier = Modifier.padding(insets).fillMaxSize(),
            )
        }
        return
    }

    TaskDetailContent(
        task = current,
        detail = detail,
        modifier = modifier,
        onOpenMap = onOpenMap,
        onOpenException = { onOpenException(current.taskId) },
        onAdvance = { step ->
            when (step) {
                NextStep.PICKUP -> current.waveId?.let(onOpenPickup)
                NextStep.DELIVER -> onOpenDeliver(current.taskId)
                else -> viewModel.advance(step)
            }
        },
    )
}

@Composable
private fun TaskDetailContent(
    task: TaskCardUi,
    detail: TaskDetail? = null,
    modifier: Modifier = Modifier,
    onOpenMap: () -> Unit = {},
    onOpenException: () -> Unit = {},
    onAdvance: (NextStep) -> Unit = {},
) {
    FreshStackScaffold(
        title = "任务详情",
        subtitle = task.card.taskNo,
        modifier = modifier,
        bottomBar = {
            FreshBottomActionBar {
                when (val step = task.nextStep) {
                    NextStep.NONE -> FreshPanel(spineTone = StatusTone.SUCCESS) {
                        Text(
                            "本单已结束",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    NextStep.PICKUP -> SlideToConfirm(
                        text = "滑动进入取货核对",
                        tone = StatusTone.SUCCESS,
                    ) { onAdvance(step) }

                    NextStep.DELIVER -> SlideToConfirm(
                        text = "滑动进入送达确认",
                        tone = StatusTone.SUCCESS,
                    ) { onAdvance(step) }

                    else -> SlideToConfirm(
                        text = step.slideText,
                        tone = StatusTone.SUCCESS,
                    ) { onAdvance(step) }
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(FreshSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            TaskCardView(task = task)

            val items = detail?.items.orEmpty()
            if (items.isNotEmpty()) {
                FreshPanel(
                    title = "商品清单",
                    spineTone = if (task.card.coldChainLevel in setOf("FROZEN", "CHILLED")) {
                        StatusTone.COLD
                    } else {
                        StatusTone.NORMAL
                    },
                ) {
                    items.forEach { item ->
                        val quantity = if (item.quantity % 1.0 == 0.0) {
                            item.quantity.toLong().toString()
                        } else {
                            item.quantity.toString()
                        }
                        DetailRow(
                            label = item.productName,
                            value = buildString {
                                append(quantity).append(' ').append(item.unit)
                                item.weightKg?.let { append(" · ").append(it).append(" kg") }
                            },
                        )
                    }
                }
            } else {
                task.card.goodsSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    FreshPanel(
                        title = "商品清单",
                        spineTone = if (task.card.coldChainLevel in setOf("FROZEN", "CHILLED")) {
                            StatusTone.COLD
                        } else {
                            StatusTone.NORMAL
                        },
                    ) {
                        Text(summary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            FreshPanel(title = "订单信息") {
                DetailRow("任务号", task.card.taskNo)
                DetailRow("订单号", task.card.orderNo)
                task.waveNo?.let { DetailRow("波次", it) }
                task.card.slotLabel?.let { DetailRow("时段", it) }
                task.card.addressDetail?.let { DetailRow("详细地址", it) }
            }

            detail?.let { loaded ->
                FreshPanel(
                    title = "履约进度",
                    eyebrow = "${loaded.events.size} 条事件",
                    spineTone = StatusTone.SUCCESS,
                ) {
                    if (loaded.events.isEmpty()) {
                        Text(
                            "暂无履约事件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        loaded.events.forEach { event ->
                            DetailRow(
                                label = eventName(event.eventType),
                                value = event.createdAt,
                            )
                            event.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                FreshPanel(
                    title = "配送凭证",
                    eyebrow = "${loaded.evidences.size} 份",
                    spineTone = if (loaded.evidences.isEmpty()) StatusTone.NORMAL else StatusTone.INFO,
                ) {
                    if (loaded.evidences.isEmpty()) {
                        Text(
                            "尚未上传凭证",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        loaded.evidences.forEach { evidence ->
                            DetailRow(
                                label = evidenceName(evidence.evidenceType),
                                value = evidence.capturedAt ?: "已上传",
                            )
                            Text(
                                evidence.fileUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FreshSecondaryButton(
                    text = "导航",
                    icon = FreshIconType.ROUTE,
                    tone = StatusTone.INFO,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenMap,
                )
                FreshSecondaryButton(
                    text = "上报异常",
                    icon = FreshIconType.WARNING,
                    tone = StatusTone.DANGER,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenException,
                )
            }
        }
    }
}

private fun eventName(type: String): String = when (type) {
    "STATUS_CHANGE" -> "状态变更"
    "ASSIGN" -> "任务指派"
    "REASSIGN" -> "任务改派"
    "EXCEPTION" -> "异常处理"
    "ETA_UPDATE" -> "预计时间更新"
    "ORDER_BRIDGE" -> "订单状态同步"
    else -> type
}

private fun evidenceName(type: String): String = when (type) {
    "PICKUP" -> "取货凭证"
    "DELIVERED" -> "送达凭证"
    "EXCEPTION" -> "异常凭证"
    "WEIGHT_SCALE" -> "称重凭证"
    "RETURN" -> "退回凭证"
    "SIGNATURE" -> "签收凭证"
    else -> type
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = FreshSpacing.Xxs),
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "任务详情 · 配送中", showBackground = true)
@Composable
private fun TaskDetailPreview() {
    RiderTheme { TaskDetailContent(previewTask()) }
}

@Preview(name = "任务详情 · 已完成", showBackground = true)
@Composable
private fun TaskDetailDonePreview() {
    RiderTheme {
        TaskDetailContent(
            previewTask(status = com.yulin.rider.feature.task.data.TaskStatus.DELIVERED, cold = false)
        )
    }
}

@Preview(name = "任务详情 · 空态", showBackground = true)
@Composable
private fun TaskDetailEmptyPreview() {
    RiderTheme {
        FreshStackScaffold(title = "任务详情") { insets ->
            FreshEmpty("任务尚未同步", Modifier.padding(insets).fillMaxSize())
        }
    }
}
