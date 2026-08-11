package com.yulin.rider.feature.exception

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshBottomActionBar
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderTextField
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone

@Composable
fun ExceptionReportScreen(
    taskId: Long,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
) {
    val viewModel: ExceptionReportViewModel = viewModel(
        key = "exception-$taskId",
        factory = ExceptionReportViewModel.factory(taskId),
    )
    val state by viewModel.state.collectAsState()
    var cameraOpen by remember { mutableStateOf(false) }

    state.result?.let { result ->
        ExceptionResultView(result, modifier, onClose)
        return
    }
    if (cameraOpen) {
        ExceptionCamera(
            onCaptured = {
                viewModel.addPhoto(it)
                cameraOpen = false
            },
            onCancel = { cameraOpen = false },
            modifier = modifier,
        )
        return
    }

    ExceptionReportContent(
        state = state,
        modifier = modifier,
        onSelect = viewModel::select,
        onTakePhoto = { cameraOpen = true },
        onRemovePhoto = viewModel::removePhoto,
        onDescriptionChange = viewModel::updateDescription,
        onSubmit = viewModel::submit,
    )
}

@Composable
private fun ExceptionReportContent(
    state: ExceptionReportUiState,
    modifier: Modifier = Modifier,
    onSelect: (ExceptionKind) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onRemovePhoto: (String) -> Unit = {},
    onDescriptionChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val selected = state.selected
    val photoMissing = selected?.photoRequired == true && state.photos.isEmpty()
    val disabledReason = when {
        selected == null -> "请先选择发生的问题"
        photoMissing -> "这类异常必须拍照留证"
        state.submitting -> "正在提交异常"
        else -> null
    }

    FreshStackScaffold(
        title = "上报异常",
        subtitle = "选择现场情况，获取处理指引",
        modifier = modifier,
        bottomBar = {
            FreshBottomActionBar(
                reason = disabledReason,
                reasonTone = if (photoMissing) StatusTone.DANGER else StatusTone.WARNING,
            ) {
                BigActionButton(
                    text = if (state.submitting) "提交中…" else "提交异常",
                    enabled = selected != null && !photoMissing && !state.submitting,
                    disabledReason = disabledReason,
                    tone = StatusTone.DANGER,
                    icon = FreshIconType.WARNING,
                    onClick = onSubmit,
                )
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
            FreshPanel(
                title = "发生了什么？",
                eyebrow = "13 类常见现场情况",
                spineTone = if (selected == null) StatusTone.WARNING else StatusTone.DANGER,
            ) {
                ExceptionKindGrid(selected, onSelect)
            }

            if (selected != null) {
                FreshPanel(
                    title = "现场照片",
                    eyebrow = if (selected.photoRequired) "必拍" else "可选",
                    spineTone = when {
                        selected.photoRequired && state.photos.isEmpty() -> StatusTone.DANGER
                        state.photos.isNotEmpty() -> StatusTone.SUCCESS
                        else -> StatusTone.NORMAL
                    },
                ) {
                    if (state.photos.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
                            state.photos.forEach { path ->
                                PhotoThumb(path) { onRemovePhoto(path) }
                            }
                        }
                    }
                    BigActionButton(
                        text = if (state.photos.isEmpty()) "拍摄现场照片" else "再拍一张",
                        tone = if (photoMissing) StatusTone.WARNING else StatusTone.NORMAL,
                        icon = FreshIconType.CAMERA,
                        onClick = onTakePhoto,
                    )
                }

                FreshPanel(title = "补充说明") {
                    RiderTextField(
                        value = state.description,
                        onValueChange = onDescriptionChange,
                        label = "描述现场情况",
                        placeholder = "例如：拨打 3 次无人接听",
                        leadingIcon = FreshIconType.INFO,
                        singleLine = false,
                    )
                }

                FreshBanner(
                    text = "提交后：${selected.offlineGuidance}",
                    tone = StatusTone.INFO,
                    icon = selected.icon,
                )
            }

            state.error?.let {
                FreshBanner(it, tone = StatusTone.DANGER, icon = FreshIconType.ERROR)
            }
        }
    }
}

@Composable
private fun ExceptionKindGrid(
    selected: ExceptionKind?,
    onSelect: (ExceptionKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
        ExceptionKind.entries.chunked(2).forEach { kinds ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                kinds.forEach { kind ->
                    KindCell(
                        kind = kind,
                        selected = kind == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(kind) },
                    )
                }
                if (kinds.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KindCell(
    kind: ExceptionKind,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(com.yulin.rider.core.designsystem.FreshRadius.Control))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = kind.label
            }
            .padding(FreshSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        FreshIcon(
            kind.icon,
            contentDescription = null,
            tint = color,
            size = 28.dp,
        )
        Text(
            kind.label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhotoThumb(path: String, onRemove: () -> Unit) {
    val bitmap = remember(path) {
        runCatching {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(com.yulin.rider.core.designsystem.FreshRadius.Small))
            .clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "异常凭证，点按删除" },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FreshIcon(
                    FreshIconType.CAMERA,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("已拍摄", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ExceptionResultView(
    result: ExceptionResult,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val tone = if (result.queuedOffline) StatusTone.WARNING else StatusTone.SUCCESS
    FreshStackScaffold(
        title = "异常处理结果",
        subtitle = result.exceptionNo?.let { "异常单号 $it" } ?: "本机离线记录",
        onBack = onClose,
        modifier = modifier,
        bottomBar = {
            FreshBottomActionBar {
                BigActionButton(
                    text = "返回任务",
                    icon = FreshIconType.BACK,
                    onClick = onClose,
                )
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
            FreshPanel(
                title = if (result.queuedOffline) "已离线记录" else "已上报",
                eyebrow = "异常结果",
                spineTone = tone,
            ) {
                FreshStatusBadge(
                    text = if (result.queuedOffline) "联网后自动提交" else "调度已收到",
                    tone = tone,
                    icon = if (result.queuedOffline) FreshIconType.OFFLINE else FreshIconType.CHECK,
                )
            }

            FreshPanel(
                title = "接下来怎么做",
                spineTone = StatusTone.INFO,
            ) {
                Text(result.guidance, style = MaterialTheme.typography.bodyLarge)
                result.holdUntilAt?.let {
                    FreshStatusBadge("已挂起至 $it", tone = StatusTone.WARNING)
                }
            }

            if (result.allowedNextActions.isNotEmpty()) {
                FreshPanel(title = "可选后续动作") {
                    result.allowedNextActions.forEach {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                        ) {
                            FreshIcon(
                                FreshIconType.CHEVRON_RIGHT,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Text(nextActionLabel(it), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        "退回与改派由门店调度台操作，请先按上方引导处理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(name = "异常 · 选择类型", showBackground = true)
@Composable
private fun ExceptionReportPreview() {
    RiderTheme {
        ExceptionReportContent(
            ExceptionReportUiState(selected = ExceptionKind.GOODS_DAMAGED)
        )
    }
}

@Preview(name = "异常 · 错误", showBackground = true)
@Composable
private fun ExceptionErrorPreview() {
    RiderTheme {
        ExceptionReportContent(
            ExceptionReportUiState(error = "当前网络不可用，稍后可重试")
        )
    }
}

@Preview(name = "异常 · 离线结果", showBackground = true)
@Composable
private fun ExceptionResultPreview() {
    RiderTheme {
        ExceptionResultView(
            result = ExceptionResult(
                exceptionNo = null,
                guidance = "电话与短信各联系两次，间隔等待后再按门店指引处理。",
                allowedNextActions = emptyList(),
                holdUntilAt = null,
                queuedOffline = true,
            ),
            onClose = {},
        )
    }
}
