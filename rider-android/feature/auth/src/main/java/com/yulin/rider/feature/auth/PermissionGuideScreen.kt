package com.yulin.rider.feature.auth

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yulin.rider.core.common.DeviceBrand
import com.yulin.rider.core.common.SystemSettings
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.CheckMarkIcon
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSecondaryButton
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SectionCard
import com.yulin.rider.core.designsystem.StatusChip
import com.yulin.rider.core.designsystem.StatusTone

/**
 * 三步权限引导(06 §3.2 第二层)。
 *
 * 顺序不能改:前台定位 + 通知 → 定位授权同意 → 后台定位。
 * 后台定位必须单独申请,且 Android 11 起系统不再弹「始终允许」选项,只能跳设置页,
 * 所以每一步都同时提供「去设置」兜底 —— 国产 ROM 上弹窗被拦是常态。
 */
@Composable
fun PermissionGuideRoute(
    onNeedLocationConsent: () -> Unit,
    onCompleted: () -> Unit,
    hasLocationConsent: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val brand = remember { DeviceBrand.current() }

    var refreshKey by remember { mutableIntStateOf(0) }
    var backgroundRequested by remember { mutableStateOf(false) }

    // 骑手会跳去系统设置再回来,回到前台必须重新读一遍权限状态,否则界面一直显示「未开启」
    ObserveResume { refreshKey++ }

    val foregroundGranted = remember(refreshKey) { context.hasForegroundLocation() }
    val notificationGranted = remember(refreshKey) { context.hasNotificationPermission() }
    val backgroundGranted = remember(refreshKey) { context.hasBackgroundLocation() }

    val step1Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshKey++
        // 系统静默拒绝(Android 11+ 的典型表现),只能引导骑手去设置页手动选「始终允许」
        if (!granted) SystemSettings.openAppDetails(context)
    }

    val stepOneDone = foregroundGranted && notificationGranted
    val allDone = stepOneDone && hasLocationConsent && backgroundGranted

    FreshStackScaffold(
        title = "开工授权",
        subtitle = "完成三步，保证接单不漏、位置不断",
        modifier = modifier,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets)
                .padding(FreshSpacing.Md),
        ) {
            FreshBanner(
                text = "当前机型：${brand.displayName}。按顺序完成，系统会自动检查结果。",
                tone = StatusTone.INFO,
                icon = FreshIconType.SETTINGS,
            )
            Spacer(Modifier.height(FreshSpacing.Md))

            PermissionStepCard(
                index = 1,
                title = "定位权限 + 通知权限",
                purpose = "定位用来给你派顺路的单、算送达时间；通知用来在有新单时把你叫醒。" +
                    "不给这两项，App 收不到单，也没法上班。",
                done = stepOneDone,
                actionText = "去开启",
                onAction = { step1Launcher.launch(foregroundPermissions()) },
                onFallback = { SystemSettings.openAppDetails(context) },
                detail = buildString {
                    append(if (foregroundGranted) "定位：已开启" else "定位：未开启")
                    append("　")
                    append(if (notificationGranted) "通知：已开启" else "通知：未开启")
                },
            )

            Spacer(Modifier.height(FreshSpacing.Sm))

            PermissionStepCard(
                index = 2,
                title = "位置信息授权同意",
                purpose = "法律要求对精确位置单独取得你的同意，所以这一步是独立的一屏说明，" +
                    "看完再决定。同意之后随时可以在设置里撤回。",
                done = hasLocationConsent,
                enabled = stepOneDone,
                actionText = "查看并确认",
                onAction = onNeedLocationConsent,
                onFallback = null,
                detail = if (hasLocationConsent) "已签署" else "尚未签署，未签署无法上班",
            )

            Spacer(Modifier.height(FreshSpacing.Sm))

            PermissionStepCard(
                index = 3,
                title = "后台定位（始终允许）",
                purpose = "送货时你会锁屏、会接电话、会打开导航。只有选了「始终允许」，" +
                    "位置才不会在锁屏后断掉，店长也才知道你没掉线。",
                done = backgroundGranted,
                enabled = stepOneDone,
                actionText = if (backgroundRequested) "去设置里选「始终允许」" else "去开启",
                onAction = {
                    backgroundRequested = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        refreshKey++
                    }
                },
                onFallback = { SystemSettings.openAppDetails(context) },
                detail = if (backgroundGranted) {
                    "已选择「始终允许」"
                } else {
                    "在系统设置的「权限 - 位置」里选择「始终允许」"
                },
            )

            Spacer(Modifier.height(FreshSpacing.Md))

            FreshPanel(
                title = "让 App 在锁屏后保持在线",
                eyebrow = "推荐设置",
                spineTone = StatusTone.WARNING,
            ) {
                Text(
                    text = "${brand.displayName}的省电策略可能在锁屏后杀掉 App。" +
                        "开启自启动、把耗电策略改成「无限制」，位置才不会中断。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
                    FreshSecondaryButton(
                        text = "自启动",
                        icon = FreshIconType.REFRESH,
                        modifier = Modifier.weight(1f),
                        onClick = { SystemSettings.openAutoStartSettings(context, brand) },
                    )
                    FreshSecondaryButton(
                        text = "电池优化",
                        icon = FreshIconType.BATTERY,
                        modifier = Modifier.weight(1f),
                        onClick = { SystemSettings.openBatteryOptimization(context) },
                    )
                }
            }

            Spacer(Modifier.height(FreshSpacing.Lg))

            BigActionButton(
                text = if (allDone) "全部完成，开始接单" else "先这样，稍后再设置",
                tone = if (allDone) StatusTone.SUCCESS else StatusTone.NORMAL,
                icon = if (allDone) FreshIconType.CHECK else FreshIconType.ROUTE,
                onClick = onCompleted,
            )

            Spacer(Modifier.height(FreshSpacing.Xxl))
        }
    }
}

@Composable
private fun PermissionStepCard(
    index: Int,
    title: String,
    purpose: String,
    done: Boolean,
    actionText: String,
    onAction: () -> Unit,
    onFallback: (() -> Unit)?,
    detail: String,
    enabled: Boolean = true,
) {
    FreshPanel(
        spineTone = when {
            done -> StatusTone.SUCCESS
            !enabled -> StatusTone.NORMAL
            else -> StatusTone.WARNING
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepBadge(index = index, done = done)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            FreshStatusBadge(
                text = if (done) "已完成" else "待处理",
                tone = if (done) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        }

        Text(
            text = purpose,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) RiderColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!done) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                FreshSecondaryButton(
                    text = actionText,
                    icon = FreshIconType.SETTINGS,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAction,
                )
                if (onFallback != null) {
                    TextButton(
                        onClick = onFallback,
                        enabled = enabled,
                        modifier = Modifier.defaultMinSize(minHeight = RiderDimens.TouchTarget),
                    ) {
                        Text("打不开？去应用设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBadge(index: Int, done: Boolean) {
    val color = if (done) RiderColors.Success else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            FreshIcon(
                type = FreshIconType.CHECK,
                contentDescription = null,
                tint = color,
                size = 20.dp,
            )
        } else {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
        }
    }
}

@Preview(name = "授权 · 待处理", showBackground = true)
@Composable
private fun PermissionGuidePendingPreview() {
    RiderTheme {
        FreshStackScaffold(title = "开工授权", subtitle = "完成三步，保证接单不漏") { insets ->
            Column(
                modifier = Modifier.padding(insets).padding(FreshSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
            ) {
                PermissionStepCard(
                    index = 1,
                    title = "定位权限 + 通知权限",
                    purpose = "用于派单、导航与新单提醒。",
                    done = true,
                    actionText = "去开启",
                    onAction = {},
                    onFallback = {},
                    detail = "定位：已开启　通知：已开启",
                )
                PermissionStepCard(
                    index = 2,
                    title = "位置信息授权同意",
                    purpose = "精确位置需要单独确认。",
                    done = false,
                    actionText = "查看并确认",
                    onAction = {},
                    onFallback = null,
                    detail = "尚未签署，未签署无法上班",
                )
            }
        }
    }
}

@Preview(name = "授权 · 全部完成", showBackground = true)
@Composable
private fun PermissionGuideCompletePreview() {
    RiderTheme {
        FreshStackScaffold(title = "开工授权", subtitle = "三步均已完成") { insets ->
            Column(
                modifier = Modifier.padding(insets).padding(FreshSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
            ) {
                PermissionStepCard(
                    index = 3,
                    title = "后台定位（始终允许）",
                    purpose = "锁屏、接电话或打开导航时保持在线。",
                    done = true,
                    actionText = "去开启",
                    onAction = {},
                    onFallback = {},
                    detail = "已选择「始终允许」",
                )
                BigActionButton(
                    text = "全部完成，开始接单",
                    tone = StatusTone.SUCCESS,
                    icon = FreshIconType.CHECK,
                    onClick = {},
                )
            }
        }
    }
}

/**
 * 回到前台就重读一次权限。
 * 宿主 Activity 从 Context 链上找,不用 LocalLifecycleOwner ——
 * 这个 CompositionLocal 在 Compose 与 lifecycle 之间搬过家,跨版本引用不稳。
 */
@Composable
private fun ObserveResume(onResume: () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(owner) {
        if (owner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun foregroundPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasForegroundLocation(): Boolean =
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

internal fun Context.hasBackgroundLocation(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        hasForegroundLocation()
    }

internal fun Context.hasNotificationPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        isGranted(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
