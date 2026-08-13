package com.yulin.rider.core.location

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

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

    @Volatile
    private var cachedNativePresent: Boolean? = null

    /**
     * 底图渲染用的 native 库是否随当前 ABI 装了进来。
     *
     * 高德只提供 arm64-v8a 与 armeabi-v7a 两份 `.so`,x86_64 模拟器上装的包里没有。
     * 光有 Key 和 Java 类是不够的 —— MapView 照样能 new 出来,但渲染线程会不停抛
     * `UnsatisfiedLinkError: GLMapEngine.nativeMainThreadTrigger`,画面停在一块白板上,
     * 而且那个异常每帧一次,是实打实的耗电。所以要在建 MapView 之前就判掉。
     *
     * 按文件名前缀探测而不是 `System.loadLibrary("AMapSDK_NAVI_v10_0_800")`:
     * 后者写死了版本号,SDK 一升级就会误判成不可用,把真机也降级掉。
     */
    fun isNativeRendererPresent(context: Context): Boolean {
        cachedNativePresent?.let { return it }
        return synchronized(this) {
            cachedNativePresent ?: run {
                val present = runCatching {
                    File(context.applicationInfo.nativeLibraryDir)
                        .list()
                        ?.any { it.startsWith("libAMapSDK") } == true
                }.getOrDefault(false)
                if (!present) {
                    Log.w(TAG, "当前 ABI 没有高德 native 库,地图底图降级为文字路线")
                }
                cachedNativePresent = present
                present
            }
        }
    }

    fun isMapAvailable(context: Context): Boolean = unavailableReason(context) == null

    /** 底图不可用的原因。返回 null 表示可用。文案由调用方决定,这里只给判定。 */
    fun unavailableReason(context: Context): MapUnavailable? = when {
        !isSdkPresent -> MapUnavailable.SDK_MISSING
        !isConfigured(context) -> MapUnavailable.KEY_MISSING
        !AmapPrivacyConsent.isAgreed(context) -> MapUnavailable.CONSENT_MISSING
        !isNativeRendererPresent(context) -> MapUnavailable.NATIVE_MISSING
        else -> null
    }

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

/** 底图不可用的具体原因。占位卡按这个给出「能不能修、怎么修」的说法。 */
enum class MapUnavailable {
    /** 依赖被裁掉了,包里根本没有高德 SDK。 */
    SDK_MISSING,

    /** 没配 Key。 */
    KEY_MISSING,

    /** 骑手还没签隐私授权,合规要求下不能初始化 SDK。 */
    CONSENT_MISSING,

    /** 有 Key 但当前 ABI 缺 native 库,典型场景是 x86_64 模拟器。 */
    NATIVE_MISSING,
}
