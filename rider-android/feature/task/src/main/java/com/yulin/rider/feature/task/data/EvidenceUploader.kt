package com.yulin.rider.feature.task.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.network.ApiCaller
import com.yulin.rider.core.network.api.RiderExceptionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** 凭证上传(04 §1.5)。服务端限单文件 5 MB,客户端先压到 1280 px 长边、JPEG 80(06 §3.3)。 */
object EvidenceUploader {

    private const val MAX_EDGE_PX = 1280
    private const val JPEG_QUALITY = 80

    /** 失败直接抛,由队列记进 lastError;吞掉异常会让「送达」看起来成功但服务端没有凭证。 */
    suspend fun upload(
        api: RiderExceptionApi,
        caller: ApiCaller,
        path: String,
        evidenceType: String,
        taskId: Long?,
        capturedAt: String,
        contentHash: String,
        exceptionId: Long? = null,
        location: GeoPoint? = null,
    ): Long = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.exists()) { "凭证文件已丢失:$path" }
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType())
        )
        caller.data {
            api.uploadEvidence(
                file = part,
                evidenceType = evidenceType.asTextPart(),
                taskId = taskId?.toString()?.asTextPart(),
                exceptionId = exceptionId?.toString()?.asTextPart(),
                lat = location?.lat?.toString()?.asTextPart(),
                lng = location?.lng?.toString()?.asTextPart(),
                capturedAt = capturedAt.asTextPart(),
                contentHash = contentHash.asTextPart(),
            )
        }.id
    }

    /**
     * 压缩并落盘到应用私有目录。拍照当下就压,断网排队时占的也是压缩后的体积。
     */
    fun persistCompressed(
        context: Context,
        source: ByteArray,
        prefix: String,
        rotationDegrees: Int = 0,
    ): String? = runCatching {
        require(source.isNotEmpty()) { "照片数据为空" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片尺寸" }
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { longEdge / it <= MAX_EDGE_PX * 2 }
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: return@runCatching null
        val oriented = bitmap.rotated(rotationDegrees)
        val scaled = oriented.scaledToMaxEdge()
        val dir = File(context.filesDir, "delivery-evidence").apply { mkdirs() }
        require(dir.usableSpace >= MIN_FREE_SPACE_BYTES) { "手机存储空间不足" }
        val target = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        val temporary = File(dir, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG 压缩失败" }
            output.fd.sync()
        }
        check(temporary.length() in 1..MAX_FILE_BYTES) { "压缩后的凭证大小不合法" }
        check(temporary.renameTo(target)) { "凭证原子落盘失败" }
        target.setLastModified(System.currentTimeMillis())
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== bitmap) oriented.recycle()
        bitmap.recycle()
        target.absolutePath
    }.onFailure {
        File(context.filesDir, "delivery-evidence").listFiles()
            ?.filter { file -> file.name.endsWith(".tmp") }
            ?.forEach(File::delete)
    }.getOrNull()

    private fun Bitmap.scaledToMaxEdge(): Bitmap {
        val longEdge = max(width, height)
        if (longEdge <= MAX_EDGE_PX) return this
        val ratio = MAX_EDGE_PX.toFloat() / longEdge
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun String.asTextPart(): RequestBody = toRequestBody("text/plain".toMediaType())

    private const val MIN_FREE_SPACE_BYTES = 16L * 1024 * 1024
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
}
