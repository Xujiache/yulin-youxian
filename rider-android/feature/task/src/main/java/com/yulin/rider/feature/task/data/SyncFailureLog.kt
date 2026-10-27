package com.yulin.rider.feature.task.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条被服务端最终拒绝的动作。
 *
 * [taskNo] 是给骑手看的，[message] 直接用服务端返回的业务错误文案。
 */
@Serializable
data class SyncFailure(
    val clientEventId: String,
    val taskId: Long? = null,
    val taskNo: String? = null,
    val actionLabel: String,
    val message: String,
    val atMillis: Long,
)

/**
 * 同步失败记录。
 *
 * 之前终态失败是「静默回滚」：骑手点完送达页面就退回去了，任务过一会儿自己变回未送达，
 * 照片也被删了，全程没有任何提示。核销码输错一位就会这样，是最容易踩的坑。
 *
 * 这里把失败原因持久化下来，首页给出横幅，骑手至少知道「这单没成，为什么没成」。
 * 用 SharedPreferences 而不是 Room：这是给人看的提示，不是业务数据，
 * 不值得为它加一次数据库迁移。
 */
class SyncFailureLog private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _failures = MutableStateFlow(load())
    val failures: StateFlow<List<SyncFailure>> = _failures.asStateFlow()

    fun record(failure: SyncFailure) {
        // 只留最近几条：骑手要看的是「刚才那单怎么了」，不是历史账本
        val next = (listOf(failure) + _failures.value.filterNot { it.clientEventId == failure.clientEventId })
            .take(MAX_RECORDS)
        persist(next)
    }

    fun dismiss(clientEventId: String) {
        persist(_failures.value.filterNot { it.clientEventId == clientEventId })
    }

    fun clear() = persist(emptyList())

    private fun persist(list: List<SyncFailure>) {
        _failures.value = list
        prefs.edit()
            .putString(KEY_ITEMS, json.encodeToString(ListSerializer, list))
            .apply()
    }

    private fun load(): List<SyncFailure> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer, raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "rider_sync_failures"
        private const val KEY_ITEMS = "items"
        private const val MAX_RECORDS = 10
        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(
            SyncFailure.serializer()
        )

        @Volatile
        private var instance: SyncFailureLog? = null

        fun get(context: Context): SyncFailureLog = instance ?: synchronized(this) {
            instance ?: SyncFailureLog(context.applicationContext).also { instance = it }
        }
    }
}
