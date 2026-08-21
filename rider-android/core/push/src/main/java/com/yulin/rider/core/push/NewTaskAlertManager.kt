package com.yulin.rider.core.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 循环语音播报(06 §3.4)——真正让骑手不错过单的机制。
 *
 * 提示音 → TTS 播报订单摘要 → 每 10 秒重复,最多 6 次,或骑手在 App 内确认后立即停止。
 * 一分钟的持续提醒是有意的:骑手可能正在爬楼、手上拿着货,看一眼手机就得等一会儿。
 */
@Singleton
class NewTaskAlertManager @Inject constructor(
    private val voice: VoiceAnnouncer,
    private val notifier: PushNotifier,
    private val cursorStore: SyncCursorStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow<NewTaskAlert?>(null)

    /** 当前未确认的派单提醒。A8 的任务确认页可以直接绑它做全屏挽留。 */
    val active: StateFlow<NewTaskAlert?> = _active.asStateFlow()

    private var loop: Job? = null

    fun raise(alert: NewTaskAlert) {
        if (alert.taskVersion != null && alert.taskVersion <= cursorStore.acknowledgedVersion()) return
        // 同一批新单在轮询里会被反复看到,已在提醒中就不重开一轮,否则会叠成噪音
        val current = _active.value
        if (current != null && current.taskVersion == alert.taskVersion &&
            current.taskId == alert.taskId && current.taskCount == alert.taskCount
        ) return

        loop?.cancel()
        _active.value = alert
        notifier.notifyNewTask(alert)
        loop = scope.launch {
            repeat(MAX_REPEAT) { index ->
                if (index > 0) delay(REPEAT_INTERVAL_MILLIS)
                if (_active.value == null) return@launch
                voice.playPromptTone()
                voice.speak(alert.speechText())
            }
            // 播满 6 次仍未确认:停播但保留通知,骑手回来还能看到
            _active.value = null
        }
    }

    /** 骑手在 App 内确认(打开任务确认页/点了接单)后调用,立刻停播。 */
    fun acknowledge(taskVersion: Long? = null) {
        (taskVersion ?: _active.value?.taskVersion)?.let(cursorStore::acknowledge)
        loop?.cancel()
        loop = null
        _active.value = null
        voice.stopSpeaking()
        notifier.cancelNewTask()
    }

    /** 下班时清场。 */
    fun reset() {
        acknowledge()
    }

    private companion object {
        const val MAX_REPEAT = 6
        const val REPEAT_INTERVAL_MILLIS = 10_000L
    }
}
