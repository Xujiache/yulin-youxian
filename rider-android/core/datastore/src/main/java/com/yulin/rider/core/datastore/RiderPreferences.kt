package com.yulin.rider.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全 App 单例 DataStore。令牌、偏好、保活向导标记同库不同键 ——
 * DataStore 同名实例只能创建一次,分库会在同进程内抛异常。
 */
val Context.riderDataStore by preferencesDataStore(name = "rider_prefs")

object RiderPreferenceKeys {
    val ACCESS_TOKEN = stringPreferencesKey("access_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val ACCESS_EXPIRE_AT = longPreferencesKey("access_expire_at")
    val RIDER_ID = longPreferencesKey("rider_id")
    val RIDER_NAME = stringPreferencesKey("rider_name")
    val RIDER_PHONE = stringPreferencesKey("rider_phone")
    /** 登出后保留，用来识别下一次登录是否发生账号切换；不属于当前会话。 */
    val LAST_RIDER_ID = longPreferencesKey("last_rider_id")
    val MUST_CHANGE_PASSWORD = booleanPreferencesKey("must_change_password")
    val LOCATION_CONSENT_AT = stringPreferencesKey("location_consent_at")

    val DEVICE_ID = stringPreferencesKey("device_id")

    val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
    val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    /** 播报语速倍率。风噪大时调慢比调大音量管用。 */
    val SPEECH_RATE = floatPreferencesKey("speech_rate")
    val FONT_SCALE = floatPreferencesKey("font_scale")
    val DARK_MODE = stringPreferencesKey("dark_mode")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

    val KEEPALIVE_GUIDE_DONE = booleanPreferencesKey("keepalive_guide_done")
    val KEEPALIVE_GUIDE_DONE_AT = longPreferencesKey("keepalive_guide_done_at")
    val PERMISSION_GUIDE_DONE = booleanPreferencesKey("permission_guide_done")

    /**
     * 上次停留的业务页路由（已填好参数的完整串）。
     * 骑手被电话、微信打断是常态，回来还要从首页一层层点回去很费时间。
     */
    val LAST_ROUTE = stringPreferencesKey("last_route")
    val LAST_ROUTE_AT = longPreferencesKey("last_route_at")
}

/** 深色模式三态。骑手白天强光、夜间骑行的取向完全不同,不能只跟随系统。 */
enum class DarkModeOption(val storageValue: String, val displayName: String) {
    FOLLOW_SYSTEM("SYSTEM", "跟随系统"),
    LIGHT("LIGHT", "始终浅色"),
    DARK("DARK", "始终深色");

    companion object {
        fun from(value: String?): DarkModeOption =
            entries.firstOrNull { it.storageValue == value } ?: FOLLOW_SYSTEM
    }
}
