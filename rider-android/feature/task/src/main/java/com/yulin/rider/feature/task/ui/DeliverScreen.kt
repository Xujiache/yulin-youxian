package com.yulin.rider.feature.task.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
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
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTextField
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.feature.task.data.ReceiveMethod
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.ui.camera.CameraCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DeliverViewModel(
    app: Application,
    private val taskId: Long,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {
    private val repository = TaskRepository.get(app)
    private val _task = MutableStateFlow<TaskCardUi?>(null)
    val task: StateFlow<TaskCardUi?> = _task
    val photos: StateFlow<ArrayList<String>> =
        savedStateHandle.getStateFlow(KEY_PHOTOS, arrayListOf())
    val cameraOpen: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_CAMERA_OPEN, false)
    val methodName: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_METHOD, ReceiveMethod.FACE_TO_FACE.name)
    val verifyCode: StateFlow<String> = savedStateHandle.getStateFlow(KEY_VERIFY_CODE, "")
    val infoSettled: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_INFO_SETTLED, false)

    init {
        viewModelScope.launch { repository.observeTask(taskId).collect { _task.value = it } }
    }

    fun openCamera(open: Boolean) {
        savedStateHandle[KEY_CAMERA_OPEN] = open
    }

    fun addPhoto(path: String) {
        savedStateHandle[KEY_PHOTOS] = ArrayList(photos.value + path)
    }

    fun setMethod(method: ReceiveMethod) {
        savedStateHandle[KEY_METHOD] = method.name
    }

    fun setVerifyCode(value: String) {
        savedStateHandle[KEY_VERIFY_CODE] = value
    }

    fun setInfoSettled(value: Boolean) {
        savedStateHandle[KEY_INFO_SETTLED] = value
    }

    fun deliver(photos: List<String>, method: ReceiveMethod, verifyCode: String?, onDone: () -> Unit) =
        viewModelScope.launch {
            repository.deliver(taskId, photos, method, verifyCode)
            savedStateHandle[KEY_PHOTOS] = arrayListOf<String>()
            savedStateHandle[KEY_VERIFY_CODE] = ""
            savedStateHandle[KEY_CAMERA_OPEN] = false
            onDone()
        }

    private companion object {
        const val KEY_PHOTOS = "deliver.photos"
        const val KEY_CAMERA_OPEN = "deliver.cameraOpen"
        const val KEY_METHOD = "deliver.method"
        const val KEY_VERIFY_CODE = "deliver.verifyCode"
        const val KEY_INFO_SETTLED = "deliver.infoSettled"
    }
}

@Composable
fun DeliverScreen(
    taskId: Long,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val viewModel: DeliverViewModel = viewModel(
        key = "deliver-$taskId",
        factory = deliverFactory(taskId),
    )
    val task by viewModel.task.collectAsState()
    val photoDraft by viewModel.photos.collectAsState()
    val photos: List<String> = photoDraft
    val cameraOpen by viewModel.cameraOpen.collectAsState()
    val methodName by viewModel.methodName.collectAsState()
    val method = runCatching { ReceiveMethod.valueOf(methodName) }
        .getOrDefault(ReceiveMethod.FACE_TO_FACE)
    val verifyCode by viewModel.verifyCode.collectAsState()
    val infoSettled by viewModel.infoSettled.collectAsState()

    val current = task
    if (current == null) {
        FreshStackScaffold(title = "确认送达", modifier = modifier) { insets ->
            FreshEmpty(
                title = "任务尚未同步",
                message = "返回首页刷新后再试",
                icon = FreshIconType.DELIVERY,
                modifier = Modifier.padding(insets).fillMaxSize(),
            )
        }
        return
    }

    LaunchedEffect(current.taskId) {
        viewModel.setInfoSettled(false)
        delay(800)
        viewModel.setInfoSettled(true)
    }
    LaunchedEffect(current.card.deliveryInstruction, method) {
        if (method == ReceiveMethod.FACE_TO_FACE &&
            current.card.deliveryInstruction?.contains("门口") == true
        ) {
            viewModel.setMethod(ReceiveMethod.DOOR)
        }
    }

    if (cameraOpen) {
        CameraCapture(
            hint = "拍到门牌或货品，确保凭证清晰",
            onCaptured = { path ->
                viewModel.addPhoto(path)
                viewModel.openCamera(false)
            },
            onCancel = { viewModel.openCamera(false) },
            modifier = modifier,
        )
        return
    }

    DeliverContent(
        task = current,
        photos = photos,
        method = method,
        verifyCode = verifyCode,
        infoSettled = infoSettled,
        modifier = modifier,
        onBack = onBack,
        onTakePhoto = { viewModel.openCamera(true) },
        onMethodChange = viewModel::setMethod,
        onVerifyCodeChange = viewModel::setVerifyCode,
        onConfirm = { viewModel.deliver(photos, method, verifyCode, onDone) },
    )
}

@Composable
private fun DeliverContent(
    task: TaskCardUi,
    photos: List<String>,
    method: ReceiveMethod,
    verifyCode: String,
    infoSettled: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onMethodChange: (ReceiveMethod) -> Unit = {},
    onVerifyCodeChange: (String) -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val codeOk = !task.card.requireVerifyCode || verifyCode.isNotBlank()
    val canConfirm = photos.isNotEmpty() && infoSettled && codeOk
    val disabledReason = when {
        photos.isEmpty() -> "未拍摄送达凭证，无法确认送达"
        !codeOk -> "请填写顾客核销码"
        !infoSettled -> "请先核对地址、楼层与件数"
        else -> null
    }

    MtScaffold(
        title = "确认送达",
        subtitle = task.card.taskNo,
        modifier = modifier,
        onBack = onBack,
        bottomBar = {
            MtBottomActionBar(
                hint = disabledReason,
                hintTone = if (photos.isEmpty()) StatusTone.DANGER else StatusTone.WARNING,
            ) {
                MtPrimaryButton(
                    text = "我已送达",
                    action = MtAction.DELIVER,
                    modifier = Modifier.weight(1f),
                    enabled = canConfirm,
                    disabledReason = disabledReason,
                    onClick = onConfirm,
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
            KeyInfoBlock(task)

            MtCard {
                MtSectionTitle("送达凭证", trailing = {
                    MtTag(
                        if (photos.isEmpty()) "必拍" else "${photos.size} 张",
                        tone = if (photos.isEmpty()) StatusTone.DANGER else StatusTone.SUCCESS,
                    )
                })
                MtDivider()
                if (photos.isEmpty()) {
                    Text(
                        text = "照片需要包含门牌或货品，用于订单争议回查。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(FreshSpacing.Sm),
                    )
                } else {
                    Row(
                        modifier = Modifier.padding(FreshSpacing.Sm),
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                    ) {
                        photos.forEach { path -> PhotoThumbnail(path) }
                    }
                }
                Box(Modifier.padding(FreshSpacing.Sm)) {
                    MtPrimaryButton(
                        text = if (photos.isEmpty()) "拍摄送达凭证" else "再拍一张",
                        action = if (photos.isEmpty()) MtAction.ACCEPT else MtAction.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                        icon = FreshIconType.CAMERA,
                        onClick = onTakePhoto,
                    )
                }
            }

            MtCard {
                MtSectionTitle("送达方式")
                MtDivider()
                Column(Modifier.padding(FreshSpacing.Sm)) {
                    ReceiveMethod.entries.chunked(2).forEach { options ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = FreshSpacing.Xs),
                            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                        ) {
                            options.forEach { option ->
                                val selected = option == method
                                FilterChip(
                                    selected = selected,
                                    onClick = { onMethodChange(option) },
                                    label = {
                                        Text(
                                            option.label,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    },
                                    shape = RoundedCornerShape(FreshRadius.Control),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RiderColors.PrimaryContainer,
                                        selectedLabelColor = RiderColors.Ink,
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = RiderDimens.TouchTarget),
                                )
                            }
                            if (options.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (task.card.requireVerifyCode) {
                MtCard {
                    MtSectionTitle("顾客核销码", trailing = {
                        MtTag(
                            if (codeOk) "已填写" else "待填写",
                            tone = if (codeOk) StatusTone.SUCCESS else StatusTone.WARNING,
                        )
                    })
                    MtDivider()
                    Box(Modifier.padding(FreshSpacing.Sm)) {
                        RiderTextField(
                            value = verifyCode,
                            onValueChange = onVerifyCodeChange,
                            label = "请顾客出示核销码",
                            leadingIcon = FreshIconType.LOCK,
                        )
                    }
                }
            }
        }
    }
}

/** 送达前复核：楼层门牌放到最大，这是骑手站在楼下唯一要确认的东西。 */
@Composable
private fun KeyInfoBlock(task: TaskCardUi) {
    val card = task.card
    MtCard {
        Column(Modifier.padding(FreshSpacing.Sm)) {
            Text(
                text = card.addressDetail ?: card.areaLabel ?: "地址待补充",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.floorNo?.let {
                Text(
                    text = "$it 楼 ${card.roomNo ?: ""}".trim(),
                    style = MaterialTheme.typography.displaySmall.tabularFigures(),
                    color = RiderColors.Deliver,
                )
            }
            Text(
                text = "${card.receiverName ?: "顾客"} · ${card.itemCount} 件",
                style = MaterialTheme.typography.titleMedium.tabularFigures(),
                color = RiderColors.Ink,
            )
        }
        card.customerRemark?.takeIf { it.isNotBlank() }?.let {
            MtInfoBar(text = "顾客备注：$it", tone = StatusTone.WARNING)
        }
    }
}

@Composable
private fun PhotoThumbnail(path: String) {
    val bitmap = remember(path) {
        runCatching {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(FreshRadius.Small))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "送达凭证",
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

private fun deliverFactory(taskId: Long) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        return DeliverViewModel(app, taskId, extras.createSavedStateHandle()) as T
    }
}

@Preview(name = "送达 · 未拍照禁用", showBackground = true)
@Composable
private fun DeliverDisabledPreview() {
    RiderTheme {
        DeliverContent(
            task = previewTask(),
            photos = emptyList(),
            method = ReceiveMethod.FACE_TO_FACE,
            verifyCode = "",
            infoSettled = true,
        )
    }
}

@Preview(name = "送达 · 已拍照", showBackground = true)
@Composable
private fun DeliverReadyPreview() {
    RiderTheme {
        DeliverContent(
            task = previewTask(),
            photos = listOf("preview.jpg"),
            method = ReceiveMethod.DOOR,
            verifyCode = "",
            infoSettled = true,
        )
    }
}
