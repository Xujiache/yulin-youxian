package com.yulin.rider.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationBufferDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: LocationBufferEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<LocationBufferEntity>)

    /** 上传器每 20 秒取一批未上传的点,按采集时间升序保证轨迹顺序。 */
    @Query("SELECT * FROM location_buffer WHERE uploaded = 0 ORDER BY locatedAt ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<LocationBufferEntity>

    @Query("SELECT COUNT(*) FROM location_buffer WHERE uploaded = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_buffer WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM location_buffer ORDER BY locatedAt DESC LIMIT 1")
    suspend fun latest(): LocationBufferEntity?

    @Query("UPDATE location_buffer SET uploaded = 1, uploadedAt = :uploadedAt WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>, uploadedAt: Long)

    /** 已上传的点保留一段时间再清,便于事后排查轨迹纠纷(06 §3.9)。 */
    @Query("DELETE FROM location_buffer WHERE uploaded = 1 AND uploadedAt IS NOT NULL AND uploadedAt < :before")
    suspend fun purgeUploadedBefore(before: Long)

    /** 未上传的点也不能无限堆积:长时间断网时保留最近的,丢掉最老的。 */
    @Query(
        "DELETE FROM location_buffer WHERE uploaded = 0 AND id NOT IN " +
            "(SELECT id FROM location_buffer WHERE uploaded = 0 ORDER BY locatedAt DESC LIMIT :keep)"
    )
    suspend fun trimPending(keep: Int)

    @Query("DELETE FROM location_buffer")
    suspend fun clear()
}
