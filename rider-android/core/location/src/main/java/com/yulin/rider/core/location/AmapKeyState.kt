package com.yulin.rider.core.location

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 高德 Key 探测。Key 通过 AndroidManifest 的 meta-data 注入,配上即生效,无需改代码:
 *
 * ```xml
 * <meta-data android:name="com.amap.api.v2.apikey" android:value="真实 Key" />
 * ```
 *
 * 未配置时全链路降级:定位走系统 LocationManager、地图页显示占位卡片、导航退到 geo: Intent。
 */
object AmapKeyState {

    const val META_DATA_KEY = "com.amap.api.v2.apikey"

    /** 占位值也视为未配置,避免团队填了 TODO 之后以为已生效。 */
    private val PLACEHOLDERS = setOf("", "TODO", "TODO_AMAP_KEY", "YOUR_AMAP_KEY", "null")

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var resolved = false

    fun apiKey(context: Context): String? {
        if (resolved) return cachedKey
        synchronized(this) {
            if (resolved) return cachedKey
            cachedKey = readMetaData(context)
            resolved = true
            return cachedKey
        }
    }

    fun isConfigured(context: Context): Boolean = !apiKey(context).isNullOrBlank()

    /** SDK 类是否真的打进了包(依赖被移除时不至于 NoClassDefFoundError)。 */
    val isSdkPresent: Boolean by lazy {
        runCatching { Class.forName("com.amap.api.location.AMapLocationClient") }.isSuccess
    }

    fun isMapAvailable(context: Context): Boolean =
        isSdkPresent && isConfigured(context) && AmapPrivacyConsent.isAgreed(context)

    private fun readMetaData(context: Context): String? = try {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        appInfo.metaData?.getString(META_DATA_KEY)?.trim()?.takeIf { it !in PLACEHOLDERS }
    } catch (e: Exception) {
        Log.w(TAG, "读取高德 Key 失败,按未配置处理", e)
        null
    }

    private const val TAG = "AmapKeyState"
}
