package com.yulin.rider.core.location

import android.util.Log
import com.yulin.rider.core.model.LocationBatchRequest
import com.yulin.rider.core.model.LocationBatchResponse
import com.yulin.rider.core.network.api.RiderLocationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批量上报(04 §1.4)。空闲时每 20 秒把缓冲区的点打一个 batch 发出去；
 * 进行中配送缩短到约 10 秒，新骑行点会立即 flush。失败保留、指数退避(上限 60 秒)。
 *
 * 只有 code == 0 才算成功。业务失败码里 1003(未上班)/1004(未同意定位)属于「再重试也没用」,
 * 直接丢弃这批点并上抛,避免队列被永远卡死。
 */
@Singleton
class LocationUploader @Inject constructor(
    private val api: RiderLocationApi,
    private val buffer: LocationBuffer,
) {

    data class Outcome(
        val success: Boolean,
        val uploaded: Int = 0,
        val response: LocationBatchResponse? = null,
        val fatalCode: Int? = null,
        val error: String? = null,
    )

    private var consecutiveFailures = 0
    private val flush = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun requestImmediateFlush() {
        flush.trySend(Unit)
    }

    /** 下一次上传应当等待的毫秒数,失败时按 base→2x→… 退避封顶。 */
    fun nextDelayMillis(delivering: Boolean = false): Long {
        val base = if (delivering) DELIVERING_INTERVAL_SECONDS else BASE_INTERVAL_SECONDS
        return if (consecutiveFailures == 0) {
            base * 1000L
        } else {
            val shift = (consecutiveFailures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
            val backoff = base.toLong() shl shift
            minOf(backoff, MAX_BACKOFF_SECONDS.toLong()) * 1000L
        }
    }

    suspend fun awaitNextSlot(delivering: Boolean = false) {
        if (!delivering) {
            while (flush.tryReceive().isSuccess) {
                // 非配送状态丢掉积压的立即上报信号，避免空闲时被旧的骑行点拖进高频上传
            }
            delay(nextDelayMillis(false))
            return
        }
        withTimeoutOrNull(nextDelayMillis(true)) {
            flush.receive()
        }
    }

    suspend fun uploadOnce(activeShiftId: Long?): Outcome {
        val pending = buffer.peek(MAX_BATCH_SIZE)
        if (pending.isEmpty()) {
            consecutiveFailures = 0
            return Outcome(success = true)
        }

        val firstContext = pending.first().context
        val batch = pending.takeWhile { it.context == firstContext }
        if (activeShiftId == null || firstContext.shiftId != activeShiftId) {
            // 后端按“当前打开班次”归档，旧班次点绝不能伪装成新班次上传。
            buffer.markUploaded(batch.map { it.id })
            return Outcome(
                success = true,
                uploaded = batch.size,
                error = "已隔离 ${batch.size} 个非当前班次位置点",
            )
        }

        val request = LocationBatchRequest(
            batchKey = UUID.randomUUID().toString(),
            points = batch.map { it.point },
            currentTaskId = firstContext.taskId,
            waveId = firstContext.waveId,
        )

        return try {
            val response = api.uploadBatch(request)
            when {
                response.code == 0 -> {
                    val uploadedIds = idsToMarkUploaded(batch, response.data)
                    buffer.markUploaded(uploadedIds)
                    consecutiveFailures = 0
                    Outcome(success = true, uploaded = uploadedIds.size, response = response.data)
                }

                response.code in FATAL_CODES -> {
                    // 未上班/未同意定位:这批点服务端永远不会收,留着只会撑爆队列
                    buffer.markUploaded(batch.map { it.id })
                    consecutiveFailures = 0
                    Outcome(success = false, fatalCode = response.code, error = response.message)
                }

                else -> {
                    consecutiveFailures++
                    Outcome(success = false, error = "[${response.code}] ${response.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consecutiveFailures++
            Log.w(TAG, "位置批量上报失败,第 $consecutiveFailures 次", e)
            Outcome(success = false, error = e.message ?: "网络异常")
        }
    }

    /** 上下班切换时重置退避,避免上一班的失败计数拖慢新班次的首次上报。 */
    fun resetBackoff() {
        consecutiveFailures = 0
        while (flush.tryReceive().isSuccess) {
            // ignore
        }
    }

    companion object {
        const val BASE_INTERVAL_SECONDS = 20
        const val DELIVERING_INTERVAL_SECONDS = 10
        const val MAX_BACKOFF_SECONDS = 60
        const val MAX_BACKOFF_SHIFT = 4
        const val MAX_BATCH_SIZE = 100
        const val BATCH_LIMIT_REASON = "BATCH_LIMIT"

        /** 1003 未上班 / 1004 未取得定位授权同意(04 §0 错误码表)。 */
        val FATAL_CODES = setOf(1003, 1004)

        private const val TAG = "LocationUploader"

        /**
         * 服务端一次最多处理 [MAX_BATCH_SIZE] 个点。被 BATCH_LIMIT 拒绝的后半批必须留在缓冲里重试，
         * 不能因为 HTTP 业务码是 0 就整批标成已上传。
         */
        fun idsToMarkUploaded(
            batch: List<BufferedPoint>,
            response: LocationBatchResponse?,
        ): List<Long> {
            val limitRejected = response?.rejectReasons.orEmpty()
                .filter { it.reason.equals(BATCH_LIMIT_REASON, ignoreCase = true) }
                .map { it.index }
                .toHashSet()
            return batch.mapIndexedNotNull { index, item ->
                if (index in limitRejected) null else item.id
            }
        }
    }
}
