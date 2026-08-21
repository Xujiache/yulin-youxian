package com.yulin.rider.core.location

import android.content.Context
import android.content.SharedPreferences
import com.yulin.rider.core.datastore.RiderTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备级本地状态:设备标识、推送 registrationId、保活向导完成标记与逐项确认。
 *
 * 用 SharedPreferences 而不是 DataStore:[KeepAliveChecker.current] 是同步签名(A8 的上班
 * 检查清单要在点击瞬间拿到结果),而 DataStore 只有挂起读。A7 的 core:datastore 若后续提供
 * 同步快照,可以在这里换实现,对外 API 不变。
 */
@Singleton
class RiderDeviceStore @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenStore: RiderTokenStore,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)

    /** 任一项确认状态变化时自增,供 Compose 侧重新读取。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    var guideDone: Boolean
        get() = prefs.getBoolean(KEY_GUIDE_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GUIDE_DONE, value).apply()
            bump()
        }

    /** 极光 registrationId;推送未接入时为 null,设备上报仍然照发。 */
    var registrationId: String?
        get() = prefs.getString(KEY_REGISTRATION_ID, null)
        set(value) = prefs.edit().putString(KEY_REGISTRATION_ID, value).apply()

    fun isStepConfirmed(stepId: String): Boolean = prefs.getBoolean(stepKey(stepId), false)

    fun setStepConfirmed(stepId: String, confirmed: Boolean) {
        prefs.edit().putBoolean(stepKey(stepId), confirmed).apply()
        bump()
    }

    fun confirmedSteps(stepIds: Collection<String>): Set<String> =
        stepIds.filterTo(mutableSetOf()) { isStepConfirmed(it) }

    fun resetConfirmations(stepIds: Collection<String>) {
        prefs.edit().apply {
            stepIds.forEach { remove(stepKey(it)) }
            putBoolean(KEY_GUIDE_DONE, false)
        }.apply()
        bump()
    }

    /**
     * 设备唯一标识。登录、设备上报、位置上报都要用同一个值,
     * 唯一来源是 core:datastore；旧版 SharedPreferences 值由 RiderTokenStore 首次读取时迁移。
     */
    val deviceId: String
        get() = tokenStore.deviceIdBlocking()

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun stepKey(stepId: String) = "step_$stepId"

    private companion object {
        const val PREFS_NAME = "rider_keepalive"
        const val KEY_GUIDE_DONE = "keepalive_guide_done"
        const val KEY_REGISTRATION_ID = "push_registration_id"
    }
}
