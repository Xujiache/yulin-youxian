package com.yulin.rider.feature.task.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtCardHeader
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtEmptyState
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtLeg
import com.yulin.rider.core.designsystem.MtLegBlock
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.riderMessage
import com.yulin.rider.feature.task.data.runTransition
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DispatchViewModel(app: Application, private val taskId: Long) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _task = MutableStateFlow<TaskCardUi?>(null)
    val task: StateFlow<TaskCardUi?> = _task
    private val _accepting = MutableStateFlow(false)
    val accepting: StateFlow<Boolean> = _accepting

    init {
        viewModelScope.launch { repository.observeTask(taskId).collect { _task.value = it } }
        viewModelScope.launch { repository.refreshTask(taskId) }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun accept(onDone: () -> Unit) = viewModelScope.launch {
        if (_accepting.value) return@launch
        _accepting.value = true
        val result = runTransition { repository.accept(taskId) }
        _accepting.value = false
        _error.value = result.riderMessage
        if (result.queued) onDone()
    }

    companion object {
        fun factory(taskId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return DispatchViewModel(app, taskId) as T
            }
        }
    }
}

/**
 * 派单页。
 *
 * 派单通知点开后落这里，而不是直接落订单详情：骑手在路上只需要看清「去哪、多远、什么时候要到」，
 * 然后一键接单。整页是美团派单弹窗的结构 —— 大标题、任务卡、底部双按钮。
 *
 * 与美团不同的是右边不是「拒绝」而是「稍后处理」：自营门店的单不存在拒接，
 * 只是允许先返回列表，单子仍在待接单里。
 */
@Composable
fun DispatchScreen(
    taskId: Long,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onAccepted: (Long) -> Unit = {},
) {
    val viewModel: DispatchViewModel = viewModel(
        key = "dispatch-$taskId",
        factory = DispatchViewModel.factory(taskId),
    )
    val task by viewModel.task.collectAsState()
    val accepting by viewModel.accepting.collectAsState()
    val error by viewModel.error.collectAsState()

    DispatchContent(
        task = task,
        accepting = accepting,
        error = error,
        modifier = modifier,
        onDismiss = onDismiss,
        onAccept = { viewModel.accept { onAccepted(taskId) } },
    )
}

@Composable
private fun DispatchContent(
    task: TaskCardUi?,
    accepting: Boolean,
    error: String? = null,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onAccept: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = FreshSpacing.Sm),
    ) {
        // 派单页整屏都是灰底，不像其他页那样有白色顶栏，安全区不会露色差
        Text(
            text = "你有 1 个派单",
            style = MaterialTheme.typography.displaySmall,
            color = RiderColors.Ink,
            modifier = Modifier.padding(
                start = FreshSpacing.Xxs,
                top = FreshSpacing.Lg,
                bottom = FreshSpacing.Sm,
            ),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (task == null) {
                MtEmptyState(
                    title = "任务尚未同步",
                    message = "返回首页下拉刷新后再试",
                    icon = FreshIconType.TASK,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DispatchCard(task)
                }
            }
        }

        error?.let {
            MtInfoBar(text = it, tone = StatusTone.DANGER, icon = FreshIconType.ERROR)
        }

        error?.let {
            MtInfoBar(text = it, tone = StatusTone.DANGER, icon = FreshIconType.ERROR)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FreshSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            MtPrimaryButton(
                text = "稍后处理",
                action = MtAction.SECONDARY,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            MtPrimaryButton(
                text = if (accepting) "接单中…" else "接受",
                action = MtAction.ACCEPT,
                modifier = Modifier.weight(1f),
                enabled = task != null && !accepting,
                disabledReason = if (task == null) "任务尚未同步" else "正在接单",
                onClick = onAccept,
            )
        }
    }
}

@Composable
private fun DispatchCard(task: TaskCardUi) {
    val card = task.card
    // 剩余时间每秒走一格，派单页上骑手盯着的就是这个数
    var remaining by remember(card.taskId) { mutableLongStateOf(card.remainingSeconds ?: -1L) }
    LaunchedEffect(card.taskId) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }

    MtCard {
        MtCardHeader(
            scheduled = card.slotLabel != null,
            timeText = when {
                remaining > 0 -> "${remaining / 60} 分 ${remaining % 60} 秒内送达"
                remaining == 0L -> "已到送达时限"
                else -> card.slotLabel ?: "无时限"
            },
        )
        MtLegBlock(
            pickupTitle = "门店取货",
            pickupSubtitle = card.goodsSummary?.takeIf { it.isNotBlank() },
            pickupDistance = RiderFormats.distance(card.distanceFromRiderMeters),
            deliverTitle = card.addressDetail ?: "地址待补充",
            deliverSubtitle = listOfNotNull(
                card.areaLabel,
                card.buildingLabel,
                card.floorNo?.let { "$it 楼" },
                card.roomNo?.let { "$it 室" },
            ).joinToString(" ").ifBlank { null },
            deliverDistance = task.legDistanceMeters?.let { RiderFormats.distance(it) },
            activeLeg = MtLeg.PICKUP,
            modifier = Modifier.padding(horizontal = FreshSpacing.Sm),
        )
        Row(
            modifier = Modifier.padding(FreshSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
        ) {
            card.coldChainText?.let { MtTag(it, tone = StatusTone.COLD) }
            if (card.itemCount > 0) MtTag("${card.itemCount} 件", tone = StatusTone.NORMAL)
            card.highlightNotes.take(2).forEach { MtTag(it, tone = StatusTone.WARNING) }
        }
        card.customerRemark?.takeIf { it.isNotBlank() }?.let {
            MtDivider()
            Text(
                text = "顾客备注：$it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(FreshSpacing.Sm),
            )
        }
    }
    Spacer(Modifier.padding(bottom = FreshSpacing.Xs))
    Text(
        text = "接单后系统开始计时，请尽快到店取货。",
        style = MaterialTheme.typography.bodySmall.tabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = FreshSpacing.Xxs, top = FreshSpacing.Xs),
    )
}

@Preview(name = "派单 · 新任务", showBackground = true, backgroundColor = 0xFFF5F5F5, heightDp = 720)
@Composable
private fun DispatchPreview() {
    RiderTheme { DispatchContent(task = previewTask(), accepting = false) }
}

@Preview(name = "派单 · 未同步", showBackground = true, backgroundColor = 0xFFF5F5F5, heightDp = 720)
@Composable
private fun DispatchEmptyPreview() {
    RiderTheme { DispatchContent(task = null, accepting = false) }
}
