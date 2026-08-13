package com.yulin.rider.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 队列 DAO。
 *
 * 分组规则:同一任务的动作必须串行(ARRIVE 一定要先于 DELIVER 到达服务端),不同任务之间可并行。
 * 没有 taskId 的动作(如整波次取货)按 waveId 归组,两者都没有的落到全局组 'G' ——
 * 宁可多串行几条,也不能乱序。
 */
@Dao
interface PendingActionDao {

    /** 幂等键冲突说明同一个动作被重复入队,忽略即可。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(action: PendingActionEntity): Long

    @Query("SELECT * FROM pending_action ORDER BY createdAt ASC, rowid ASC")
    suspend fun getAll(): List<PendingActionEntity>

    @Query("SELECT * FROM pending_action ORDER BY createdAt ASC, rowid ASC")
    fun observeAll(): Flow<List<PendingActionEntity>>

    @Query("SELECT COUNT(*) FROM pending_action")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_action WHERE attemptCount > :maxAttempts")
    fun observeStuckCount(maxAttempts: Int): Flow<Int>

    @Query("SELECT * FROM pending_action WHERE taskId = :taskId ORDER BY createdAt ASC, rowid ASC")
    suspend fun findByTask(taskId: Long): List<PendingActionEntity>

    @Query("SELECT * FROM pending_action WHERE waveId = :waveId ORDER BY createdAt ASC, rowid ASC")
    suspend fun findByWave(waveId: Long): List<PendingActionEntity>

    @Query("SELECT * FROM pending_action WHERE clientEventId = :clientEventId")
    suspend fun findById(clientEventId: String): PendingActionEntity?

    /**
     * 每个组只放出最早的一条。失败的队头不会被删除,于是自动阻塞了本组后续动作,
     * 而其他组照常推进 —— 这就是 06 §3.3 要求的「FIFO + 同任务串行」。
     */
    @Query(
        """
        SELECT * FROM pending_action AS p
        WHERE NOT EXISTS (
            SELECT 1 FROM pending_action AS q
            WHERE (CASE WHEN q.waveId IS NOT NULL THEN 'W' || q.waveId
                        WHEN q.taskId IS NOT NULL THEN 'T' || q.taskId ELSE 'G' END)
                = (CASE WHEN p.waveId IS NOT NULL THEN 'W' || p.waveId
                        WHEN p.taskId IS NOT NULL THEN 'T' || p.taskId ELSE 'G' END)
              AND (q.createdAt < p.createdAt
                   OR (q.createdAt = p.createdAt AND q.rowid < p.rowid))
        )
        ORDER BY p.createdAt ASC, p.rowid ASC
        LIMIT :limit
        """
    )
    suspend fun claimHeadOfEachGroup(limit: Int): List<PendingActionEntity>

    /**
     * 按任务集合取动作。终态失败回滚只能波及「同一批被乐观改过的任务」，
     * 不能像 [findByWave] 那样把整波次其他单的动作也算进去。
     */
    @Query(
        "SELECT * FROM pending_action WHERE taskId IN (:taskIds) ORDER BY createdAt ASC, rowid ASC"
    )
    suspend fun findByTasks(taskIds: List<Long>): List<PendingActionEntity>

    @Query("DELETE FROM pending_action WHERE clientEventId = :clientEventId")
    suspend fun delete(clientEventId: String)

    @Query("DELETE FROM pending_action WHERE clientEventId IN (:clientEventIds)")
    suspend fun deleteAll(clientEventIds: List<String>)

    @Query("DELETE FROM pending_action WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    @Query("DELETE FROM pending_action WHERE waveId = :waveId")
    suspend fun deleteByWave(waveId: Long)

    @Query("UPDATE pending_action SET payloadJson = :payloadJson WHERE clientEventId = :clientEventId")
    suspend fun updatePayload(clientEventId: String, payloadJson: String)

    @Query(
        "UPDATE pending_action SET attemptCount = attemptCount + 1, lastError = :error " +
            "WHERE clientEventId = :clientEventId"
    )
    suspend fun markFailure(clientEventId: String, error: String?)

    @Query("UPDATE pending_action SET attemptCount = 0, lastError = NULL WHERE clientEventId = :clientEventId")
    suspend fun resetAttempts(clientEventId: String)

    /** 只在骑手手动确认放弃时调用。队列永不自动清空(06 §3.9)。 */
    @Query("DELETE FROM pending_action")
    suspend fun clear()
}
