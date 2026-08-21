package com.yulin.rider.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 离线写队列(06 §3.3)。
 *
 * 「送达」必须在断网时也能完成,且记录的时间必须是骑手按下按钮的时刻([clientEventAt]),
 * 不是网络恢复的时刻 —— 所以操作先落这张表,UI 立即走乐观状态,重放交给同步器。
 * [clientEventId] 同时是服务端的幂等键,重放不会产生重复副作用。
 */
@Entity(
    tableName = "pending_action",
    indices = [Index(value = ["taskId"]), Index(value = ["waveId"]), Index(value = ["createdAt"])],
)
data class PendingActionEntity(
    @PrimaryKey val clientEventId: String,
    val actionType: String,
    val taskId: Long? = null,
    val waveId: Long? = null,
    val payloadJson: String,
    val clientEventAt: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 超过 10 次仍失败就在 UI 上标「同步异常」,由骑手手动重试(06 §3.3)。 */
    val isStuck: Boolean get() = attemptCount > MAX_AUTO_ATTEMPTS

    companion object {
        const val MAX_AUTO_ATTEMPTS = 10
    }
}

/** 队列里的动作类型,与 04 §1.3 的状态流转接口一一对应。 */
enum class PendingActionType {
    ACCEPT, REJECT, PICKUP, DEPART, ARRIVE, DELIVER, RETURN, TRANSFER, EXCEPTION, SEQUENCE;

    companion object {
        fun from(raw: String): PendingActionType? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 任务缓存(06 §3.9)。进页面先出缓存再刷新,地库、电梯里也能看单。
 * [payloadJson] 存整份 TaskCard,新增字段不用改表。
 */
@Entity(
    tableName = "task_cache",
    indices = [Index(value = ["waveId"]), Index(value = ["status"])],
)
data class TaskCacheEntity(
    @PrimaryKey val taskId: Long,
    val taskNo: String? = null,
    val waveId: Long? = null,
    val seqNo: Int? = null,
    val status: String,
    val statusText: String? = null,
    val receiverName: String? = null,
    val addressLabel: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val promisedAtMillis: Long? = null,
    val etaAtMillis: Long? = null,
    val payloadJson: String,
    val updatedAt: Long,
    /** 本地已乐观改过状态但服务端还没确认,刷新时不能被服务端旧数据覆盖回去。 */
    val localDirty: Boolean = false,
)

/** 波次缓存,含 polyline —— 断网时地图仍能画出规划路线。 */
@Entity(tableName = "wave_cache")
data class WaveCacheEntity(
    @PrimaryKey val waveId: Long,
    val waveNo: String? = null,
    val status: String? = null,
    val taskCount: Int = 0,
    val completedCount: Int = 0,
    val polyline: String? = null,
    val routeJson: String? = null,
    val payloadJson: String,
    val updatedAt: Long,
)

/**
 * 位置缓冲(06 §3.1)。采样写入、上传器批量读取,
 * 上传成功后保留 1 小时再清理,便于排查「骑手说送到了但轨迹上没有」这类纠纷。
 */
@Entity(
    tableName = "location_buffer",
    indices = [Index(value = ["uploaded", "locatedAt"])],
)
data class LocationBufferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double? = null,
    val speedMps: Double? = null,
    val bearing: Double? = null,
    val altitude: Double? = null,
    val provider: String? = null,
    val motionState: String? = null,
    val batteryLevel: Int? = null,
    val networkType: String? = null,
    val locatedAt: Long,
    /** 采集当下的业务上下文，不能在稍后上传时用“当前班次”覆盖。 */
    val shiftId: Long? = null,
    val taskId: Long? = null,
    val waveId: Long? = null,
    val uploaded: Boolean = false,
    val uploadedAt: Long? = null,
)

/**
 * 凭证上传账本。contentHash 是客户端幂等键：动作 API 暂时失败或进程重启后复用已上传 ID，
 * 不再把同一张图片重复上传。
 */
@Entity(
    tableName = "evidence_upload",
    indices = [Index(value = ["localPath"]), Index(value = ["uploadedId"])],
)
data class EvidenceUploadEntity(
    @PrimaryKey val contentHash: String,
    val localPath: String,
    val capturedAt: String,
    val uploadedId: Long? = null,
    val uploadedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
