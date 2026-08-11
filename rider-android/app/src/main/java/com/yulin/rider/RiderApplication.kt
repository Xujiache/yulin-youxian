package com.yulin.rider

import android.app.Application
import com.yulin.rider.core.network.SessionEvent
import com.yulin.rider.core.network.SessionEvents
import com.yulin.rider.core.push.RiderPushManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RiderApplication : Application() {

    @Inject lateinit var sessionEvents: SessionEvents
    @Inject lateinit var sessionCoordinator: RiderSessionCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        RiderNotificationChannels.createAll(this)

        // 极光 AppKey 为空时只打一条日志,3 秒轮询那条通道不受影响
        RiderPushManager.init(this, BuildConfig.DEBUG)

        // Worker/前台服务在 Activity 不可见时也可能发现会话失效，必须仍能停服务、取消任务并清库。
        appScope.launch {
            sessionEvents.events.collect { event ->
                if (event is SessionEvent.RequireLogin || event is SessionEvent.AccountSuspended) {
                    sessionCoordinator.endSession()
                }
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    companion object {
        const val CHANNEL_NEW_TASK = "channel_new_task"
        const val CHANNEL_URGENT = "channel_urgent"
        const val CHANNEL_NORMAL = "channel_normal"
        const val CHANNEL_SERVICE = "channel_service"
    }
}
