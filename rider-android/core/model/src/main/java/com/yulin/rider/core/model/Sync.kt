package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncConfig(
    val reportIntervalSeconds: Int = 10,
    val syncIntervalSeconds: Int = 3,
)

/** 应用内轮询兜底(04 §1.4):推送失效时不丢单。 */
@Serializable
data class SyncResponse(
    val serverTime: String? = null,
    val hasNewTask: Boolean = false,
    val pendingAcceptCount: Int = 0,
    val taskVersion: Long = 0,
    val unreadMessageCount: Int = 0,
    val urgentMessages: List<RiderMessage> = emptyList(),
    val fatigue: Fatigue = Fatigue(),
    val config: SyncConfig = SyncConfig(),
)
