package com.yulin.rider.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tasks: List<TaskCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskCacheEntity)

    @Query("SELECT * FROM task_cache ORDER BY waveId ASC, seqNo ASC, promisedAtMillis ASC")
    fun observeAll(): Flow<List<TaskCacheEntity>>

    @Query("SELECT * FROM task_cache WHERE status IN (:statuses) ORDER BY seqNo ASC, promisedAtMillis ASC")
    fun observeByStatus(statuses: List<String>): Flow<List<TaskCacheEntity>>

    @Query("SELECT * FROM task_cache WHERE taskId = :taskId")
    fun observeTask(taskId: Long): Flow<TaskCacheEntity?>

    @Query("SELECT * FROM task_cache WHERE taskId = :taskId")
    suspend fun findTask(taskId: Long): TaskCacheEntity?

    @Query("SELECT * FROM task_cache WHERE waveId = :waveId ORDER BY seqNo ASC")
    suspend fun findByWave(waveId: Long): List<TaskCacheEntity>

    /** 乐观更新:骑手一按下就改本地状态,让他能立刻做下一单。 */
    @Query("UPDATE task_cache SET status = :status, statusText = :statusText, localDirty = 1, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun markLocalStatus(taskId: Long, status: String, statusText: String?, updatedAt: Long)

    @Query("UPDATE task_cache SET localDirty = 0 WHERE taskId = :taskId")
    suspend fun clearDirty(taskId: Long)

    @Query(
        "UPDATE task_cache SET status = :status, statusText = :statusText, localDirty = 0, " +
            "updatedAt = :updatedAt WHERE taskId = :taskId"
    )
    suspend fun restoreStatus(
        taskId: Long,
        status: String,
        statusText: String?,
        updatedAt: Long,
    )

    /** 服务端全量刷新时,清掉本轮没返回且没有本地未同步改动的任务。 */
    @Query("DELETE FROM task_cache WHERE localDirty = 0 AND taskId NOT IN (:keepTaskIds)")
    suspend fun pruneExcept(keepTaskIds: List<Long>)

    @Query("DELETE FROM task_cache")
    suspend fun clear()
}

@Dao
interface WaveCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(waves: List<WaveCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wave: WaveCacheEntity)

    @Query("SELECT * FROM wave_cache ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WaveCacheEntity>>

    @Query("SELECT * FROM wave_cache WHERE waveId = :waveId")
    suspend fun find(waveId: Long): WaveCacheEntity?

    @Query("SELECT * FROM wave_cache WHERE waveId = :waveId")
    fun observe(waveId: Long): Flow<WaveCacheEntity?>

    @Query("DELETE FROM wave_cache")
    suspend fun clear()
}
