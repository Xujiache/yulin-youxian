package com.yulin.rider.core.location

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yulin.rider.core.designsystem.CheckMarkIcon
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtBottomActionBar
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 保活向导页(06 §3.2 第三层)。这是决定 App 能不能用的核心功能,不是锦上添花:
 * 国产 ROM 上不做这一页,骑手锁屏几分钟后定位就断了,调度台会把他标成掉线。
 *
 * 页面自持依赖(Hilt EntryPoint),导航侧只需要一行:
 * `composable(RiderRoutes.KEEPALIVE_GUIDE) { KeepAliveGuideScreen(onBack = { navController.popBackStack() }) }`
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KeepAliveGuideEntryPoint {
    fun keepAliveChecker(): KeepAliveChecker
    fun deviceStore(): RiderDeviceStore
    fun deviceReporter(): RiderDeviceReporter
}

@Composable
fun KeepAliveGuideScreen(
    onBack: () -> Unit = {},
    onCompleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deps = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KeepAliveGuideEntryPoint::class.java,
        )
    }

    val vendor = remember { KeepAliveVendor.detect() }
    val sections = remember(vendor) { KeepAliveCatalog.sectionsFor(vendor) }
    val allSteps = remember(sections) { sections.flatMap { it.steps } }
    val stepIds = remember(allSteps) { allSteps.map { it.id } }
    val appLabel = remember(context) { context.appLabel() }

    var status by remember { mutableStateOf(deps.keepAliveChecker().current()) }
    var confirmed by remember { mutableStateOf(deps.deviceStore().confirmedSteps(stepIds)) }

    // 用 Snackbar 而不是页内提示条:「重新检测」「保存进度」在这一页的最底部,
    // 十来项展开后页头早滚出屏幕了,提示挂在页头等于骑手点完什么都看不到。
    val snackbarHostState = remember { SnackbarHostState() }
    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun refresh() {
        status = deps.keepAliveChecker().current()
        confirmed = deps.deviceStore().confirmedSteps(stepIds)
    }

    // 骑手从系统设置页回来时自动更新状态,省掉一次手动点击。
    // 用轮询而不是监听 ON_RESUME:一次检测只是几个权限查询,开销可忽略,
    // 而 LocalLifecycleOwner 正在 compose-ui 与 lifecycle 之间换包,轮询没有这层耦合。
    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_DETECT_INTERVAL_MILLIS)
            refresh()
        }
    }

    val doneIds = remember(status, confirmed) {
        allSteps.filter { it.isDone(status, confirmed) }.map { it.id }.toSet()
    }
    val allRequiredDone = allSteps.filter { it.required }.all { it.id in doneIds }

    // 手风琴式展开：同一时刻只摊开一项。十来项全展开时页面有五六屏长，
    // 骑手根本找不到自己做到哪了 —— 这正是这一页原来最难用的地方。
    val firstPending = allSteps.firstOrNull { it.id !in doneIds }?.id
    var expandedStepId by rememberSaveable { mutableStateOf(firstPending) }
    LaunchedEffect(firstPending) {
        if (expandedStepId != null && expandedStepId in doneIds) expandedStepId = firstPending
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MtScaffold(
            title = "保活设置向导",
            subtitle = vendor.displayName,
            onBack = onBack,
            bottomBar = {
                // 十来项要滚好几屏，主操作留在内容流里等于永远够不着
                MtBottomActionBar(
                    secondaryActions = {
                        MtPrimaryButton(
                            text = "重新检测",
                            action = MtAction.SECONDARY,
                            icon = FreshIconType.REFRESH,
                            onClick = {
                                refresh()
                                toast("已重新检测，系统权限状态已更新")
                            },
                        )
                    },
                ) {
                    MtPrimaryButton(
                        text = if (allRequiredDone) "完成设置" else "保存进度",
                        action = MtAction.ACCEPT,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                deps.deviceReporter().reportKeepAliveGuideDone(allRequiredDone)
                                refresh()
                                if (allRequiredDone) {
                                    onCompleted()
                                } else {
                                    toast("进度已保存，未完成项会在上班前继续提醒")
                                }
                            }
                        },
                    )
                }
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = FreshSpacing.Sm),
                contentPadding = PaddingValues(top = FreshSpacing.Xs, bottom = FreshSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                item { GuideProgress(doneIds.size, allSteps.size) }

                sections.forEach { section ->
                    item(key = section.title) {
                        val sectionDone = section.steps.count { it.id in doneIds }
                        MtCard {
                            MtSectionTitle(
                                section.title,
                                trailing = {
                                    MtTag(
                                        text = "$sectionDone/${section.steps.size}",
                                        tone = if (sectionDone == section.steps.size) {
                                            StatusTone.SUCCESS
                                        } else {
                                            StatusTone.WARNING
                                        },
                                    )
                                },
                            )
                            MtDivider()
                            section.steps.forEachIndexed { index, step ->
                                if (index > 0) MtDivider()
                                StepRow(
                                    step = step,
                                    done = step.id in doneIds,
                                    expanded = expandedStepId == step.id,
                                    onToggleExpand = {
                                        expandedStepId = if (expandedStepId == step.id) null else step.id
                                    },
                                    onOpen = {
                                        when (KeepAliveIntents.open(context, step.action, appLabel)) {
                                            KeepAliveIntents.OpenOutcome.OPENED -> Unit
                                            KeepAliveIntents.OpenOutcome.FELL_BACK ->
                                                toast("这台手机没有这个设置页，已打开应用详情页，请按文字步骤操作")

                                            KeepAliveIntents.OpenOutcome.FAILED ->
                                                toast("请在手机自带管家中手动完成这一项")
                                        }
                                    },
                                    onToggleConfirm = { checked ->
                                        deps.deviceStore().setStepConfirmed(step.id, checked)
                                        refresh()
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "这些设置一次做完就长期有效。系统大版本升级后建议回来重新检测一次。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = FreshSpacing.Xxs,
                            vertical = FreshSpacing.Xs,
                        ),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 进度卡。品牌名已经在顶栏副标题里，这里不再重复，只回答「还差几项」——
 * 这是骑手打开这一页唯一想知道的事。
 */
@Composable
private fun GuideProgress(doneCount: Int, totalCount: Int) {
    val remaining = (totalCount - doneCount).coerceAtLeast(0)
    val allDone = remaining == 0
    MtCard {
        Column(modifier = Modifier.padding(FreshSpacing.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (allDone) "全部设置完成" else "还差 $remaining 项",
                    style = MaterialTheme.typography.titleMedium,
                    color = RiderColors.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$doneCount/$totalCount",
                    style = MaterialTheme.typography.titleMedium.tabularFigures(),
                    color = if (allDone) RiderColors.Deliver else RiderColors.Pickup,
                )
            }
            LinearProgressIndicator(
                progress = { if (totalCount == 0) 0f else doneCount.toFloat() / totalCount },
                color = if (allDone) RiderColors.Deliver else RiderColors.Primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FreshSpacing.Xs)
                    .height(6.dp)
                    .clip(RoundedCornerShape(FreshRadius.Pill)),
            )
        }
        MtInfoBar(
            text = "锁屏省电会终止定位与新单提醒，逐项完成后再上班。",
            tone = StatusTone.WARNING,
            icon = FreshIconType.BATTERY,
        )
    }
}

/** 状态点。收起状态下这一列是「做到哪了」的唯一视觉线索，比右侧标签更早被扫到。 */
@Composable
private fun StepStatusDot(done: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (done) RiderColors.Deliver else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            CheckMarkIcon(color = RiderColors.Surface, modifier = Modifier.size(11.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(RiderColors.Pickup),
            )
        }
    }
}

/**
 * 单个检查项。
 *
 * 收起时是一行摘要：状态点 + 标题 + 一行说明 + 状态标签，扫一眼就知道还剩哪几项；
 * 展开后才出现编号步骤、去设置按钮和确认勾选。展开与否由外层控制，
 * 全页同时只摊开一项 —— 十项全展开是原来这一页读不动的根本原因。
 */
@Composable
private fun StepRow(
    step: KeepAliveStep,
    done: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpen: () -> Unit,
    onToggleConfirm: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            StepStatusDot(done)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (step.required) step.title else "${step.title}（可选）",
                    style = MaterialTheme.typography.titleSmall,
                    color = RiderColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = step.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 收起时压到一行。这些说明都是两三十字，不截断的话一屏只装得下三项
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MtTag(
                text = if (done) "已完成" else "待设置",
                tone = if (done) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        }

        if (!expanded) return@Column

        Column(
            // 左侧缩进对齐标题，展开区在视觉上明确从属于上面那一行
            modifier = Modifier.padding(
                start = STEP_CONTENT_INDENT,
                end = FreshSpacing.Sm,
                bottom = FreshSpacing.Sm,
            ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            if (step.instructions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FreshRadius.Control))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(FreshSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
                ) {
                    step.instructions.forEachIndexed { index, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs)) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall.tabularFigures(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = RiderColors.Ink,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (step.action != KeepAliveAction.ManualOnly) {
                MtPrimaryButton(
                    text = "去设置",
                    action = MtAction.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                    icon = FreshIconType.SETTINGS,
                    onClick = onOpen,
                )
            }

            if (step.autoDetect == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FreshRadius.Control))
                        .toggleable(value = done, onValueChange = onToggleConfirm)
                        .padding(vertical = FreshSpacing.Xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = done, onCheckedChange = null)
                    Text(
                        text = "我已按上面的步骤设置好了",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RiderColors.Ink,
                        modifier = Modifier.padding(start = FreshSpacing.Xxs),
                    )
                }
            } else {
                Text(
                    text = "这一项可以自动检测，设置完返回本页会自动变成「已完成」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 状态点 20dp + 间距 8dp + 卡片左内边距 12dp，让展开区与标题左对齐。 */
private val STEP_CONTENT_INDENT = 40.dp

/** 可自动检测的项以系统状态为准,骑手勾了也不算数;其余项才看自述确认。 */
private fun KeepAliveStep.isDone(status: KeepAliveStatus, confirmed: Set<String>): Boolean =
    when (autoDetect) {
        KeepAliveAutoDetect.FINE_LOCATION -> status.fineLocationGranted
        KeepAliveAutoDetect.BACKGROUND_LOCATION -> status.backgroundLocationGranted
        KeepAliveAutoDetect.NOTIFICATION -> status.notificationGranted
        KeepAliveAutoDetect.BATTERY_OPTIMIZATION -> status.batteryOptimizationIgnored
        null -> id in confirmed
    }

private fun Context.appLabel(): String = runCatching {
    applicationInfo.loadLabel(packageManager).toString()
}.getOrDefault("禹邻优鲜骑手")

private const val AUTO_DETECT_INTERVAL_MILLIS = 1_000L
