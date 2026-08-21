package com.yulin.rider.core.common

/**
 * CameraX 拍照失败文案。
 *
 * 取 Int 错误码而不是 ImageCaptureException,是为了不把 camera-core 依赖带进 core 模块;
 * 调用方传 `exception.imageCaptureError` 即可。
 *
 * 送达强制拍照,拍不成就交不了差,所以每条文案都要给出骑手能照着做的下一步。
 */
fun cameraCaptureErrorText(errorCode: Int): String = when (errorCode) {
    ERROR_CAMERA_CLOSED -> "相机被其他应用占用，请关掉正在用相机的应用后重试"
    ERROR_FILE_IO -> "照片保存失败，可能是手机存储空间不足，请清理后重试"
    ERROR_INVALID_CAMERA -> "相机不可用，请重启手机后重试"
    ERROR_CAPTURE_FAILED -> "拍照失败，请擦一下镜头、对准包裹再试一次"
    else -> "拍照失败，请重试；若一直失败请联系店长（错误码 $errorCode）"
}

/** 照片已拍成但落盘失败,和 CameraX 的错误码无关,单独给一条。 */
const val CAMERA_SAVE_FAILED_TEXT = "照片保存失败，可能是手机存储空间不足，请清理后重试"

// 与 androidx.camera.core.ImageCapture 的常量保持一致
private const val ERROR_FILE_IO = 1
private const val ERROR_CAPTURE_FAILED = 2
private const val ERROR_CAMERA_CLOSED = 3
private const val ERROR_INVALID_CAMERA = 4
