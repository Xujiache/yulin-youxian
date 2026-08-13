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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
        onReselect = viewModel::clearSelection,
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
    onReselect: () -> Unit = {},
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
            if (selected == null) {
                KindPicker(onSelect = onSelect)
            } else {
                SelectedKindCard(kind = selected, onReselect = onReselect)
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

/**
 * 分组折叠的类型选择器。
 *
 * 13 类平铺要占满整屏,骑手得滚动着找。两列网格试过不行 ——
 * 「缺斤少两争议」这种六字标签在放大字号下会挤成两行,反而更难扫。
 * 所以按「谁的问题」分三组,一次只展开一组,收起时只有四行。
 *
 * 「顾客与地址」默认展开:生鲜最后一公里里,联系不上和门禁进不去占了绝大多数,
 * 让最常见的情况保持一次点击,别为了省长度把所有人都变成两次点击。
 */
@Composable
private fun KindPicker(onSelect: (ExceptionKind) -> Unit) {
    var expanded by remember { mutableStateOf<ExceptionGroup?>(ExceptionGroup.CUSTOMER) }

    MtCard {
        MtSectionTitle("发生了什么？")
        ExceptionGroup.entries.forEach { group ->
            MtDivider()
            // 「其他」只有一项,套个折叠纯属多让骑手点一下
            if (group.kinds.size == 1) {
                KindRow(kind = group.kinds.first(), onClick = { onSelect(group.kinds.first()) })
                return@forEach
            }
            val open = expanded == group
            GroupRow(group = group, expanded = open) {
                expanded = if (open) null else group
            }
            if (open) {
                group.kinds.forEach { kind ->
                    KindRow(kind = kind, indented = true, onClick = { onSelect(kind) })
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: ExceptionGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = if (expanded) {
                    "${group.label}，已展开，点按收起"
                } else {
                    "${group.label}，包含${group.summary}，点按展开"
                }
            }
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshIcon(group.icon, contentDescription = null, tint = RiderColors.Ink, size = 20.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = group.label,
                style = MaterialTheme.typography.titleSmall,
                color = RiderColors.Ink,
            )
            // 收起时也要看得出里面装了什么，否则只能靠猜着点开
            if (!expanded) {
                Text(
                    text = group.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FreshIcon(
            if (expanded) FreshIconType.CHEVRON_DOWN else FreshIconType.CHEVRON_RIGHT,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
    }
}

/** 组内的一条问题。竖排一行一条,比两列网格好扫。 */
@Composable
private fun KindRow(
    kind: ExceptionKind,
    onClick: () -> Unit,
    indented: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                selected = false
                contentDescription = kind.label
            }
            .padding(
                start = if (indented) FreshSpacing.Xl else FreshSpacing.Sm,
                end = FreshSpacing.Sm,
                top = FreshSpacing.Sm,
                bottom = FreshSpacing.Sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshIcon(kind.icon, contentDescription = null, tint = RiderColors.Ink, size = 20.dp)
        Text(
            text = kind.label,
            style = MaterialTheme.typography.bodyLarge,
            color = RiderColors.Ink,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 选定之后整个列表收成一行。
 *
 * 选完类型就不需要再看另外 12 个了,把它们留在屏幕上只会把拍照和补充说明顶到屏幕外。
 */
@Composable
private fun SelectedKindCard(kind: ExceptionKind, onReselect: () -> Unit) {
    val accent = StatusTone.DANGER.toneColor()
    MtCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onReselect)
                .background(StatusTone.DANGER.toneContainer())
                .semantics { contentDescription = "已选择${kind.label}，点按重新选择" }
                .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            FreshIcon(kind.icon, contentDescription = null, tint = accent, size = 20.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = kind.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
                Text(
                    text = kind.group.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.75f),
                )
            }
            MtTag("重选", tone = StatusTone.DANGER)
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
private fun ExceptionPickerPreview() {
    RiderTheme { ExceptionReportContent(ExceptionReportUiState()) }
}

@Preview(name = "异常 · 已选中", showBackground = true)
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
