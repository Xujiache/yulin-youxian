package com.yulin.rider.feature.exception

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtBottomActionBar
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTextField
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.toneColor
import com.yulin.rider.core.designsystem.toneContainer

@Composable
fun ExceptionReportScreen(
    taskId: Long,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
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
        onBack = onBack,
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
    onBack: () -> Unit = {},
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

    MtScaffold(
        title = "遇到问题",
        subtitle = "选择现场情况，获取处理指引",
        modifier = modifier,
        onBack = onBack,
        bottomBar = {
            MtBottomActionBar(
                hint = disabledReason,
                hintTone = if (photoMissing) StatusTone.DANGER else StatusTone.WARNING,
            ) {
                MtPrimaryButton(
                    text = if (state.submitting) "提交中…" else "提交",
                    action = MtAction.DANGER,
                    modifier = Modifier.weight(1f),
                    enabled = selected != null && !photoMissing && !state.submitting,
                    disabledReason = disabledReason,
                    onClick = onSubmit,
                )
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
            MtCard {
                MtSectionTitle("发生了什么？")
                MtDivider()
                ExceptionKind.entries.forEachIndexed { index, kind ->
                    if (index > 0) MtDivider()
                    KindRow(
                        kind = kind,
                        selected = kind == selected,
                        onClick = { onSelect(kind) },
                    )
                }
            }

            if (selected != null) {
                MtCard {
                    MtSectionTitle("现场照片", trailing = {
                        MtTag(
                            if (selected.photoRequired) "必拍" else "可选",
                            tone = when {
                                photoMissing -> StatusTone.DANGER
                                state.photos.isNotEmpty() -> StatusTone.SUCCESS
                                else -> StatusTone.NORMAL
                            },
                        )
                    })
                    MtDivider()
                    if (state.photos.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(FreshSpacing.Sm),
                            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                        ) {
                            state.photos.forEach { path ->
                                PhotoThumb(path) { onRemovePhoto(path) }
                            }
                        }
                    }
                    Box(Modifier.padding(FreshSpacing.Sm)) {
                        MtPrimaryButton(
                            text = if (state.photos.isEmpty()) "拍摄现场照片" else "再拍一张",
                            action = if (photoMissing) MtAction.ACCEPT else MtAction.SECONDARY,
                            modifier = Modifier.fillMaxWidth(),
                            icon = FreshIconType.CAMERA,
                            onClick = onTakePhoto,
                        )
                    }
                }

                MtCard {
                    MtSectionTitle("补充说明")
                    MtDivider()
                    Box(Modifier.padding(FreshSpacing.Sm)) {
                        RiderTextField(
                            value = state.description,
                            onValueChange = onDescriptionChange,
                            label = "描述现场情况",
                            placeholder = "例如：拨打 3 次无人接听",
                            leadingIcon = FreshIconType.INFO,
                            singleLine = false,
                        )
                    }
                }

                MtInfoBar(text = "提交后：${selected.offlineGuidance}", icon = selected.icon)
            }

            state.error?.let {
                MtInfoBar(it, tone = StatusTone.DANGER, icon = FreshIconType.ERROR)
            }
        }
    }
}

/** 问题类型逐行排列。13 类用两列网格会把文案挤成两行，竖排一行一条更好扫。 */
@Composable
private fun KindRow(
    kind: ExceptionKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) StatusTone.DANGER.toneColor() else RiderColors.Ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = kind.label
            }
            .background(if (selected) StatusTone.DANGER.toneContainer() else Color.Transparent)
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshIcon(
            kind.icon,
            contentDescription = null,
            tint = accent,
            size = 20.dp,
        )
        Text(
            text = kind.label,
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            FreshIcon(
                FreshIconType.CHECK,
                contentDescription = null,
                tint = accent,
                size = 18.dp,
            )
        }
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
            .clip(RoundedCornerShape(FreshRadius.Small))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
                Text(
                    text = "已拍摄",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    MtScaffold(
        title = "处理结果",
        subtitle = result.exceptionNo?.let { "异常单号 $it" } ?: "本机离线记录",
        onBack = onClose,
        modifier = modifier,
        bottomBar = {
            MtBottomActionBar {
                MtPrimaryButton(
                    text = "返回任务",
                    action = MtAction.ACCEPT,
                    modifier = Modifier.weight(1f),
                    onClick = onClose,
                )
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
            MtCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                ) {
                    FreshIcon(
                        if (result.queuedOffline) FreshIconType.OFFLINE else FreshIconType.CHECK,
                        contentDescription = null,
                        tint = tone.toneColor(),
                        size = 22.dp,
                    )
                    Text(
                        text = if (result.queuedOffline) "已离线记录" else "已上报",
                        style = MaterialTheme.typography.titleLarge,
                        color = RiderColors.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    MtTag(
                        if (result.queuedOffline) "联网后自动提交" else "调度已收到",
                        tone = tone,
                    )
                }
            }

            MtCard {
                MtSectionTitle("接下来怎么做")
                MtDivider()
                Column(Modifier.padding(FreshSpacing.Sm)) {
                    Text(result.guidance, style = MaterialTheme.typography.bodyLarge)
                    result.holdUntilAt?.let {
                        Box(Modifier.padding(top = FreshSpacing.Xs)) {
                            MtTag("已挂起至 $it", tone = StatusTone.WARNING)
                        }
                    }
                }
            }

            if (result.allowedNextActions.isNotEmpty()) {
                MtCard {
                    MtSectionTitle("可选后续动作")
                    MtDivider()
                    Column(Modifier.padding(FreshSpacing.Sm)) {
                        result.allowedNextActions.forEach {
                            Row(
                                modifier = Modifier.padding(vertical = FreshSpacing.Xxs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                            ) {
                                FreshIcon(
                                    FreshIconType.CHEVRON_RIGHT,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    size = 16.dp,
                                )
                                Text(nextActionLabel(it), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Text(
                            text = "退回与改派由门店调度台操作，请先按上方引导处理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = FreshSpacing.Xs),
                        )
                    }
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
