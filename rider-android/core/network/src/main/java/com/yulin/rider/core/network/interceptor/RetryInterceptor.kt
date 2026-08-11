package com.yulin.rider.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_RETRIES = 2
private const val RETRY_BASE_DELAY_MS = 300L
private const val MAX_SNIFFED_BODY_BYTES = 64L * 1024

/**
 * 幂等请求重试(06 §3.8):GET,以及带 clientEventId 的 POST,失败重试 2 次。
 *
 * 带 clientEventId 的 POST 之所以可以重试,是因为服务端按该键去重并返回上一次的成功结果(04 §1.3);
 * 不带幂等键的 POST 一律不重试 —— 重复送达、重复上报异常的代价比多一次失败大得多。
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isIdempotent()) return chain.proceed(request)

        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(request)
                // 5xx 说明服务端这一跳出了问题,重试有意义;4xx 重试只是白跑
                if (attempt >= MAX_RETRIES || response.code < 500) return response
                response.close()
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES) throw e
            }
            attempt++
            sleepQuietly(RETRY_BASE_DELAY_MS * attempt)
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private fun Request.isIdempotent(): Boolean = when (method) {
    "GET", "HEAD" -> true
    "POST", "PUT" -> hasClientEventId()
    else -> false
}

/**
 * 只嗅探 JSON 文本体。multipart 的文件流是一次性的,读一遍就废了,绝不能碰。
 */
private fun Request.hasClientEventId(): Boolean {
    val body = body ?: return false
    if (body.isOneShot() || body.isDuplex()) return false
    if (body.contentType()?.subtype?.contains("json", ignoreCase = true) != true) return false
    val length = body.contentLength()
    if (length > MAX_SNIFFED_BODY_BYTES) return false
    return runCatching {
        Buffer().use { buffer ->
            body.writeTo(buffer)
            buffer.readUtf8().contains("\"clientEventId\"")
        }
    }.getOrDefault(false)
}
