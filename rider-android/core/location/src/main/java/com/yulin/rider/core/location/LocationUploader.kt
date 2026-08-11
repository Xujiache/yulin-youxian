package com.yulin.rider.core.location

import android.util.Log
import com.yulin.rider.core.model.LocationBatchRequest
import com.yulin.rider.core.model.LocationBatchResponse
import com.yulin.rider.core.network.api.RiderLocationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批量上报(04 §1.4)。每 20 秒把缓冲区的点打一个 batch 发出去,失败保留、指数退避(上限 60 秒)。
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

    /** 下一次上传应当等待的毫秒数,失败时按 20→40→60 退避封顶。 */
    fun nextDelayMillis(): Long = if (consecutiveFailures == 0) {
        BASE_INTERVAL_SECONDS * 1000L
    } else {
        // 移位次数必须先夹住:长时间断网后 consecutiveFailures 会很大,直接左移会溢出成负数,
        // minOf 取到负值 → delay 立即返回 → 变成 100% CPU 的空转循环
        val shift = (consecutiveFailures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        val backoff = BASE_INTERVAL_SECONDS.toLong() shl shift
        minOf(backoff, MAX_BACKOFF_SECONDS.toLong()) * 1000L
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
                    buffer.markUploaded(batch.map { it.id })
                    consecutiveFailures = 0
                    Outcome(success = true, uploaded = batch.size, response = response.data)
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
    }

    suspend fun awaitNextSlot() = delay(nextDelayMillis())

    companion object {
        const val BASE_INTERVAL_SECONDS = 20
        const val MAX_BACKOFF_SECONDS = 60
        const val MAX_BACKOFF_SHIFT = 4
        const val MAX_BATCH_SIZE = 200

        /** 1003 未上班 / 1004 未取得定位授权同意(04 §0 错误码表)。 */
        val FATAL_CODES = setOf(1003, 1004)

        private const val TAG = "LocationUploader"
    }
}
