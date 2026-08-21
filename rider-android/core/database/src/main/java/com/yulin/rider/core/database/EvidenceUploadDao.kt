package com.yulin.rider.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EvidenceUploadDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(evidence: EvidenceUploadEntity): Long

    @Query("SELECT * FROM evidence_upload WHERE contentHash = :contentHash")
    suspend fun find(contentHash: String): EvidenceUploadEntity?

    @Query(
        "UPDATE evidence_upload SET uploadedId = :uploadedId, uploadedAt = :uploadedAt " +
            "WHERE contentHash = :contentHash"
    )
    suspend fun markUploaded(contentHash: String, uploadedId: Long, uploadedAt: Long)

    @Query("DELETE FROM evidence_upload WHERE contentHash IN (:contentHashes)")
    suspend fun delete(contentHashes: List<String>)

    @Query("DELETE FROM evidence_upload")
    suspend fun clear()
}
