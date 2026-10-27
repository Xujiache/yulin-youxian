package com.yulin.rider.core.push

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 极光 JPush 接入层。
 *
 * **为什么走反射而不是直接调 API**:版本目录里的 jpush 4.0.5 其 AAR 的 PushReceiver 带
 * intent-filter 却缺 `android:exported`,与 targetSdk 36 的强制要求冲突,一旦启用依赖
 * 整个 manifest 合并就会失败(见 core/push/build.gradle.kts 的说明)。反射适配让这段代码
 * 现在就能编译、能跑(降级为不初始化),等官方 5.x 依赖可解析、AppKey 也拿到之后,
 * 只要打开依赖并补上 meta-data 就自动生效,业务代码一行不用改。
 *
 * 无 AppKey 时全程 no-op,绝不崩溃 —— 此时派单靠 [SyncPoller] 的 3 秒轮询兜底。
 */
@Singleton
class JPushAdapter @Inject constructor() {

    enum class State {
        /** SDK 类不在 classpath(依赖尚未启用)。 */
        SDK_ABSENT,

        /** SDK 在,但 AppKey 未配置。 */
        NO_APP_KEY,
        READY,
        FAILED,
    }

    @Volatile
    var state: State = State.SDK_ABSENT
        private set

    private val jpushInterface: Class<*>? by lazy {
        runCatching { Class.forName(JPUSH_INTERFACE) }.getOrNull()
    }

    fun isAvailable(context: Context): Boolean =
        jpushInterface != null && appKey(context) != null

    /**
     * 只在 App 启动时调用一次。无 AppKey 时静默跳过,不抛异常。
     * @return 是否真的完成了初始化。
     */
    fun init(context: Context, debug: Boolean): Boolean {
        val clazz = jpushInterface
        if (clazz == null) {
            state = State.SDK_ABSENT
            Log.i(TAG, "极光 SDK 未接入,派单提醒走 3 秒轮询兜底")
            return false
        }
        if (appKey(context) == null) {
            state = State.NO_APP_KEY
            Log.i(TAG, "极光 AppKey 未配置,跳过初始化")
            return false
        }
        return try {
            clazz.getMethod("setDebugMode", Boolean::class.javaPrimitiveType)
                .invoke(null, debug)
            clazz.getMethod("init", Context::class.java)
                .invoke(null, context.applicationContext)
            state = State.READY
            true
        } catch (e: Throwable) {
            state = State.FAILED
            Log.w(TAG, "极光初始化失败,降级为轮询", e)
            false
        }
    }

    /** registrationId 在 init 之后需要一小段时间才有值,拿不到就返回 null,由调用方重试。 */
    fun registrationId(context: Context): String? {
        if (state != State.READY) return null
        return runCatching {
            jpushInterface
                ?.getMethod("getRegistrationID", Context::class.java)
                ?.invoke(null, context.applicationContext) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 下班后停推,省电也避免打扰。 */
    fun setPushEnabled(context: Context, enabled: Boolean) {
        if (state != State.READY) return
        runCatching {
            val method = if (enabled) "resumePush" else "stopPush"
            jpushInterface?.getMethod(method, Context::class.java)
                ?.invoke(null, context.applicationContext)
        }.onFailure { Log.w(TAG, "切换极光推送开关失败", it) }
    }

    private fun appKey(context: Context): String? = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(META_APP_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it !in PLACEHOLDERS }
    }.getOrNull()

    private companion object {
        const val TAG = "JPushAdapter"
        const val JPUSH_INTERFACE = "cn.jpush.android.api.JPushInterface"
        const val META_APP_KEY = "JPUSH_APPKEY"
        val PLACEHOLDERS = setOf("TODO_JPUSH_APPKEY", "TODO", "null")
    }
}
