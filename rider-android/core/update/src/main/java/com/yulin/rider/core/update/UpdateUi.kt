package com.yulin.rider.core.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.RiderColors
import kotlinx.coroutines.launch

@Composable
fun AppUpdateScreen(
    controller: UpdateController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.check(manual = true) }
    MtScaffold(title = "应用更新", modifier = modifier, onBack = onBack) {
        UpdatePanel(
            state = state,
            controller = controller,
            force = state.policy == UpdatePolicyResolver.FORCE,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun ForceUpdateGate(state: UpdateUiState, controller: UpdateController) {
    if (state.policy != UpdatePolicyResolver.FORCE) return
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        UpdatePanel(
            state = state,
            controller = controller,
            force = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun OptionalUpdateDialog(
    state: UpdateUiState,
    controller: UpdateController,
    riderOnDuty: Boolean,
) {
    if (!UpdatePromptPolicy.shouldShowOptionalDialog(riderOnDuty, state.policy, state.showOptional)) {
        return
    }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { controller.dismissOptional() },
        title = { Text(state.latest?.title ?: "发现新版本") },
        text = {
            UpdatePanel(state = state, controller = controller, force = false)
        },
        confirmButton = {},
        dismissButton = {
            if (!state.accepted && !state.downloading && !state.ready) {
                MtPrimaryButton(
                    text = "以后再说",
                    action = MtAction.SECONDARY,
                    onClick = { scope.launch { controller.skipOptional() } },
                )
            }
        },
    )
}

@Composable
fun UpdatePanel(
    state: UpdateUiState,
    controller: UpdateController,
    force: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val latest = state.latest
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(FreshSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        Text(
            text = if (force) "必须更新后才能继续接单" else (latest?.title ?: "骑手端有新版本"),
            style = MaterialTheme.typography.titleLarge,
            color = RiderColors.Ink,
        )
        Text(
            text = "${latest?.versionName.orEmpty()}（${latest?.versionCode ?: ""}）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = latest?.notes ?: "请更新到最新版本。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = when {
                state.managedMode == ManagedDevice.DEVICE_OWNER -> "本机已纳管，将静默安装。"
                state.accepted || state.downloading -> "已开始下载。关闭弹窗或离开页面也不会中断。"
                else -> "本机需系统确认安装。未上线时会弹窗提醒。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.downloading || state.ready) {
            val total = state.totalBytes.coerceAtLeast(1L)
            LinearProgressIndicator(
                progress = { (state.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = formatBytes(state.downloadedBytes) + " / " + formatBytes(state.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (!state.ready && !state.downloading) {
            MtPrimaryButton(
                text = "立即更新",
                action = MtAction.ACCEPT,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.checking,
                onClick = { controller.acceptUpdate() },
            )
        }
        if (state.downloading) {
            MtPrimaryButton(
                text = "正在下载，无法取消",
                action = MtAction.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
                onClick = {},
            )
        }
        if (state.ready) {
            MtPrimaryButton(
                text = if (state.managedMode == ManagedDevice.DEVICE_OWNER) "立即安装" else "安装（需系统确认）",
                action = MtAction.ACCEPT,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.installing,
                onClick = { scope.launch { controller.install() } },
            )
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    if (value < 1024 * 1024) return "${value / 1024} KB"
    return "${"%.1f".format(value / (1024.0 * 1024.0))} MB"
}
