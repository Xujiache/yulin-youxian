package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

/** 骑手端任务卡片(04 §1.3,字段已脱敏)。 */
@Serializable
data class TaskCard(
    val taskId: Long,
    val taskNo: String,
    val orderNo: String,
    val status: String,
    val statusText: String? = null,
    val seqNo: Int? = null,
    val totalStops: Int? = null,
    val receiverName: String? = null,
    val receiverPhoneMasked: String? = null,
    val callNumber: String? = null,
    val phoneDegraded: Boolean = false,
    val addressDetail: String? = null,
    val areaLabel: String? = null,
    val buildingLabel: String? = null,
    val unitNo: Int? = null,
    val floorNo: Int? = null,
    val roomNo: String? = null,
    val location: GeoPoint? = null,
    val distanceFromRiderMeters: Int? = null,
    val itemCount: Int = 0,
    val totalWeightKg: Double? = null,
    val packageCount: Int = 0,
    val coldChainLevel: String? = null,
    val coldChainText: String? = null,
    val goodsSummary: String? = null,
    val customerRemark: String? = null,
    val deliveryInstruction: String? = null,
    val highlightNotes: List<String> = emptyList(),
    val slotLabel: String? = null,
    val promisedAt: String? = null,
    val etaAt: String? = null,
    val remainingSeconds: Long? = null,
    val overtimeRisk: String? = null, // LOW/MEDIUM/HIGH/OVERTIME
    val requireVerifyCode: Boolean = false,
    val requirePhoto: Boolean = false,
    val sameAddressTaskCount: Int = 0,
)

@Serializable
data class WaveGroup(
    val waveId: Long,
    val waveNo: String,
    val status: String,
    val taskCount: Int = 0,
    val completedCount: Int = 0,
    val planDistanceMeters: Long? = null,
    val planDurationSeconds: Long? = null,
    val planReturnAt: String? = null,
    val maxColdChainLevel: String? = null,
    val stops: List<TaskCard> = emptyList(),
)

@Serializable
data class TaskList(
    val waves: List<WaveGroup> = emptyList(),
    val standaloneTasks: List<TaskCard> = emptyList(),
)

/** GET /api/rider/tasks/{taskId} 的完整响应，而不是列表页用的 TaskCard。 */
@Serializable
data class TaskDetail(
    val card: TaskCard,
    val items: List<TaskItem> = emptyList(),
    val events: List<TaskEvent> = emptyList(),
    val evidences: List<TaskEvidence> = emptyList(),
)

@Serializable
data class TaskItem(
    val productName: String,
    val quantity: Double,
    val unit: String,
    val weightKg: Double? = null,
    /** 金额单位为分。 */
    val amount: Int? = null,
)

@Serializable
data class TaskEvent(
    val id: Long,
    val eventType: String,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val operatorType: String? = null,
    val operatorName: String? = null,
    val reason: String? = null,
    val clientEventAt: String? = null,
    val createdAt: String,
)

@Serializable
data class TaskEvidence(
    val id: Long,
    val fileUrl: String,
    val evidenceType: String,
    val capturedAt: String? = null,
)

/**
 * 状态流转通用请求(04 §1.3):幂等键 + 客户端原始时间戳,离线重放必带。
 * 各接口的额外字段以可空成员承载。
 */
@Serializable
data class TaskTransitionRequest(
    val clientEventId: String,
    val clientEventAt: String,
    val location: GeoPoint? = null,
    val reason: String? = null,                    // reject / return / transfer
    val verifyCode: String? = null,                // deliver
    val evidenceIds: List<Long>? = null,           // deliver / return
    val receiveMethod: String? = null,             // deliver: FACE_TO_FACE/DOOR/RECEPTION/LOCKER
    val checkedTaskIds: List<Long>? = null,        // wave pickup
    val actualPackageCount: Int? = null,           // wave pickup
)

@Serializable
data class WaveSequenceRequest(val taskIds: List<Long>)

/** 隐私号拨打通道响应(04 §1.5)。 */
@Serializable
data class CallResponse(
    val callNumber: String,
    val degraded: Boolean = false,
    val expireAt: String? = null,
    val notice: String? = null,
)
