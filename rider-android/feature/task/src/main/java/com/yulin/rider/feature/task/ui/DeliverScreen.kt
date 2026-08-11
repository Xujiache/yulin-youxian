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
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.FreshBottomActionBar
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTextField
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SlideToConfirm
import com.yulin.rider.core.designsystem.StatusTone
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

    FreshStackScaffold(
        title = "确认送达",
        subtitle = task.card.taskNo,
        modifier = modifier,
        bottomBar = {
            FreshBottomActionBar(
                reason = disabledReason,
                reasonTone = if (photos.isEmpty()) StatusTone.DANGER else StatusTone.WARNING,
            ) {
                SlideToConfirm(
                    text = "滑动确认已送达",
                    enabled = canConfirm,
                    disabledReason = disabledReason,
                    tone = StatusTone.SUCCESS,
                    onConfirm = onConfirm,
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
            KeyInfoBlock(task)

            FreshPanel(
                title = "送达凭证",
                eyebrow = "必拍",
                spineTone = if (photos.isEmpty()) StatusTone.DANGER else StatusTone.SUCCESS,
            ) {
                if (photos.isEmpty()) {
                    FreshStatusBadge(
                        text = "尚未拍照",
                        tone = StatusTone.DANGER,
                        icon = FreshIconType.CAMERA,
                    )
                    Text(
                        "照片需要包含门牌或货品，用于订单争议回查。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
                        photos.forEach { path -> PhotoThumbnail(path) }
                    }
                }
                BigActionButton(
                    text = if (photos.isEmpty()) "拍摄送达凭证" else "再拍一张",
                    tone = if (photos.isEmpty()) StatusTone.WARNING else StatusTone.NORMAL,
                    icon = FreshIconType.CAMERA,
                    onClick = onTakePhoto,
                )
            }

            FreshPanel(title = "送达方式", spineTone = StatusTone.INFO) {
                ReceiveMethod.entries.chunked(2).forEach { options ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option == method,
                                onClick = { onMethodChange(option) },
                                label = {
                                    Text(option.label, style = MaterialTheme.typography.labelLarge)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = RiderDimens.TouchTarget),
                            )
                        }
                        if (options.size == 1) {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (task.card.requireVerifyCode) {
                FreshPanel(
                    title = "顾客核销码",
                    spineTone = if (codeOk) StatusTone.SUCCESS else StatusTone.WARNING,
                ) {
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

@Composable
private fun KeyInfoBlock(task: TaskCardUi) {
    val card = task.card
    FreshPanel(
        title = card.addressDetail ?: card.areaLabel ?: "地址待补充",
        eyebrow = "送达前复核",
        spineTone = when {
            card.overtimeRisk == "OVERTIME" -> StatusTone.DANGER
            card.overtimeRisk in setOf("HIGH", "MEDIUM") -> StatusTone.WARNING
            card.coldChainLevel in setOf("FROZEN", "CHILLED") -> StatusTone.COLD
            else -> StatusTone.SUCCESS
        },
    ) {
        card.floorNo?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                FreshIcon(
                    FreshIconType.LOCATION,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "$it 楼 ${card.roomNo ?: ""}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            "${card.receiverName ?: "顾客"} · ${card.itemCount} 件",
            style = MaterialTheme.typography.titleMedium,
        )
        card.customerRemark?.takeIf { it.isNotBlank() }?.let {
            FreshStatusBadge(
                text = "备注：$it",
                tone = StatusTone.WARNING,
                icon = FreshIconType.WARNING,
            )
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
            .size(96.dp)
            .clip(RoundedCornerShape(com.yulin.rider.core.designsystem.FreshRadius.Small))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    tint = RiderColors.Primary,
                )
                Text("已拍摄", style = MaterialTheme.typography.labelMedium)
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
