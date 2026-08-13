package com.yulin.rider.feature.task.data

import com.yulin.rider.core.model.ExceptionCreate
import com.yulin.rider.core.model.TaskTransitionRequest
import kotlinx.serialization.Serializable

object PendingActionTypes {
    const val ACCEPT = "ACCEPT"
    const val PICKUP = "PICKUP"
    const val DEPART = "DEPART"
    const val ARRIVE = "ARRIVE"
    const val DELIVER = "DELIVER"
    const val EXCEPTION = "EXCEPTION"

    /** 同步失败提示里用的中文名，骑手看不懂 DELIVER。 */
    fun label(type: String): String = when (type) {
        ACCEPT -> "接单"
        PICKUP -> "取货"
        DEPART -> "出发"
        ARRIVE -> "到达"
        DELIVER -> "送达"
        EXCEPTION -> "异常上报"
        else -> type
    }
}

@Serializable
data class OptimisticTaskSnapshot(
    val taskId: Long,
    val status: String,
    val statusText: String? = null,
)

@Serializable
data class QueuedEvidence(
    val contentHash: String,
    val localPath: String,
    val capturedAt: String,
    val uploadedId: Long? = null,
)

/**
 * 离线动作载荷(06 §3.3)。
 *
 * taskId / waveId / lat / lng 在 core/database 的 PendingActionEntity 里已有独立列
 * (队列靠 taskId 分组保证同任务串行),这里再存一份是为了重放时不用回查实体,
 * 也让载荷本身自解释。真正只有这里才有的是 [transition] / [exception] / [localPhotoPaths]。
 */
@Serializable
data class PendingActionPayload(
    val actionType: String,
    val taskId: Long? = null,
    val waveId: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val createdAt: Long,
    val transition: TaskTransitionRequest? = null,
    val exception: ExceptionCreate? = null,
    /** 旧版载荷兼容字段；首次重放会迁移进 evidences。 */
    val localPhotoPaths: List<String> = emptyList(),
    val evidences: List<QueuedEvidence> = emptyList(),
    val evidenceType: String? = null,
    /** 终态业务错误时恢复骑手操作前的状态；进程重启后同样有效。 */
    val optimisticStates: List<OptimisticTaskSnapshot> = emptyList(),
)
