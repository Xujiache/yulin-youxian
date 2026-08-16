package com.yulin.rider.core.update

import com.yulin.rider.core.network.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloadClient @Inject constructor(
    @UpdateDownloadClient private val client: OkHttpClient,
) {
    suspend fun download(
        fileUrl: String,
        target: File,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val url = resolveUrl(fileUrl)
        if (target.isFile && ApkChecksum.matches(target, expectedSha256)) {
            onProgress(target.length(), target.length())
            return@withContext target
        }
        val probe = probe(url)
        val canParallel = probe != null && RangePlan.supportsParallel(probe.acceptRanges, probe.totalBytes)
        if (probe != null && canParallel && (target.length() == 0L || target.length() == probe.totalBytes)) {
            downloadParallel(url, target, expectedSha256, probe.totalBytes, onProgress)
        } else {
            downloadSingle(url, target, expectedSha256, onProgress)
        }
    }

    private fun probe(url: String): Probe? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return null
            val total = contentRangeTotal(response.header("Content-Range"))
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code != 206 }
                ?: return null
            Probe(
                totalBytes = total,
                acceptRanges = response.header("Accept-Ranges") ?: if (response.code == 206) "bytes" else null,
            )
        }
    }

    private suspend fun downloadParallel(
        url: String,
        target: File,
        expectedSha256: String,
        totalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        if (target.exists() && target.length() != totalBytes) {
            target.delete()
        }
        val downloaded = AtomicLong(0L)
        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(totalBytes)
            val channel = raf.channel
            coroutineScope {
                RangePlan.spans(totalBytes).map { span ->
                    async(Dispatchers.IO) {
                        downloadSpan(url, channel, span) { delta ->
                            onProgress(downloaded.addAndGet(delta), totalBytes)
                        }
                    }
                }.awaitAll()
            }
        }
        if (!ApkChecksum.matches(target, expectedSha256)) {
            target.delete()
            error("安装包校验失败")
        }
        return target
    }

    private fun downloadSpan(
        url: String,
        channel: FileChannel,
        span: ByteSpan,
        onDelta: (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${span.start}-${span.endInclusive}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206) {
                error("分段下载失败 HTTP ${response.code}")
            }
            val body = response.body ?: error("分段下载失败：空响应")
            var position = span.start
            val buffer = ByteArray(256 * 1024)
            body.byteStream().use { input ->
                while (position <= span.endInclusive) {
                    val remaining = (span.endInclusive - position + 1L).coerceAtMost(buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, remaining)
                    if (read <= 0) break
                    channel.write(ByteBuffer.wrap(buffer, 0, read), position)
                    position += read
                    onDelta(read.toLong())
                }
            }
            if (position <= span.endInclusive) {
                error("分段下载不完整")
            }
        }
    }

    private fun downloadSingle(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val existing = if (target.isFile) target.length() else 0L
        val request = Request.Builder().url(url).get().apply {
            if (existing > 0L) header("Range", "bytes=$existing-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                if (ApkChecksum.matches(target, expectedSha256)) return target
                target.delete()
                return downloadSingle(url, target, expectedSha256, onProgress)
            }
            if (!response.isSuccessful) {
                error("下载失败 HTTP ${response.code}")
            }
            val append = response.code == 206 && existing > 0L
            if (!append && target.exists()) target.delete()
            val body = response.body ?: error("下载失败：空响应")
            val total = if (append) {
                existing + body.contentLength().coerceAtLeast(0L)
            } else {
                body.contentLength().coerceAtLeast(0L)
            }
            RandomAccessFile(target, "rw").use { file ->
                if (append) file.seek(existing) else file.setLength(0)
                var downloaded = if (append) existing else 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        file.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        if (!ApkChecksum.matches(target, expectedSha256)) {
            target.delete()
            error("安装包校验失败")
        }
        return target
    }

    private fun resolveUrl(fileUrl: String): String {
        if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) return fileUrl
        val base = BuildConfig.BASE_URL.trimEnd('/')
        return if (fileUrl.startsWith("/")) base + fileUrl else "$base/$fileUrl"
    }

    private data class Probe(val totalBytes: Long, val acceptRanges: String?)

    companion object {
        fun createIsolatedClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 6
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }

        internal fun contentRangeTotal(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.lastIndex) return null
            return header.substring(slash + 1).toLongOrNull()
        }
    }
}
