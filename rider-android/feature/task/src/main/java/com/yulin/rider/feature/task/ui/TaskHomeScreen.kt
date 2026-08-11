package com.yulin.rider.feature.task.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshPageHeader
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.TaskBoard
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskSection
import com.yulin.rider.feature.task.data.WaveUi
import com.yulin.rider.feature.task.ui.components.TaskCardView
import com.yulin.rider.feature.task.util.RiderFormats

/** 今日配送驾驶舱。打开即看到在岗状态、风险提示与下一步任务。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHomeScreen(
    modifier: Modifier = Modifier,
    viewModel: TaskHomeViewModel = viewModel(),
    shiftHeader: @Composable () -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenWave: (Long) -> Unit = {},
    onOpenPickup: (Long) -> Unit = {},
    onOpenDeliver: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    TaskHomeContent(
        state = state,
        modifier = modifier,
        shiftHeader = shiftHeader,
        onRefresh = viewModel::refresh,
        onRetrySync = viewModel::retrySync,
        onSelectSection = viewModel::selectSection,
        onOpenTask = onOpenTask,
        onOpenWave = onOpenWave,
        onSlide = { task ->
            when (task.nextStep) {
                NextStep.ACCEPT -> viewModel.accept(task.taskId)
                NextStep.DEPART -> viewModel.depart(task.taskId)
                NextStep.ARRIVE -> viewModel.arrive(task.taskId)
                NextStep.PICKUP -> task.waveId?.let(onOpenPickup) ?: onOpenTask(task.taskId)
                NextStep.DELIVER -> onOpenDeliver(task.taskId)
                NextStep.NONE -> Unit
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskHomeContent(
    state: TaskHomeUiState,
    modifier: Modifier = Modifier,
    shiftHeader: @Composable () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetrySync: () -> Unit = {},
    onSelectSection: (TaskSection) -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenWave: (Long) -> Unit = {},
    onSlide: (TaskCardUi) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        FreshPageHeader(
            title = "今日配送",
            subtitle = "任务按时限与路线顺序排列",
        )
        shiftHeader()

        state.notice?.let {
            FreshBanner(
                text = it,
                tone = StatusTone.WARNING,
                icon = FreshIconType.OFFLINE,
            )
        }
        when {
            state.hasSyncFailure -> FreshBanner(
                text = "有操作多次同步失败",
                tone = StatusTone.DANGER,
                icon = FreshIconType.ERROR,
                actionText = "重试",
                onAction = onRetrySync,
            )

            state.pendingSyncCount > 0 -> FreshBanner(
                text = "${state.pendingSyncCount} 条操作已记录，联网后自动上报",
                tone = StatusTone.WARNING,
                icon = FreshIconType.OFFLINE,
            )
        }

        SectionTabs(
            board = state.board,
            selected = state.section,
            onSelect = onSelectSection,
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.loading -> FreshLoading(
                    modifier = Modifier.fillMaxSize(),
                    label = "正在同步今日任务",
                )

                state.board.count(state.section) == 0 -> FreshEmpty(
                    title = emptyTextOf(state.section),
                    message = "下拉刷新可重新同步门店任务",
                    icon = FreshIconType.TASK,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> TaskList(
                    board = state.board,
                    section = state.section,
                    onOpenTask = onOpenTask,
                    onOpenWave = onOpenWave,
                    onSlide = onSlide,
                )
            }
        }
    }
}

@Composable
private fun TaskList(
    board: TaskBoard,
    section: TaskSection,
    onOpenTask: (Long) -> Unit,
    onOpenWave: (Long) -> Unit,
    onSlide: (TaskCardUi) -> Unit,
) {
    val waves = board.waves
        .map { wave -> wave.copy(stops = wave.stops.filter { it.section == section }) }
        .filter { it.stops.isNotEmpty() }
    val standalone = board.standalone.filter { it.section == section }
    val done = section == TaskSection.TODAY_DONE

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(FreshSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        waves.forEach { wave ->
            item(key = "wave-${wave.wave.waveId}") {
                WaveHeader(wave = wave, onClick = { onOpenWave(wave.wave.waveId) })
            }
            items(wave.stops, key = { "task-${it.taskId}" }) { task ->
                TaskCardView(
                    task = task,
                    onClick = { onOpenTask(task.taskId) },
                    onSlideConfirm = if (done) null else ({ onSlide(task) }),
                )
            }
        }
        if (standalone.isNotEmpty()) {
            if (waves.isNotEmpty()) {
                item(key = "standalone-header") {
                    Text("单独任务", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(standalone, key = { "task-${it.taskId}" }) { task ->
                TaskCardView(
                    task = task,
                    onClick = { onOpenTask(task.taskId) },
                    onSlideConfirm = if (done) null else ({ onSlide(task) }),
                )
            }
        }
    }
}

@Composable
private fun WaveHeader(wave: WaveUi, onClick: () -> Unit) {
    val group = wave.wave
    val completed = group.completedCount >= group.taskCount
    FreshPanel(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button },
        title = "本趟 ${group.taskCount} 站",
        eyebrow = "波次 ${group.waveNo}",
        spineTone = if (completed) StatusTone.SUCCESS else StatusTone.INFO,
        action = {
            FreshStatusBadge(
                text = "完成 ${group.completedCount}/${group.taskCount}",
                tone = if (completed) StatusTone.SUCCESS else StatusTone.NORMAL,
            )
        },
    ) {
        Text(
            text = buildList {
                add("计划 ${RiderFormats.distance(group.planDistanceMeters)}")
                group.planDurationSeconds?.let { add(RiderFormats.duration(it)) }
                RiderFormats.hourMinute(group.planReturnAt)?.let { add("预计 $it 回店") }
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("查看站点顺序", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionTabs(
    board: TaskBoard,
    selected: TaskSection,
    onSelect: (TaskSection) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = TaskSection.entries.indexOf(selected)) {
        TaskSection.entries.forEach { section ->
            val count = board.count(section)
            Tab(
                selected = section == selected,
                onClick = { onSelect(section) },
                text = {
                    Text(
                        text = if (count > 0) "${section.label} $count" else section.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

private fun emptyTextOf(section: TaskSection): String = when (section) {
    TaskSection.PENDING_ACCEPT -> "暂无待接单"
    TaskSection.IN_PROGRESS -> "当前没有进行中的订单"
    TaskSection.TODAY_DONE -> "今天还没有完成的订单"
}

@Preview(name = "首页 · 有任务", showBackground = true)
@Composable
private fun TaskHomeLoadedPreview() {
    val wave = previewWave()
    RiderTheme {
        TaskHomeContent(
            state = TaskHomeUiState(
                board = TaskBoard(waves = listOf(wave)),
                section = TaskSection.IN_PROGRESS,
            ),
        )
    }
}

@Preview(name = "首页 · 离线", showBackground = true)
@Composable
private fun TaskHomeOfflinePreview() {
    RiderTheme {
        TaskHomeContent(
            state = TaskHomeUiState(
                board = TaskBoard(standalone = listOf(previewTask())),
                notice = "当前离线，显示本机缓存任务",
                pendingSyncCount = 2,
            ),
        )
    }
}

@Preview(name = "首页 · 空态", showBackground = true)
@Composable
private fun TaskHomeEmptyPreview() {
    RiderTheme { TaskHomeContent(TaskHomeUiState()) }
}

@Preview(name = "首页 · 加载", showBackground = true)
@Composable
private fun TaskHomeLoadingPreview() {
    RiderTheme { TaskHomeContent(TaskHomeUiState(loading = true)) }
}
