package com.yulin.rider.core.database

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线写队列的对外门面(06 §3.3)。
 *
 * 同步器只需要循环 [nextBatch] → 成功 [markSuccess] / 失败 [markFailure],
 * FIFO 与「同任务串行」的保证全部落在 [PendingActionDao.claimHeadOfEachGroup] 的一条 SQL 里,
 * 调用方不需要也不应该自己排序。
 */
@Singleton
class OfflineActionQueue @Inject constructor(
    private val dao: PendingActionDao,
) {

    val pendingCount: Flow<Int> = dao.observeCount()

    val stuckCount: Flow<Int> = dao.observeStuckCount(PendingActionEntity.MAX_AUTO_ATTEMPTS)

    val all: Flow<List<PendingActionEntity>> = dao.observeAll()

    /**
     * 入队。[clientEventAt] 必须是骑手按下按钮的真实时刻,不能等到重放时再取当前时间。
     * 返回 false 表示该幂等键已在队列里,重复点击被吞掉。
     */
    suspend fun enqueue(
        clientEventId: String,
        actionType: String,
        payloadJson: String,
        taskId: Long? = null,
        waveId: Long? = null,
        clientEventAt: Long = System.currentTimeMillis(),
        lat: Double? = null,
        lng: Double? = null,
    ): Boolean {
        val inserted = dao.insert(
            PendingActionEntity(
                clientEventId = clientEventId,
                actionType = actionType,
                taskId = taskId,
                waveId = waveId,
                payloadJson = payloadJson,
                clientEventAt = clientEventAt,
                lat = lat,
                lng = lng,
                createdAt = System.currentTimeMillis(),
            )
        )
        return inserted != -1L
    }

    /** 本轮可以并行重放的动作:每个任务组最多一条,且一定是该组最早的一条。 */
    suspend fun nextBatch(limit: Int = DEFAULT_BATCH): List<PendingActionEntity> =
        dao.claimHeadOfEachGroup(limit)

    suspend fun markSuccess(clientEventId: String) = dao.delete(clientEventId)

    suspend fun markFailure(clientEventId: String, error: String?) =
        dao.markFailure(clientEventId, error?.take(MAX_ERROR_LENGTH))

    /** 骑手在「同步异常」提示里手动重试,把退避计数清零重新排。 */
    suspend fun retryNow(clientEventId: String) = dao.resetAttempts(clientEventId)

    suspend fun actionsOf(taskId: Long): List<PendingActionEntity> = dao.findByTask(taskId)

    suspend fun actionsOfWave(waveId: Long): List<PendingActionEntity> = dao.findByWave(waveId)

    suspend fun snapshot(): List<PendingActionEntity> = dao.getAll()

    suspend fun updatePayload(clientEventId: String, payloadJson: String) =
        dao.updatePayload(clientEventId, payloadJson)

    companion object {
        private const val DEFAULT_BATCH = 20
        private const val MAX_ERROR_LENGTH = 300
    }
}
