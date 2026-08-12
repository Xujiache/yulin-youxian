package com.yulin.rider.feature.task.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtBottomActionBar
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtCardHeader
import com.yulin.rider.core.designsystem.MtContactStrip
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtGhostAction
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtLeg
import com.yulin.rider.core.designsystem.MtLegBlock
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.core.model.TaskDetail
import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
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
    onBack: () -> Unit = {},
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
        onBack = onBack,
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
    onBack: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    onOpenException: () -> Unit = {},
    onAdvance: (NextStep) -> Unit = {},
) {
    val context = LocalContext.current
    val card = task.card
    val phone = card.callNumber
    val activeLeg = if (task.displayStatus in DELIVER_LEG_STATUSES) MtLeg.DELIVER else MtLeg.PICKUP

    MtScaffold(
        title = "订单详情",
        subtitle = card.taskNo,
        modifier = modifier,
        onBack = onBack,
        bottomBar = {
            MtBottomActionBar(
                secondaryActions = {
                    if (!phone.isNullOrBlank()) {
                        MtGhostAction("联系", FreshIconType.PHONE) {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")),
                            )
                        }
                    }
                    MtGhostAction("遇到问题", FreshIconType.PROBLEM, onClick = onOpenException)
                },
            ) {
                val step = task.nextStep
                if (step == NextStep.NONE) {
                    MtPrimaryButton(
                        text = "本单已结束",
                        action = MtAction.SECONDARY,
                        modifier = Modifier.weight(1f),
                        enabled = false,
                        disabledReason = "本单已结束",
                        onClick = {},
                    )
                } else {
                    MtPrimaryButton(
                        text = step.slideText,
                        action = step.toAction(),
                        modifier = Modifier.weight(1f),
                        onClick = { onAdvance(step) },
                    )
                }
            }
        },
    ) {
        Column(
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
            // 时限 + 取送两段地址 + 联系人，一张卡里说清「什么时候、去哪、找谁」
            MtCard {
                MtCardHeader(
                    scheduled = card.slotLabel != null,
                    timeText = detailTimeText(task),
                )
                MtLegBlock(
                    pickupTitle = "门店取货",
                    pickupSubtitle = card.goodsSummary?.takeIf { it.isNotBlank() },
                    deliverTitle = card.addressDetail ?: "地址待补充",
                    deliverSubtitle = listOfNotNull(
                        card.areaLabel,
                        card.buildingLabel,
                        card.unitNo?.let { "$it 单元" },
                        card.floorNo?.let { "$it 楼" },
                        card.roomNo?.let { "$it 室" },
                    ).joinToString(" ").ifBlank { null },
                    activeLeg = activeLeg,
                    modifier = Modifier.padding(horizontal = FreshSpacing.Sm),
                    onNavigateDeliver = onOpenMap,
                )
                if (!card.receiverName.isNullOrBlank() || !phone.isNullOrBlank()) {
                    Box(Modifier.padding(FreshSpacing.Sm)) {
                        MtContactStrip(
                            name = card.receiverName ?: "顾客",
                            phone = card.receiverPhoneMasked ?: phone.orEmpty(),
                            onCall = if (phone.isNullOrBlank()) {
                                null
                            } else {
                                {
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")),
                                    )
                                }
                            },
                        )
                    }
                }
                card.customerRemark?.takeIf { it.isNotBlank() }?.let { remark ->
                    MtInfoBar(text = "顾客备注：$remark", tone = StatusTone.WARNING)
                }
            }

            GoodsCard(task = task, detail = detail)

            MtCard {
                MtSectionTitle("订单信息")
                MtDivider()
                DetailRow("任务号", card.taskNo)
                DetailRow("订单号", card.orderNo)
                task.waveNo?.let { DetailRow("波次", it) }
                card.slotLabel?.let { DetailRow("时段", it) }
            }

            detail?.let { loaded ->
                MtCard {
                    MtSectionTitle("履约进度", trailing = {
                        Text(
                            "${loaded.events.size} 条",
                            style = MaterialTheme.typography.bodySmall.tabularFigures(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    })
                    MtDivider()
                    if (loaded.events.isEmpty()) {
                        EmptyLine("暂无履约事件")
                    } else {
                        loaded.events.forEach { event ->
                            DetailRow(eventName(event.eventType), event.createdAt)
                            event.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = FreshSpacing.Sm,
                                        end = FreshSpacing.Sm,
                                        bottom = FreshSpacing.Xxs,
                                    ),
                                )
                            }
                        }
                    }
                }

                MtCard {
                    MtSectionTitle("配送凭证", trailing = {
                        Text(
                            "${loaded.evidences.size} 份",
                            style = MaterialTheme.typography.bodySmall.tabularFigures(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    })
                    MtDivider()
                    if (loaded.evidences.isEmpty()) {
                        EmptyLine("尚未上传凭证")
                    } else {
                        loaded.evidences.forEach { evidence ->
                            DetailRow(
                                evidenceName(evidence.evidenceType),
                                evidence.capturedAt ?: "已上传",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoodsCard(task: TaskCardUi, detail: TaskDetail?) {
    val items = detail?.items.orEmpty()
    val summary = task.card.goodsSummary?.takeIf { it.isNotBlank() }
    if (items.isEmpty() && summary == null) return

    MtCard {
        MtSectionTitle("商品清单", trailing = {
            task.card.coldChainText?.let { MtTag(it, tone = StatusTone.COLD) }
        })
        MtDivider()
        if (items.isNotEmpty()) {
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
        } else {
            Text(
                text = summary.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(FreshSpacing.Sm),
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(FreshSpacing.Sm),
    )
}

private val DELIVER_LEG_STATUSES = setOf(
    com.yulin.rider.feature.task.data.TaskStatus.DELIVERING,
    com.yulin.rider.feature.task.data.TaskStatus.ARRIVED,
    com.yulin.rider.feature.task.data.TaskStatus.DELIVERED,
)

private fun NextStep.toAction(): MtAction = when (this) {
    NextStep.PICKUP -> MtAction.PICKUP
    NextStep.DELIVER -> MtAction.DELIVER
    else -> MtAction.ACCEPT
}

private fun detailTimeText(task: TaskCardUi): String {
    val card = task.card
    val remaining = card.remainingSeconds
    return when {
        remaining != null && remaining > 0 -> "还剩${(remaining / 60).coerceAtLeast(1)}分钟送达"
        remaining != null -> "已超时，尽快送达"
        else -> card.slotLabel ?: "无时限"
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.tabularFigures(),
            modifier = Modifier.weight(1f),
        )
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
