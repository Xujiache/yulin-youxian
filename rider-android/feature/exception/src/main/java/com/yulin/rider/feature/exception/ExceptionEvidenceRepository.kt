package com.yulin.rider.feature.exception

import android.content.Context
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.database.EvidenceRecord
import com.yulin.rider.core.database.di.RiderDatabases
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.network.RiderApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** 异常凭证的数据层：复用 Room 上传账本，并把业务失败与断网明确区分。 */
class ExceptionEvidenceRepository(context: Context) {

    private val appContext = context.applicationContext
    private val registry = RiderDatabases.evidenceRegistry(appContext)

    suspend fun upload(
        path: String,
        taskId: Long,
        location: GeoPoint?,
    ): RiderResult<EvidenceRecord> = withContext(Dispatchers.IO) {
        val apis = RiderApis.of(appContext)
        apis.caller.runCatchingApi {
            var record = registry.prepare(path)
            if (record.uploadedId == null) {
                val file = File(record.localPath)
                val part = MultipartBody.Part.createFormData(
                    "file",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaType()),
                )
                val uploaded = apis.caller.data {
                    apis.exception.uploadEvidence(
                        file = part,
                        evidenceType = "EXCEPTION".asTextPart(),
                        taskId = taskId.toString().asTextPart(),
                        lat = location?.lat?.toString()?.asTextPart(),
                        lng = location?.lng?.toString()?.asTextPart(),
                        capturedAt = record.capturedAt.asTextPart(),
                        contentHash = record.contentHash.asTextPart(),
                    )
                }
                registry.markUploaded(record.contentHash, uploaded.id)
                record = record.copy(uploadedId = uploaded.id)
            }
            record
        }
    }

    suspend fun complete(records: Collection<EvidenceRecord>) {
        registry.complete(records.map { it.contentHash })
    }

    suspend fun prepare(path: String): EvidenceRecord = registry.prepare(path)

    private fun String.asTextPart(): RequestBody = toRequestBody("text/plain".toMediaType())
}
