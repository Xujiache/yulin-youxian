package com.yulin.rider.core.location

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocationClient

/**
 * 高德 SDK 隐私开关。只有服务端确认用户真实点击同意后才写 true；撤回立即同步为 false。
 */
object AmapPrivacyConsent {

    fun update(context: Context, agreed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGREED, agreed)
            .commit()
        runCatching {
            AMapLocationClient.updatePrivacyShow(context.applicationContext, true, true)
            AMapLocationClient.updatePrivacyAgree(context.applicationContext, agreed)
        }.onFailure { Log.w(TAG, "更新高德隐私同意状态失败", it) }
    }

    fun isAgreed(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGREED, false)

    /** 创建 SDK 对象前调用；未同意时直接返回 false，绝不偷跑初始化。 */
    fun applyStoredConsent(context: Context): Boolean {
        if (!isAgreed(context)) return false
        update(context, true)
        return true
    }

    private const val PREFS_NAME = "rider_amap_privacy"
    private const val KEY_AGREED = "agreed"
    private const val TAG = "AmapPrivacyConsent"
}
