package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double? = null,
    val speedMps: Double? = null,
    val bearing: Double? = null,
    val altitude: Double? = null,
    val provider: String? = null,      // GPS / NETWORK
    val motionState: String? = null,   // STILL / WALKING / RIDING
    val batteryLevel: Int? = null,
    val networkType: String? = null,
    val locatedAt: String,
)

/** 全系统调用量最大的接口:批量 + 幂等 + 极简(04 §1.4)。 */
@Serializable
data class LocationBatchRequest(
    val batchKey: String,
    val points: List<LocationPoint>,
    val currentTaskId: Long? = null,
    val waveId: Long? = null,
)

@Serializable
data class RejectReason(
    val index: Int,
    val reason: String,
)

/** 服务端搭车下发指令,省一次轮询。 */
@Serializable
data class ServerCommand(
    val type: String,              // REFRESH_TASKS / MARK_ARRIVED / ...
    val taskId: Long? = null,
)

@Serializable
data class LocationBatchResponse(
    val accepted: Int = 0,
    val rejected: Int = 0,
    val rejectReasons: List<RejectReason> = emptyList(),
    val serverTime: String? = null,
    val nextIntervalSeconds: Int? = null,
    val commands: List<ServerCommand> = emptyList(),
)
