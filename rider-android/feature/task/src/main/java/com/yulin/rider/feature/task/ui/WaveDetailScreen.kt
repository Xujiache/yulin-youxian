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
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtBottomActionBar
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtMetric
import com.yulin.rider.core.designsystem.MtPrimaryButton
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
import com.yulin.rider.feature.task.data.riderMessage
import com.yulin.rider.feature.task.data.runTransition
import com.yulin.rider.feature.task.ui.components.tone
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 这一波能不能收尾，以及底部要给骑手说明什么。null 表示这趟还有活要干。 */
internal data class WaveClosing(val hint: String, val exceptionCount: Int)

/**
 * 判据是「没有任何一单还等着骑手推进」，而不是「全部落到终态」。
 *
 * 骑手上报「顾客联系不上」之后那一单是 EXCEPTION，等调度解除，他这边已经无事可做，
 * 可 EXCEPTION 不在 DELIVERED/RETURNED/CANCELLED 里 —— 按终态判的话「我已回店」永远不出现，
 * 这一波收不了尾，调度台也就发不出下一个时段。
 *
 * 站点为空时返回 null:这种波次进不到本页(buildBoard 会把没有站点的波次滤掉)，
 * 真出现了也不该让骑手替一趟空路线签收尾。
 */
internal fun waveClosing(stops: List<TaskCardUi>): WaveClosing? {
    if (stops.isEmpty() || stops.any { it.nextStep.actionable }) return null
    val exceptions = stops.count { it.displayStatus == TaskStatus.EXCEPTION }
    val hint = when {
        exceptions > 0 -> "本趟有 $exceptions 单异常等调度处理，回到门店点一下先把这趟收尾"
        stops.all { it.displayStatus == TaskStatus.CANCELLED } ->
            "本趟订单已全部取消，回到门店点一下，调度台才会发下一个时段"
        else -> "这趟送完了，回到门店后点一下，调度台才会发下一个时段"
    }
    return WaveClosing(hint, exceptions)
}

class WaveDetailViewModel(app: Application, private val waveId: Long) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _wave = MutableStateFlow<WaveUi?>(null)
    val wave: StateFlow<WaveUi?> = _wave

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * 回店在任务上没有可乐观更新的状态，界面看不出「已经点过了」。
     * 本地标记加上按 waveId 固定的幂等键，连点不会再变成好几条 RETURN_WAVE。
     */
    private val _returnSubmitted = MutableStateFlow(false)
    val returnSubmitted: StateFlow<Boolean> = _returnSubmitted

    private val _accepting = MutableStateFlow(false)
    val accepting: StateFlow<Boolean> = _accepting

    init {
        viewModelScope.launch { repository.observeWave(waveId).collect { _wave.value = it } }
        viewModelScope.launch { repository.refresh() }
    }

    fun dismissError() {
        _error.value = null
    }

    fun acceptWave() = viewModelScope.launch {
        if (_accepting.value) return@launch
        _accepting.value = true
        _error.value = runTransition { repository.acceptWave(waveId) }.riderMessage
        _accepting.value = false
    }

    fun returnWave() = viewModelScope.launch {
        if (_returnSubmitted.value) return@launch
        _returnSubmitted.value = true
        val result = runTransition { repository.returnWave(waveId) }
        _error.value = result.riderMessage
        // 没排进队列就把标记退回去，否则骑手连重试的入口都没有
        if (!result.queued) _returnSubmitted.value = false
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
    val error by viewModel.error.collectAsState()
    val returnSubmitted by viewModel.returnSubmitted.collectAsState()
    val accepting by viewModel.accepting.collectAsState()
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
    WaveDetailContent(
        wave = current,
        error = error,
        returnSubmitted = returnSubmitted,
        accepting = accepting,
        modifier = modifier,
        onBack = onBack,
        onOpenTask = onOpenTask,
        onAcceptWave = viewModel::acceptWave,
        onReturnWave = viewModel::returnWave,
        onDismissError = viewModel::dismissError,
    )
}

@Composable
private fun WaveDetailContent(
    wave: WaveUi,
    error: String? = null,
    returnSubmitted: Boolean = false,
    accepting: Boolean = false,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onAcceptWave: () -> Unit = {},
    onReturnWave: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    val completed = wave.wave.completedCount >= wave.wave.taskCount
    // 时段批次制：整波单一次性发下来，一单一单点接单在店门口太慢
    val pendingAccept = wave.stops.count { it.card.status == TaskStatus.ASSIGNED }
    val closing = waveClosing(wave.stops)
    MtScaffold(
        title = "本趟路线",
        subtitle = wave.wave.waveNo,
        modifier = modifier,
        onBack = onBack,
        bottomBar = {
            when {
                pendingAccept > 0 -> MtBottomActionBar(hint = error, hintTone = StatusTone.DANGER) {
                    MtPrimaryButton(
                        text = if (accepting) "正在记录…" else "一键接单（$pendingAccept 单）",
                        action = MtAction.ACCEPT,
                        modifier = Modifier.weight(1f),
                        enabled = !accepting,
                        onClick = onAcceptWave,
                    )
                }

                closing != null -> MtBottomActionBar(
                    hint = error ?: closing.hint,
                    hintTone = if (error != null) StatusTone.DANGER else StatusTone.WARNING,
                ) {
                    MtPrimaryButton(
                        text = if (returnSubmitted) "回店已记录，等待同步" else "我已回店",
                        action = MtAction.ACCEPT,
                        modifier = Modifier.weight(1f),
                        enabled = !returnSubmitted,
                        onClick = onReturnWave,
                    )
                }
            }
        },
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
            error?.let { message ->
                item {
                    MtInfoBar(
                        text = message,
                        tone = StatusTone.DANGER,
                        icon = FreshIconType.ERROR,
                        onDismiss = onDismissError,
                    )
                }
            }
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
                            // 0 是「还没规划」，交给 distance() 渲染成「—」而不是「0 m」
                            value = RiderFormats.distance(
                                wave.wave.planDistanceMeters?.takeIf { it > 0 },
                            ),
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
