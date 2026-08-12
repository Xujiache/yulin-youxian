package com.yulin.rider.core.location

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.StatusTone
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

    Box(modifier = Modifier.fillMaxSize()) {
        MtScaffold(
            title = "保活设置向导",
            subtitle = "${vendor.displayName} · 已完成 ${doneIds.size}/${allSteps.size}",
            onBack = onBack,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = FreshSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                item { GuideHeader(vendor, doneIds.size, allSteps.size) }

                sections.forEach { section ->
                    item(key = section.title) {
                        MtCard {
                            MtSectionTitle(
                                section.title,
                                trailing = {
                                    MtTag(
                                        text = if (section.steps.all { it.id in doneIds }) "已完成" else "待设置",
                                        tone = if (section.steps.all { it.id in doneIds }) {
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
                    GuideFooter(
                        allRequiredDone = allRequiredDone,
                        onRedetect = {
                            refresh()
                            toast("已重新检测，系统权限状态已更新")
                        },
                        onSave = {
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
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun GuideHeader(
    vendor: KeepAliveVendor,
    doneCount: Int,
    totalCount: Int,
) {
    Column(
        modifier = Modifier.padding(top = FreshSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        Text(
            text = "识别到手机品牌:${vendor.displayName}",
            style = MaterialTheme.typography.titleMedium,
        )
        MtInfoBar(
            text = "锁屏省电可能终止定位与新单提醒，请逐项完成后再上班。",
            tone = StatusTone.WARNING,
            icon = FreshIconType.BATTERY,
            modifier = Modifier.clip(RoundedCornerShape(FreshRadius.Control)),
        )
        MtTag(
            text = "已完成 $doneCount / $totalCount 项",
            tone = if (doneCount >= totalCount) StatusTone.SUCCESS else StatusTone.WARNING,
        )
    }
}

@Composable
private fun StepRow(
    step: KeepAliveStep,
    done: Boolean,
    onOpen: () -> Unit,
    onToggleConfirm: (Boolean) -> Unit,
) {
    // 默认只展开还没做完的项;做完的收起来,十来项的页面才扫得动
    var expanded by remember(step.id) { mutableStateOf(!done) }

    Column(modifier = Modifier.padding(vertical = FreshSpacing.Xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = expanded, onValueChange = { expanded = it }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (step.required) step.title else "${step.title}(可选)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = step.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MtTag(
                text = if (done) "已完成" else "待设置",
                tone = if (done) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        }

        if (!expanded) return@Column

        Column(
            modifier = Modifier.padding(top = FreshSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
        ) {
            step.instructions.forEachIndexed { index, line ->
                Text(
                    text = "${index + 1}. $line",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                        .toggleable(value = done, onValueChange = onToggleConfirm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = done, onCheckedChange = null)
                    Text(
                        text = "我已按上面的步骤设置好了",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = FreshSpacing.Xs),
                    )
                }
            } else {
                Text(
                    text = "这一项手机可以自动检测,设置完返回本页会自动变成「已完成」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GuideFooter(
    allRequiredDone: Boolean,
    onRedetect: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        modifier = Modifier.padding(bottom = FreshSpacing.Xl),
    ) {
        MtPrimaryButton(
            text = "重新检测",
            action = MtAction.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
            icon = FreshIconType.REFRESH,
            onClick = onRedetect,
        )

        MtPrimaryButton(
            text = if (allRequiredDone) "完成设置" else "保存进度",
            action = MtAction.ACCEPT,
            modifier = Modifier.fillMaxWidth(),
            onClick = onSave,
        )

        Text(
            text = "这些设置一次做完就长期有效。系统大版本升级后建议回来重新检测一次。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
