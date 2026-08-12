package com.yulin.rider.feature.task.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtEmptyState
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtTab
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.MtTextTabs
import com.yulin.rider.core.designsystem.MtTopBar
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.TaskBoard
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskSection
import com.yulin.rider.feature.task.data.WaveUi
import com.yulin.rider.feature.task.ui.components.TaskCardView
import com.yulin.rider.feature.task.util.RiderFormats

/**
 * 主界面。
 *
 * 结构照搬美团骑手端：顶栏只有菜单、在岗胶囊和消息；下面一排文字 Tab 分「待接单 / 进行中 / 今日完成」，
 * 右侧挂路线入口；中间整屏留给任务列表；底部是一条固定操作条。
 * 没有底部 Tab 栏，其余入口都在左侧抽屉里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHomeScreen(
    modifier: Modifier = Modifier,
    viewModel: TaskHomeViewModel = viewModel(),
    onOpenMenu: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onOpenRoute: () -> Unit = {},
    statusPill: @Composable () -> Unit = {},
    shiftHeader: @Composable (onRefresh: () -> Unit) -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenWave: (Long) -> Unit = {},
    onOpenPickup: (Long) -> Unit = {},
    onOpenDeliver: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    TaskHomeContent(
        state = state,
        modifier = modifier,
        onOpenMenu = onOpenMenu,
        onOpenMessages = onOpenMessages,
        onOpenRoute = onOpenRoute,
        statusPill = statusPill,
        dutyBar = shiftHeader,
        onRefresh = viewModel::refresh,
        onRetrySync = viewModel::retrySync,
        onSelectSection = viewModel::selectSection,
        onOpenTask = onOpenTask,
        onOpenWave = onOpenWave,
        onAdvance = { task ->
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
internal fun TaskHomeContent(
    state: TaskHomeUiState,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onOpenRoute: () -> Unit = {},
    statusPill: @Composable () -> Unit = {},
    dutyBar: @Composable (onRefresh: () -> Unit) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetrySync: () -> Unit = {},
    onSelectSection: (TaskSection) -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
    onOpenWave: (Long) -> Unit = {},
    onAdvance: (TaskCardUi) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        MtTopBar(
            onMenu = onOpenMenu,
            statusPill = statusPill,
            actions = {
                Box(
                    modifier = Modifier
                        .size(RiderDimens.TouchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenMessages)
                        .semantics {
                            contentDescription = "消息中心"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    FreshIcon(FreshIconType.BELL, contentDescription = null, size = 22.dp)
                }
            },
        )

        MtTextTabs(
            tabs = TaskSection.entries.map { MtTab(it.label, state.board.count(it)) },
            selectedIndex = TaskSection.entries.indexOf(state.section),
            onSelect = { onSelectSection(TaskSection.entries[it]) },
            trailing = {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs)
                        .semantics {
                            contentDescription = "查看配送路线"
                            role = Role.Button
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
                ) {
                    FreshIcon(
                        FreshIconType.ROUTE,
                        contentDescription = null,
                        tint = RiderColors.Ink,
                        size = 18.dp,
                    )
                    Text("路线", style = MaterialTheme.typography.titleSmall, color = RiderColors.Ink)
                }
            },
        )
        MtDivider()

        state.notice?.let {
            MtInfoBar(text = it, tone = StatusTone.WARNING, icon = FreshIconType.OFFLINE)
        }
        when {
            state.hasSyncFailure -> MtInfoBar(
                text = "有操作多次同步失败，点此重试",
                tone = StatusTone.DANGER,
                icon = FreshIconType.ERROR,
                onDismiss = onRetrySync,
            )

            state.pendingSyncCount > 0 -> MtInfoBar(
                text = "${state.pendingSyncCount} 条操作已记录，联网后自动上报",
                tone = StatusTone.WARNING,
                icon = FreshIconType.OFFLINE,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
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

                    state.board.count(state.section) == 0 -> MtEmptyState(
                        title = emptyTitleOf(state.section),
                        message = emptyMessageOf(state.section),
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> TaskList(
                        board = state.board,
                        section = state.section,
                        onOpenTask = onOpenTask,
                        onOpenWave = onOpenWave,
                        onAdvance = onAdvance,
                    )
                }
            }
        }

        // 底部固定操作条：未上线时是黄色「上线」，已上线时是「刷新列表」
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
        ) {
            dutyBar(onRefresh)
        }
    }
}

@Composable
private fun TaskList(
    board: TaskBoard,
    section: TaskSection,
    onOpenTask: (Long) -> Unit,
    onOpenWave: (Long) -> Unit,
    onAdvance: (TaskCardUi) -> Unit,
) {
    val waves = board.waves
        .map { wave -> wave.copy(stops = wave.stops.filter { it.section == section }) }
        .filter { it.stops.isNotEmpty() }
    val standalone = board.standalone.filter { it.section == section }
    val done = section == TaskSection.TODAY_DONE

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
        waves.forEach { wave ->
            item(key = "wave-${wave.wave.waveId}") {
                WaveHeader(wave = wave, onClick = { onOpenWave(wave.wave.waveId) })
            }
            items(wave.stops, key = { "task-${it.taskId}" }) { task ->
                TaskCardView(
                    task = task,
                    onClick = { onOpenTask(task.taskId) },
                    onAdvance = if (done) null else ({ onAdvance(task) }),
                )
            }
        }
        if (standalone.isNotEmpty()) {
            if (waves.isNotEmpty()) {
                item(key = "standalone-header") {
                    Text(
                        text = "单独任务",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = FreshSpacing.Xxs,
                            top = FreshSpacing.Xs,
                            bottom = FreshSpacing.Xxs,
                        ),
                    )
                }
            }
            items(standalone, key = { "task-${it.taskId}" }) { task ->
                TaskCardView(
                    task = task,
                    onClick = { onOpenTask(task.taskId) },
                    onAdvance = if (done) null else ({ onAdvance(task) }),
                )
            }
        }
    }
}

/** 波次头。一趟多站时先给一张概览卡，点进去看站点顺序。 */
@Composable
private fun WaveHeader(wave: WaveUi, onClick: () -> Unit) {
    val group = wave.wave
    val completed = group.completedCount >= group.taskCount
    MtCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FreshSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            FreshIcon(
                FreshIconType.ROUTE,
                contentDescription = null,
                tint = RiderColors.Pickup,
                size = 18.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "波次 ${group.waveNo} · 本趟 ${group.taskCount} 站",
                    style = MaterialTheme.typography.titleMedium,
                    color = RiderColors.Ink,
                )
                Text(
                    text = buildList {
                        add("计划 ${RiderFormats.distance(group.planDistanceMeters)}")
                        group.planDurationSeconds?.let { add(RiderFormats.duration(it)) }
                        RiderFormats.hourMinute(group.planReturnAt)?.let { add("预计 $it 回店") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MtTag(
                text = "完成 ${group.completedCount}/${group.taskCount}",
                tone = if (completed) StatusTone.SUCCESS else StatusTone.NORMAL,
            )
            FreshIcon(
                FreshIconType.CHEVRON_RIGHT,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 16.dp,
            )
        }
    }
}

private fun emptyTitleOf(section: TaskSection): String = when (section) {
    TaskSection.PENDING_ACCEPT -> "附近暂时没有任务"
    TaskSection.IN_PROGRESS -> "当前没有进行中的订单"
    TaskSection.TODAY_DONE -> "今天还没有完成的订单"
}

private fun emptyMessageOf(section: TaskSection): String = when (section) {
    TaskSection.PENDING_ACCEPT -> "上线后门店派单会自动出现在这里"
    TaskSection.IN_PROGRESS -> "接到单后会显示取货与送达进度"
    TaskSection.TODAY_DONE -> "完成的订单会留在这里方便回查"
}

@Preview(name = "首页 · 有任务", showBackground = true, backgroundColor = 0xFFF5F5F5, heightDp = 780)
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

@Preview(name = "首页 · 离线", showBackground = true, backgroundColor = 0xFFF5F5F5, heightDp = 780)
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

@Preview(name = "首页 · 空态", showBackground = true, backgroundColor = 0xFFF5F5F5, heightDp = 780)
@Composable
private fun TaskHomeEmptyPreview() {
    RiderTheme { TaskHomeContent(TaskHomeUiState()) }
}

@Preview(name = "首页 · 加载", showBackground = true, backgroundColor = 0xFFF5F5F5, heightDp = 780)
@Composable
private fun TaskHomeLoadingPreview() {
    RiderTheme { TaskHomeContent(TaskHomeUiState(loading = true)) }
}
