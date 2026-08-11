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
    val voiceEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
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

    suspend fun currentSettings(): RiderSettings = settingsFlow.first()

    suspend fun currentGuide(): RiderGuideState = guideFlow.first()

    suspend fun setVoiceEnabled(enabled: Boolean) =
        put { it[RiderPreferenceKeys.VOICE_ENABLED] = enabled }

    suspend fun setSoundEnabled(enabled: Boolean) =
        put { it[RiderPreferenceKeys.SOUND_ENABLED] = enabled }

    /** 只开放 1.0–1.3:再大就要重排版面,这个上限与设计系统的控件放大上限一致。 */
    suspend fun setFontScale(scale: Float) =
        put { it[RiderPreferenceKeys.FONT_SCALE] = scale.coerceIn(1.0f, 1.3f) }

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
}
