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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtEmptyState
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
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

    val scope = rememberCoroutineScope()
    val controller = remember { LifecycleCameraController(context) }
    var bindFailed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        // 绑定失败在组合期抛出会直接崩掉整个 App。模拟器缺后置摄像头、
        // CameraX 初始化失败都会走到这里，降级成一屏说明总比闪退强。
        runCatching { controller.bindToLifecycle(lifecycleOwner) }
            .onFailure { bindFailed = true }
        onDispose { runCatching { controller.unbind() } }
    }

    if (bindFailed) {
        CameraPermissionContent(
            state = CameraPermissionState.NO_CAMERA,
            modifier = modifier,
            onRequest = {},
            onOpenSettings = {},
            onCancel = onCancel,
        )
        return
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
            text = hint,
            icon = FreshIconType.CAMERA,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(FreshSpacing.Sm)
                .clip(RoundedCornerShape(FreshRadius.Control)),
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
                            // 读缓冲区可能抛异常，close 必须在 finally 里：
                            // 漏关一次 ImageProxy 就会占住相机缓冲，第二张直接拍不出来
                            val bytes = try {
                                image.toJpegBytes()
                            } catch (_: RuntimeException) {
                                null
                            } finally {
                                image.close()
                            }
                            if (bytes == null) {
                                capturing = false
                                captureError = CAMERA_SAVE_FAILED_TEXT
                                return
                            }
                            // 解码、旋转、压缩、落盘全是重活，放主线程上高分辨率照片能卡出 ANR
                            scope.launch {
                                val path = withContext(Dispatchers.IO) {
                                    runCatching {
                                        EvidenceUploader.persistCompressed(
                                            context,
                                            bytes,
                                            "evidence",
                                            rotationDegrees,
                                        )
                                    }.getOrNull()
                                }
                                capturing = false
                                if (path != null) {
                                    onCaptured(path)
                                } else {
                                    captureError = CAMERA_SAVE_FAILED_TEXT
                                }
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
            // 取消不给填充按钮：黑色取景画面上再放一块白底会抢走快门的位置
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
private fun CameraPermissionContent(
    state: CameraPermissionState,
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    MtScaffold(
        title = "相机权限",
        subtitle = "拍摄送达凭证",
        onBack = onCancel,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(FreshSpacing.Sm),
            verticalArrangement = Arrangement.Center,
        ) {
            MtEmptyState(
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
                text = "返回任务",
                action = MtAction.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel,
            )
        }
    }
}

private val CONTROL_SCRIM = Color.Black.copy(alpha = 0.55f)

/** 报错文字压在黑色取景画面上，用比标准错误红更亮的一档才读得清。 */
private val ERROR_TEXT = Color(0xFFFF7875)

/** 取不到平面就返回 null 而不是越界崩溃 —— 少数 ROM 在切后台瞬间会给出空 planes。 */
private fun ImageProxy.toJpegBytes(): ByteArray? {
    val buffer = planes.firstOrNull()?.buffer ?: return null
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
