package com.yulin.rider.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canInstallUnknown(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun verifyReadyToInstall(apk: File, latestSha256: String, latestVersionCode: Int): String? {
        if (!apk.isFile) return "安装包不存在"
        if (!ApkChecksum.matches(apk, latestSha256)) return "安装包校验失败"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: return "无法读取安装包"
        if (archive.packageName != EXPECTED_PACKAGE) return "包名不正确"
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode
        }
        val current = ManagedDevice.currentVersionCode(context)
        if (archiveCode <= current) return "不能安装更旧或相同的版本"
        val currentCert = ManagedDevice.currentSigningSha256(context)
        val apkCert = archiveSigningSha256(archive)
        if (currentCert != null && apkCert != null && currentCert != apkCert) {
            return "签名证书不一致"
        }
        if (archiveCode != latestVersionCode) return "安装包版本与发布记录不一致"
        return null
    }

    fun install(apk: File, latestSha256: String, latestVersionCode: Int): String? {
        val error = verifyReadyToInstall(apk, latestSha256, latestVersionCode)
        if (error != null) return error
        if (!ManagedDevice.canSilentInstall(context) && !canInstallUnknown()) {
            context.startActivity(unknownSourcesIntent())
            return NEED_UNKNOWN_SOURCES
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(EXPECTED_PACKAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(
                if (ManagedDevice.canSilentInstall(context)) {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                },
            )
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("package", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        return null
    }

    private fun archiveSigningSha256(info: android.content.pm.PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return null
        val first = signatures.firstOrNull()?.toByteArray() ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(first)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val EXPECTED_PACKAGE = "com.yulin.rider"
        const val NEED_UNKNOWN_SOURCES = "NEED_UNKNOWN_SOURCES"
        const val ACTION_INSTALL_RESULT = "com.yulin.rider.action.UPDATE_INSTALL_RESULT"
    }
}
