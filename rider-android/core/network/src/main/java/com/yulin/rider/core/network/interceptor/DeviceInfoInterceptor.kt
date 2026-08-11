package com.yulin.rider.core.network.interceptor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yulin.rider.core.datastore.RiderTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** 注入设备标识与版本号(06 §3.8),服务端靠 X-Device-Id 做设备绑定与推送定向。 */
@Singleton
class DeviceInfoInterceptor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokenStore: RiderTokenStore,
) : Interceptor {

    private val appVersion: String by lazy { readAppVersion() }
    private val deviceModel: String by lazy { headerSafe(Build.MODEL) }
    private val osVersion: String by lazy { headerSafe(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Device-Id", headerSafe(tokenStore.deviceIdBlocking()))
            .header("X-App-Version", appVersion)
            .header("X-Platform", "android")
            .header("X-Device-Model", deviceModel)
            .header("X-OS-Version", osVersion)
            .build()
        return chain.proceed(request)
    }

    private fun readAppVersion(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName.orEmpty().ifBlank { "0.0.0" }
    }.getOrDefault("0.0.0")

    /**
     * HTTP 头只允许 ISO-8859-1 可见字符,而国产 ROM 的 Build.MODEL 里出现中文并不罕见
     * (例如「红米 Note」)。不过滤会在 OkHttp 里直接抛 IllegalArgumentException,全网请求崩掉。
     */
    private fun headerSafe(raw: String?): String {
        val value = raw.orEmpty().filter { it.code in 0x20..0x7E }.trim()
        return value.ifBlank { "unknown" }
    }
}
