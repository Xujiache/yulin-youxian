package com.yulin.rider.feature.exception

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtEmptyState
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.EmptyState
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.common.CAMERA_SAVE_FAILED_TEXT
import com.yulin.rider.core.common.CameraPermissionState
import com.yulin.rider.core.common.CameraReliability
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.common.cameraCaptureErrorText
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.network.ApiCaller
import com.yulin.rider.core.network.api.RiderExceptionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * 异常凭证的拍照与上传。
 *
 * 与 feature/task 里的同名实现是有意重复的:feature 之间不允许互相依赖,而这段逻辑
 * 又必须两边都能用。合并的位置应该是 core/designsystem 或新的 core/camera 模块,
 * 属于集成阶段的收口项。
 */
object ExceptionEvidenceUploader {

    private const val MAX_EDGE_PX = 1280
    private const val JPEG_QUALITY = 80

    /** 返回 null 表示这张图没传上去,调用方应转为离线入队而不是继续在线提交。 */
    suspend fun upload(
        api: RiderExceptionApi,
        caller: ApiCaller,
        path: String,
        taskId: Long?,
        location: GeoPoint?,
    ): Long? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType())
        )
        val result = caller.result {
            api.uploadEvidence(
                file = part,
                evidenceType = "EXCEPTION".asTextPart(),
                taskId = taskId?.toString()?.asTextPart(),
                lat = location?.lat?.toString()?.asTextPart(),
                lng = location?.lng?.toString()?.asTextPart(),
                capturedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")).asTextPart(),
            )
        }
        (result as? RiderResult.Success)?.data?.id
    }

    fun persistCompressed(
        context: Context,
        source: ByteArray,
        rotationDegrees: Int = 0,
    ): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { longEdge / it <= MAX_EDGE_PX * 2 }
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: return@runCatching null
        val oriented = bitmap.rotated(rotationDegrees)
        val scaled = oriented.scaledToMaxEdge()
        val dir = File(context.filesDir, "exception-evidence").apply { mkdirs() }
        val target = File(dir, "exception-${System.currentTimeMillis()}.jpg")
        FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== oriented) oriented.recycle()
        if (oriented !== bitmap) bitmap.recycle()
        target.absolutePath
    }.getOrNull()

    private fun Bitmap.scaledToMaxEdge(): Bitmap {
        val longEdge = max(width, height)
        if (longEdge <= MAX_EDGE_PX) return this
        val ratio = MAX_EDGE_PX.toFloat() / longEdge
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
    }

    private fun Bitmap.rotated(rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return this
        return Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(normalized.toFloat()) },
            true,
        )
    }

    private fun String.asTextPart(): RequestBody = toRequestBody("text/plain".toMediaType())
}

@Composable
internal fun ExceptionCamera(
    onCaptured: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    var permissionRequestedBefore by rememberSaveable { mutableStateOf(false) }
    var permissionState by remember(activity) {
        mutableStateOf(
            activity?.let {
                CameraReliability.permissionState(it, permissionRequestedBefore)
            } ?: CameraPermissionState.NO_CAMERA
        )
    }
    var capturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionRequestedBefore = true
        permissionState = activity?.let { current ->
            CameraReliability.permissionState(current, true)
        } ?: CameraPermissionState.NO_CAMERA
    }

    LaunchedEffect(permissionState) {
        if (permissionState == CameraPermissionState.REQUESTABLE && !permissionRequestedBefore) {
            permissionRequestedBefore = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner, activity, permissionRequestedBefore) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) {
                permissionState =
                    CameraReliability.permissionState(activity, permissionRequestedBefore)
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    if (permissionState != CameraPermissionState.GRANTED || lifecycleOwner == null) {
        ExceptionCameraPermissionContent(
            state = permissionState,
            modifier = modifier,
            onRequest = {
                permissionRequestedBefore = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenSettings = {
                activity?.let(CameraReliability::openApplicationSettings)
            },
            onCancel = onCancel,
        )
        return
    }

    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
        )
        MtInfoBar(
            text = "请拍清现场问题与周边环境",
            icon = FreshIconType.CAMERA,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(FreshSpacing.Sm)
                .clip(RoundedCornerShape(FreshRadius.Control)),
        )
        Column(
            // 同送达拍照页:不让开导航栏,「取消」会被切掉一半
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(CONTROL_SCRIM)
                .safeDrawingPadding()
                .padding(FreshSpacing.Md),
        ) {
            captureError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = CAPTURE_ERROR_TEXT,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
            MtPrimaryButton(
                text = if (capturing) "正在保存…" else if (captureError != null) "重新拍照" else "拍照",
                action = MtAction.ACCEPT,
                modifier = Modifier.fillMaxWidth(),
                enabled = !capturing,
                disabledReason = "正在保存照片",
                icon = FreshIconType.CAMERA,
            ) {
                capturing = true
                captureError = null
                controller.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val rotationDegrees = image.imageInfo.rotationDegrees
                            val bytes = image.toJpegBytes()
                            image.close()
                            val path = ExceptionEvidenceUploader.persistCompressed(
                                context,
                                bytes,
                                rotationDegrees,
                            )
                            capturing = false
                            if (path != null) {
                                onCaptured(path)
                            } else {
                                captureError = CAMERA_SAVE_FAILED_TEXT
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            captureError = cameraCaptureErrorText(exception.imageCaptureError)
                        }
                    },
                )
            }
            Spacer(Modifier.height(FreshSpacing.Xs))
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = RiderDimens.TouchTarget),
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ExceptionCameraPermissionContent(
    state: CameraPermissionState,
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    MtScaffold(
        title = "相机权限",
        subtitle = "拍摄异常凭证",
        onBack = onCancel,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(FreshSpacing.Sm),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            MtEmptyState(
                title = when (state) {
                    CameraPermissionState.NO_CAMERA -> "未检测到可用相机"
                    CameraPermissionState.PERMANENTLY_DENIED -> "相机权限已关闭"
                    else -> "需要开启相机权限"
                },
                message = when (state) {
                    CameraPermissionState.NO_CAMERA -> "请更换有相机的设备后再补充异常凭证。"
                    CameraPermissionState.PERMANENTLY_DENIED ->
                        "请到系统应用设置中允许相机权限，再返回本页拍摄凭证。"
                    else -> "异常照片用于说明现场情况与后续调度处理。"
                },
                icon = FreshIconType.CAMERA,
            )
            if (state != CameraPermissionState.NO_CAMERA) {
                MtPrimaryButton(
                    text = if (state == CameraPermissionState.PERMANENTLY_DENIED) {
                        "打开系统设置"
                    } else {
                        "开启相机权限"
                    },
                    action = MtAction.ACCEPT,
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (state == CameraPermissionState.PERMANENTLY_DENIED) {
                        FreshIconType.SETTINGS
                    } else {
                        FreshIconType.CAMERA
                    },
                    onClick = if (state == CameraPermissionState.PERMANENTLY_DENIED) {
                        onOpenSettings
                    } else {
                        onRequest
                    },
                )
                Spacer(Modifier.height(FreshSpacing.Xs))
            }
            MtPrimaryButton(
                text = "返回异常页",
                action = MtAction.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel,
            )
        }
    }
}

private val CONTROL_SCRIM = Color.Black.copy(alpha = 0.55f)

/** 报错文字压在黑色取景画面上，用比标准错误红更亮的一档才读得清。 */
private val CAPTURE_ERROR_TEXT = Color(0xFFFF7875)

private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is LifecycleOwner) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}

@Preview(name = "异常相机 · 权限缺失", showBackground = true)
@Composable
private fun ExceptionCameraPermissionPreview() {
    RiderTheme {
        ExceptionCameraPermissionContent(
            state = CameraPermissionState.PERMANENTLY_DENIED,
            onRequest = {},
            onOpenSettings = {},
            onCancel = {},
        )
    }
}
