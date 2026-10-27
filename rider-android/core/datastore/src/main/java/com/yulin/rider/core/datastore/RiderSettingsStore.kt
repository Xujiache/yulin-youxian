package com.yulin.rider.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 用户偏好。字号倍率与深色模式在启动时就要读到,否则会闪一下默认主题。 */
data class RiderSettings(
    /** 语音播报：用 TTS 读出新单与紧急消息。 */
    val voiceEnabled: Boolean = true,
    /** 提示音：播报前的那一声，以及滑动确认成功的反馈音。与语音播报独立。 */
    val soundEnabled: Boolean = true,
    val speechRate: Float = 1.0f,
    val fontScale: Float = 1.0f,
    val darkMode: DarkModeOption = DarkModeOption.FOLLOW_SYSTEM,
    val keepScreenOn: Boolean = true,
)

/** 保活向导进度。上班检查清单要校验它,服务端也要同步一份(rider_device.keepalive_guide_done)。 */
data class RiderGuideState(
    val keepaliveGuideDone: Boolean = false,
    val keepaliveGuideDoneAtMillis: Long = 0L,
    val permissionGuideDone: Boolean = false,
)

@Singleton
class RiderSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    val settingsFlow: Flow<RiderSettings> = context.riderDataStore.data.map { prefs ->
        RiderSettings(
            voiceEnabled = prefs[RiderPreferenceKeys.VOICE_ENABLED] ?: true,
            soundEnabled = prefs[RiderPreferenceKeys.SOUND_ENABLED] ?: true,
            speechRate = prefs[RiderPreferenceKeys.SPEECH_RATE] ?: 1.0f,
            fontScale = prefs[RiderPreferenceKeys.FONT_SCALE] ?: 1.0f,
            darkMode = DarkModeOption.from(prefs[RiderPreferenceKeys.DARK_MODE]),
            keepScreenOn = prefs[RiderPreferenceKeys.KEEP_SCREEN_ON] ?: true,
        )
    }

    val guideFlow: Flow<RiderGuideState> = context.riderDataStore.data.map { prefs ->
        RiderGuideState(
            keepaliveGuideDone = prefs[RiderPreferenceKeys.KEEPALIVE_GUIDE_DONE] ?: false,
            keepaliveGuideDoneAtMillis = prefs[RiderPreferenceKeys.KEEPALIVE_GUIDE_DONE_AT] ?: 0L,
            permissionGuideDone = prefs[RiderPreferenceKeys.PERMISSION_GUIDE_DONE] ?: false,
        )
    }

    /**
     * 上次停留的业务页。超过 [LAST_ROUTE_TTL_MILLIS] 就不再恢复 ——
     * 隔了一夜再打开还跳回昨天那一单，比停在首页更让人困惑。
     */
    suspend fun currentLastRoute(): String? {
        val prefs = context.riderDataStore.data.first()
        val route = prefs[RiderPreferenceKeys.LAST_ROUTE]?.takeIf { it.isNotBlank() } ?: return null
        val savedAt = prefs[RiderPreferenceKeys.LAST_ROUTE_AT] ?: 0L
        val age = System.currentTimeMillis() - savedAt
        return if (savedAt > 0L && age in 0..LAST_ROUTE_TTL_MILLIS) route else null
    }

    suspend fun setLastRoute(route: String?) = put { prefs ->
        if (route.isNullOrBlank()) {
            prefs.remove(RiderPreferenceKeys.LAST_ROUTE)
            prefs.remove(RiderPreferenceKeys.LAST_ROUTE_AT)
        } else {
            prefs[RiderPreferenceKeys.LAST_ROUTE] = route
            prefs[RiderPreferenceKeys.LAST_ROUTE_AT] = System.currentTimeMillis()
        }
    }

    suspend fun currentSettings(): RiderSettings = settingsFlow.first()

    suspend fun currentGuide(): RiderGuideState = guideFlow.first()

    suspend fun setVoiceEnabled(enabled: Boolean) =
        put { it[RiderPreferenceKeys.VOICE_ENABLED] = enabled }

    suspend fun setSoundEnabled(enabled: Boolean) =
        put { it[RiderPreferenceKeys.SOUND_ENABLED] = enabled }

    /** 0.6–1.5 倍。再慢会拖到下一条播报，再快中文 TTS 会糊成一片。 */
    suspend fun setSpeechRate(rate: Float) =
        put { it[RiderPreferenceKeys.SPEECH_RATE] = rate.coerceIn(0.6f, 1.5f) }

    /**
     * 只开放 0.9–1.3。上限再大就要重排版面,与设计系统的控件放大上限一致;
     * 下限 0.9 是给视力好、希望一屏多看几单的骑手用的,再小触摸目标就不够 9 mm 了。
     */
    suspend fun setFontScale(scale: Float) =
        put { it[RiderPreferenceKeys.FONT_SCALE] = scale.coerceIn(0.9f, 1.3f) }

    suspend fun setDarkMode(option: DarkModeOption) =
        put { it[RiderPreferenceKeys.DARK_MODE] = option.storageValue }

    suspend fun setKeepScreenOn(enabled: Boolean) =
        put { it[RiderPreferenceKeys.KEEP_SCREEN_ON] = enabled }

    suspend fun setKeepaliveGuideDone(done: Boolean) = put { prefs ->
        prefs[RiderPreferenceKeys.KEEPALIVE_GUIDE_DONE] = done
        prefs[RiderPreferenceKeys.KEEPALIVE_GUIDE_DONE_AT] =
            if (done) System.currentTimeMillis() else 0L
    }

    suspend fun setPermissionGuideDone(done: Boolean) =
        put { it[RiderPreferenceKeys.PERMISSION_GUIDE_DONE] = done }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.riderDataStore.edit(block)
    }

    private companion object {
        /** 一个班次的长度量级。跨班次恢复没有意义，反而容易让人以为单子没送完。 */
        const val LAST_ROUTE_TTL_MILLIS = 12L * 60 * 60 * 1000
    }
}
