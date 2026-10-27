package com.yulin.rider.core.push

import android.util.Log
import com.yulin.rider.core.model.RiderMessage
import com.yulin.rider.core.model.SyncResponse
import com.yulin.rider.core.model.TaskCard
import com.yulin.rider.core.network.api.RiderSyncApi
import com.yulin.rider.core.network.api.RiderTaskApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轮询兜底(06 §3.4 / 04 §1.4)。上班期间每 3 秒调一次 GET /api/rider/sync。
 *
 * 在极光 AppKey 到位之前,这是**唯一**的派单通道,必须可靠;到位之后它仍然是推送失败时的
 * 保险绳。接口极轻量(只返回版本号和计数),3 秒一次的开销可以接受。
 * 只在上班期间跑,下班立即停 —— 这是省电的关键。
 */
@Singleton
class SyncPoller @Inject constructor(
    private val api: RiderSyncApi,
    private val taskApi: RiderTaskApi,
    private val alertManager: NewTaskAlertManager,
    private val voice: VoiceAnnouncer,
    private val notifier: PushNotifier,
    private val cursorStore: SyncCursorStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _latest = MutableStateFlow<SyncResponse?>(null)

    /** 最近一次 /sync 结果。A8 可以直接观察 taskVersion 决定要不要拉全量。 */
    val latest: StateFlow<SyncResponse?> = _latest.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null
    private var lastTaskVersion: Long? = null
    private var lastPendingAcceptCount = 0
    private var firstPollAfterStart = true
    private val announcedMessageIds = mutableSetOf<Long>()

    fun start() {
        if (job?.isActive == true) return
        lastTaskVersion = cursorStore.seenVersion().takeIf { it >= 0L }
        lastPendingAcceptCount = 0
        firstPollAfterStart = true
        announcedMessageIds.clear()
        _running.value = true
        job = scope.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }

    private suspend fun pollLoop() {
        var intervalSeconds = DEFAULT_INTERVAL_SECONDS
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            try {
                val response = api.sync()
                val data = response.data
                if (response.code == 0 && data != null) {
                    consecutiveFailures = 0
                    handle(data)
                    _latest.value = data
                    intervalSeconds = data.config.syncIntervalSeconds
                        .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
                } else {
                    consecutiveFailures++
                    Log.d(TAG, "轮询业务失败 [${response.code}] ${response.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                Log.d(TAG, "轮询网络失败,第 $consecutiveFailures 次", e)
            }

            // 断网时不停地 3 秒一发只会白耗电,退避到最多 30 秒;恢复后立刻回到正常节奏
            val waitSeconds = if (consecutiveFailures == 0) {
                intervalSeconds
            } else {
                minOf(intervalSeconds shl minOf(consecutiveFailures, 4), FAILURE_MAX_INTERVAL_SECONDS)
            }
            delay(waitSeconds * 1000L)
        }
    }

    private suspend fun handle(data: SyncResponse) {
        if (data.pendingAcceptCount == 0) {
            alertManager.acknowledge(data.taskVersion)
        }
        val versionChanged = data.taskVersion != lastTaskVersion
        val pendingGrew = data.pendingAcceptCount > lastPendingAcceptCount
        val restoreUnacknowledged = firstPollAfterStart &&
            data.taskVersion > cursorStore.acknowledgedVersion() &&
            data.pendingAcceptCount > 0

        if ((pendingGrew || versionChanged || restoreUnacknowledged) &&
            data.pendingAcceptCount > 0
        ) {
            val pending = firstPendingTask()
            if (pending != null) {
                alertManager.raise(
                    NewTaskAlert(
                        taskId = pending.taskId,
                        taskVersion = data.taskVersion,
                        taskCount = data.pendingAcceptCount,
                        // 带上小区和距离才有决策价值：只说「您有 1 个新订单」
                        // 骑手还是得掏手机看，语音就白播了
                        areaLabel = pending.areaLabel,
                        distanceMeters = pending.distanceFromRiderMeters,
                        source = AlertSource.POLLING,
                    )
                )
                cursorStore.markSeen(data.taskVersion)
                lastTaskVersion = data.taskVersion
            }
        } else {
            cursorStore.markSeen(data.taskVersion)
            lastTaskVersion = data.taskVersion
        }
        lastPendingAcceptCount = data.pendingAcceptCount
        firstPollAfterStart = false

        data.urgentMessages.filter { it.needVoice }.forEach { announce(it) }
    }

    private suspend fun firstPendingTask(): TaskCard? {
        val response = taskApi.getTasks(null)
        if (response.code != 0) return null
        val tasks = response.data ?: return null
        return (tasks.waves.asSequence().flatMap { it.stops.asSequence() } +
            tasks.standaloneTasks.asSequence())
            .firstOrNull { it.status == "ASSIGNED" || it.status == "PENDING_ACCEPT" }
    }

    private suspend fun announce(message: RiderMessage) {
        if (!announcedMessageIds.add(message.id)) return
        val text = listOfNotNull(message.title, message.content)
            .filter { it.isNotBlank() }
            .joinToString("，")
        if (text.isBlank()) return
        if (message.linkType.equals("APP", ignoreCase = true) ||
            message.messageType.equals("APP_UPDATE", ignoreCase = true)
        ) {
            notifier.notifyAppUpdate(message.title ?: "骑手端有新版本", message.content.orEmpty())
            return
        }
        notifier.notifyUrgent(message.title ?: "紧急提醒", message.content.orEmpty())
        voice.playPromptTone()
        // 一次 sync 最多带 5 条紧急消息，必须排队播；用打断的话只有最后一条能听全
        voice.speak(text, interrupt = false)
    }

    private companion object {
        const val TAG = "SyncPoller"
        const val DEFAULT_INTERVAL_SECONDS = 3
        const val MIN_INTERVAL_SECONDS = 2
        const val MAX_INTERVAL_SECONDS = 30
        const val FAILURE_MAX_INTERVAL_SECONDS = 30
    }
}
