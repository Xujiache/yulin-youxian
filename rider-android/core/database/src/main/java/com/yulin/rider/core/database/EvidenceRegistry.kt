package com.yulin.rider.core.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class EvidenceRecord(
    val contentHash: String,
    val localPath: String,
    val capturedAt: String,
    val uploadedId: Long?,
)

@Singleton
class EvidenceRegistry @Inject constructor(
    private val dao: EvidenceUploadDao,
) {

    suspend fun prepare(path: String): EvidenceRecord = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.isFile && file.length() > 0L) { "凭证文件不存在或为空:$path" }
        val hash = file.sha256()
        val capturedAt = SERVER_TIME_FORMAT.format(
            Instant.ofEpochMilli(file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis())
                .atZone(SERVER_ZONE)
        )
        dao.insert(
            EvidenceUploadEntity(
                contentHash = hash,
                localPath = file.absolutePath,
                capturedAt = capturedAt,
            )
        )
        requireNotNull(dao.find(hash)).toRecord()
    }

    suspend fun find(contentHash: String): EvidenceRecord? = dao.find(contentHash)?.toRecord()

    suspend fun markUploaded(contentHash: String, uploadedId: Long) {
        dao.markUploaded(contentHash, uploadedId, System.currentTimeMillis())
    }

    /** 主业务动作成功或确定放弃后，账本和本地文件一起清理。 */
    suspend fun complete(contentHashes: Collection<String>) {
        if (contentHashes.isEmpty()) return
        val records = contentHashes.distinct().mapNotNull { dao.find(it) }
        dao.delete(records.map { it.contentHash })
        withContext(Dispatchers.IO) {
            records.forEach { runCatching { File(it.localPath).delete() } }
        }
    }

    private fun EvidenceUploadEntity.toRecord() = EvidenceRecord(
        contentHash = contentHash,
        localPath = localPath,
        capturedAt = capturedAt,
        uploadedId = uploadedId,
    )

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val SERVER_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val SERVER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
