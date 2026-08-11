package com.yulin.rider.core.common

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class CameraPermissionState {
    GRANTED,
    REQUESTABLE,
    PERMANENTLY_DENIED,
    NO_CAMERA,
}

/** CameraX Composable 共用的非 UI 判定；A8 负责根据状态展示授权或系统设置入口。 */
object CameraReliability {

    fun permissionState(activity: Activity, permissionRequestedBefore: Boolean): CameraPermissionState {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return CameraPermissionState.NO_CAMERA
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return CameraPermissionState.GRANTED
        }
        return if (permissionRequestedBefore &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        ) {
            CameraPermissionState.PERMANENTLY_DENIED
        } else {
            CameraPermissionState.REQUESTABLE
        }
    }

    fun openApplicationSettings(activity: Activity): Boolean = runCatching {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            )
        )
        true
    }.getOrDefault(false)
}
