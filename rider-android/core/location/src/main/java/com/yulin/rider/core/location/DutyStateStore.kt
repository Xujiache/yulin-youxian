package com.yulin.rider.core.location

import android.content.Context
import com.yulin.rider.core.datastore.RiderTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedDutyState(
    val waitingForServer: Boolean,
    val onDuty: Boolean,
    val riderId: Long,
    val shiftId: Long?,
    val startedAtMillis: Long?,
    val heartbeatAtMillis: Long?,
)

/** 进程被杀或设备重启后恢复前台服务所需的最小状态。 */
@Singleton
class DutyStateStore @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenStore: RiderTokenStore,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markWaiting(): Boolean = prefs.edit()
        .putBoolean(KEY_WAITING, true)
        .putBoolean(KEY_ON_DUTY, false)
        .putLong(KEY_RIDER_ID, tokenStore.currentBlocking()?.riderId ?: 0L)
        .remove(KEY_SHIFT_ID)
        .remove(KEY_STARTED_AT)
        .commit()

    fun markOnDuty(shiftId: Long, startedAtMillis: Long): Boolean = prefs.edit()
        .putBoolean(KEY_WAITING, false)
        .putBoolean(KEY_ON_DUTY, true)
        .putLong(KEY_RIDER_ID, tokenStore.currentBlocking()?.riderId ?: 0L)
        .putLong(KEY_SHIFT_ID, shiftId)
        .putLong(KEY_STARTED_AT, startedAtMillis)
        .putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis())
        .commit()

    fun heartbeat() {
        prefs.edit().putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis()).apply()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    fun current(): PersistedDutyState {
        val sessionRiderId = tokenStore.currentBlocking()?.riderId ?: 0L
        val storedRiderId = prefs.getLong(KEY_RIDER_ID, 0L)
        val validAccount = sessionRiderId > 0L && storedRiderId == sessionRiderId
        return PersistedDutyState(
            waitingForServer = validAccount && prefs.getBoolean(KEY_WAITING, false),
            onDuty = validAccount && prefs.getBoolean(KEY_ON_DUTY, false),
            riderId = storedRiderId,
            shiftId = prefs.getLong(KEY_SHIFT_ID, -1L).takeIf { it > 0L },
            startedAtMillis = prefs.getLong(KEY_STARTED_AT, -1L).takeIf { it > 0L },
            heartbeatAtMillis = prefs.getLong(KEY_HEARTBEAT_AT, -1L).takeIf { it > 0L },
        )
    }

    private companion object {
        const val PREFS_NAME = "rider_duty_state"
        const val KEY_WAITING = "waiting"
        const val KEY_ON_DUTY = "on_duty"
        const val KEY_RIDER_ID = "rider_id"
        const val KEY_SHIFT_ID = "shift_id"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_HEARTBEAT_AT = "heartbeat_at"
    }
}
