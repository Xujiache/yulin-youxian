package com.yulin.rider.core.update

import com.yulin.rider.core.network.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
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
        val existing = if (target.isFile) target.length() else 0L
        val request = Request.Builder().url(url).get().apply {
            if (existing > 0L) header("Range", "bytes=$existing-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                if (ApkChecksum.matches(target, expectedSha256)) return@withContext target
                target.delete()
                return@withContext download(fileUrl, target, expectedSha256, onProgress)
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
                    val buffer = ByteArray(64 * 1024)
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
        target
    }

    private fun resolveUrl(fileUrl: String): String {
        if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) return fileUrl
        val base = BuildConfig.BASE_URL.trimEnd('/')
        return if (fileUrl.startsWith("/")) base + fileUrl else "$base/$fileUrl"
    }

    companion object {
        fun createIsolatedClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .build()
    }
}
