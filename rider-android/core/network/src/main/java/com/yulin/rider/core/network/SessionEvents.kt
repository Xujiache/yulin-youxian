package com.yulin.rider.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 需要跳页的会话级事件。任何一个接口都可能返回 1003/1004,
 * 让每个 ViewModel 各写一遍跳转逻辑必然会漏,所以统一在网络层发事件、由 app 层导航消费。
 */
sealed interface SessionEvent {
    data class RequireLogin(val reason: String) : SessionEvent
    data class RequireOnDuty(val reason: String) : SessionEvent
    data class RequireLocationConsent(val reason: String) : SessionEvent
    data class AccountSuspended(val reason: String) : SessionEvent
}

@Singleton
class SessionEvents @Inject constructor() {

    private val _events = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /** 可在任意线程调用(含 OkHttp 拦截器线程),不挂起、不阻塞。 */
    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}
