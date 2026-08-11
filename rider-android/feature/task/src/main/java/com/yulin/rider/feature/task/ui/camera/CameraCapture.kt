package com.yulin.rider.feature.task.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yulin.rider.core.common.CAMERA_SAVE_FAILED_TEXT
import com.yulin.rider.core.common.CameraPermissionState
import com.yulin.rider.core.common.CameraReliability
import com.yulin.rider.core.common.cameraCaptureErrorText
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.EmptyState
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.feature.task.data.EvidenceUploader

/**
 * 拍照(06 §4 camera 路由)。
 *
 * 用 CameraX 而不是系统相机 Intent:系统相机要配 FileProvider,而且各家 ROM 的相机
 * 界面按钮大小不一;骑手戴手套单手操作,快门必须是屏幕下方一整条的大目标。
 *
 * [onCaptured] 回调里给的是压缩落盘后的本地路径,断网时也拿得到。
 */
@Composable
fun CameraCapture(
    hint: String,
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
    // 送达强制拍照,拍失败必须说清楚原因,否则骑手会一直点快门卡在这一步
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
        CameraPermissionContent(
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

        FreshBanner(
            text = hint,
            tone = StatusTone.INFO,
            icon = FreshIconType.CAMERA,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(FreshSpacing.Md),
        )

        Column(
            // 压条要盖到导航栏底下(背景在前、让位在后),否则「取消」会被导航栏切掉一半;
            // 取景画面是全黑到全白都可能,提示文字必须自带底色才读得清
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(CONTROL_SCRIM)
                .safeDrawingPadding()
                .padding(FreshSpacing.Md),
        ) {
            Text(
                text = captureError ?: hint,
                style = MaterialTheme.typography.titleMedium,
                color = if (captureError != null) ERROR_TEXT else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            BigActionButton(
                text = if (capturing) "正在保存…" else if (captureError != null) "重新拍照" else "拍照",
                modifier = Modifier.fillMaxWidth(),
                enabled = !capturing,
                tone = StatusTone.SUCCESS,
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
                            val path = EvidenceUploader.persistCompressed(
                                context,
                                bytes,
                                "evidence",
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
            Spacer(Modifier.height(12.dp))
            BigActionButton(
                text = "取消",
                modifier = Modifier.fillMaxWidth(),
                tone = StatusTone.NORMAL,
                icon = FreshIconType.CLOSE,
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun CameraPermissionContent(
    state: CameraPermissionState,
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    FreshStackScaffold(
        title = "相机权限",
        subtitle = "拍摄送达凭证",
        onBack = onCancel,
        modifier = modifier,
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(FreshSpacing.Md),
            verticalArrangement = Arrangement.Center,
        ) {
            FreshEmpty(
                title = when (state) {
                    CameraPermissionState.NO_CAMERA -> "未检测到可用相机"
                    CameraPermissionState.PERMANENTLY_DENIED -> "相机权限已关闭"
                    else -> "需要开启相机权限"
                },
                message = when (state) {
                    CameraPermissionState.NO_CAMERA -> "请更换有相机的设备后再完成送达。"
                    CameraPermissionState.PERMANENTLY_DENIED ->
                        "请到系统应用设置中允许相机权限，再返回本页拍摄凭证。"
                    else -> "送达前必须拍摄门牌或货品，凭证只用于配送回查。"
                },
                icon = FreshIconType.CAMERA,
            )
            if (state != CameraPermissionState.NO_CAMERA) {
                BigActionButton(
                    text = if (state == CameraPermissionState.PERMANENTLY_DENIED) {
                        "打开系统设置"
                    } else {
                        "开启相机权限"
                    },
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
                Spacer(Modifier.height(FreshSpacing.Sm))
            }
            BigActionButton(
                text = "返回任务",
                tone = StatusTone.NORMAL,
                icon = FreshIconType.BACK,
                onClick = onCancel,
            )
        }
    }
}

private val CONTROL_SCRIM = Color.Black.copy(alpha = 0.55f)
private val ERROR_TEXT = RiderColors.DangerBright

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

@Preview(name = "相机 · 权限缺失", showBackground = true)
@Composable
private fun CameraPermissionPreview() {
    RiderTheme {
        CameraPermissionContent(
            state = CameraPermissionState.PERMANENTLY_DENIED,
            onRequest = {},
            onOpenSettings = {},
            onCancel = {},
        )
    }
}
