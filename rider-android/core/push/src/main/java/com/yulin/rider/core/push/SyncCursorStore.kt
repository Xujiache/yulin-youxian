package com.yulin.rider.core.push

import android.content.Context
import com.yulin.rider.core.datastore.RiderTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 按 riderId 持久化 /sync 消费与用户确认游标，进程重启不会重复播报已确认版本。 */
@Singleton
class SyncCursorStore @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenStore: RiderTokenStore,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun seenVersion(): Long = prefs.getLong(key(KEY_SEEN), NO_VERSION)

    fun acknowledgedVersion(): Long = prefs.getLong(key(KEY_ACK), NO_VERSION)

    fun markSeen(version: Long) = markMax(KEY_SEEN, version)

    fun acknowledge(version: Long) = markMax(KEY_ACK, version)

    @Synchronized
    private fun markMax(prefix: String, version: Long) {
        val key = key(prefix)
        if (version > prefs.getLong(key, NO_VERSION)) {
            prefs.edit().putLong(key, version).commit()
        }
    }

    private fun key(prefix: String): String {
        val riderId = tokenStore.currentBlocking()?.riderId ?: 0L
        return "${prefix}_$riderId"
    }

    private companion object {
        const val PREFS_NAME = "rider_sync_cursor"
        const val KEY_SEEN = "seen"
        const val KEY_ACK = "ack"
        const val NO_VERSION = -1L
    }
}
