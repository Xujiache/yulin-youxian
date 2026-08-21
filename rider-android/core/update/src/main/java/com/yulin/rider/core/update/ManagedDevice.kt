package com.yulin.rider.core.update

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object ManagedDevice {
    const val DEVICE_OWNER = "DEVICE_OWNER"
    const val PROFILE_OWNER = "PROFILE_OWNER"
    const val STANDARD = "STANDARD"

    fun mode(context: Context): String {
        val manager = ContextCompat.getSystemService(context, DevicePolicyManager::class.java)
            ?: return STANDARD
        val packageName = context.packageName
        return when {
            manager.isDeviceOwnerApp(packageName) -> DEVICE_OWNER
            manager.isProfileOwnerApp(packageName) -> PROFILE_OWNER
            else -> STANDARD
        }
    }

    fun canSilentInstall(context: Context): Boolean = mode(context) == DEVICE_OWNER

    fun currentVersionCode(context: Context): Int {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    fun currentSigningSha256(context: Context): String? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        } ?: return null
        val first = signatures.firstOrNull()?.toByteArray() ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(first)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
