package com.yulin.rider.core.push

import android.content.Context
import android.content.Intent
import com.yulin.rider.core.location.RiderDeviceReporter
import com.yulin.rider.core.model.SyncResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 派单提醒的统一入口,提供给 A8 在上下班与任务确认时调用。
 *
 * 通道优先级:极光推送(有 AppKey 时)→ 3 秒轮询兜底(始终开着)。
 * 两条通道都汇聚到 [NewTaskAlertManager],由它做去重与循环播报。
 */
@Singleton
class PushController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jpush: JPushAdapter,
    private val poller: SyncPoller,
    private val alertManager: NewTaskAlertManager,
    private val voice: VoiceAnnouncer,
    private val deviceReporter: RiderDeviceReporter,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前未确认的派单提醒;非空即表示正在循环播报。 */
    val newTaskAlert: StateFlow<NewTaskAlert?> get() = alertManager.active

    /** 最近一次 /api/rider/sync 结果,含 taskVersion / 未读消息数 / 疲劳态。 */
    val sync: StateFlow<SyncResponse?> get() = poller.latest

    val pollingRunning: StateFlow<Boolean> get() = poller.running

    val pushState: JPushAdapter.State get() = jpush.state

    /** 语音播报开关,对应设置页的「语音播报」。 */
    var voiceEnabled: Boolean
        get() = voice.speechEnabled
        set(value) {
            voice.speechEnabled = value
        }

    /** App 启动时调用一次。无 AppKey 时静默跳过极光,不影响轮询通道。 */
    fun initOnAppStart(debug: Boolean) {
        if (jpush.init(context, debug)) {
            scope.launch { reportRegistrationIdWhenReady() }
        }
    }

    /** 骑手点「上班」后调用:开启轮询兜底并恢复推送。 */
    fun onDutyStarted() {
        jpush.setPushEnabled(context, true)
        poller.start()
    }

    /** 骑手下班后调用:停轮询、停推送、清掉未确认的提醒。省电的关键。 */
    fun onDutyEnded() {
        poller.stop()
        alertManager.reset()
        jpush.setPushEnabled(context, false)
    }

    /** 骑手在 App 内确认新单后调用,立刻停止循环播报并清掉通知。 */
    fun acknowledgeNewTask(taskVersion: Long? = null) = alertManager.acknowledge(taskVersion)

    /**
     * 主动触发一次派单提醒。两个用途:
     * 1. 极光推送回调里带着订单摘要过来时;
     * 2. A8 拉到任务详情后,用小区名与距离补一条更具体的播报。
     */
    fun announceNewTask(alert: NewTaskAlert) = alertManager.raise(alert)

    /** 从全屏 Intent / 通知点击的 Intent 里解析出要跳转的任务;不是派单通知则返回 null。 */
    fun resolveTaskIdFromIntent(intent: Intent?): Long? {
        if (intent?.getStringExtra(PushNotifier.EXTRA_ROUTE) != PushNotifier.ROUTE_NEW_TASK) return null
        return intent.getLongExtra(PushNotifier.EXTRA_TASK_ID, -1L).takeIf { it > 0 }
    }

    fun resolveTaskVersionFromIntent(intent: Intent?): Long? {
        if (intent?.getStringExtra(PushNotifier.EXTRA_ROUTE) != PushNotifier.ROUTE_NEW_TASK) return null
        return intent.getLongExtra(PushNotifier.EXTRA_TASK_VERSION, -1L).takeIf { it >= 0L }
    }

    fun shutdown() {
        poller.stop()
        alertManager.reset()
        voice.shutdown()
    }

    /** registrationId 在 init 之后要等一会儿才生成,轮询几次拿到就上报,拿不到下次启动再补。 */
    private suspend fun reportRegistrationIdWhenReady() {
        repeat(REGISTRATION_RETRY) {
            val id = jpush.registrationId(context)
            if (id != null) {
                deviceReporter.reportRegistrationId(id)
                return
            }
            delay(REGISTRATION_RETRY_INTERVAL_MILLIS)
        }
    }

    private companion object {
        const val REGISTRATION_RETRY = 10
        const val REGISTRATION_RETRY_INTERVAL_MILLIS = 3_000L
    }
}
