package com.yulin.rider.core.location

import com.yulin.rider.core.model.LocationPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class LocationCaptureContext(
    val shiftId: Long?,
    val taskId: Long?,
    val waveId: Long?,
)

data class BufferedPoint(
    val id: Long,
    val point: LocationPoint,
    val context: LocationCaptureContext,
)

/**
 * 位置点缓冲。上传成功前必须留存,否则电梯/地下车库里的轨迹会整段丢失。
 *
 * 目前的实现是内存队列:core:database(A7)尚未提供 location_buffer 表。
 * A7 的 Room 表就位后,新增一个 RoomLocationBuffer 实现并在 LocationModule 里换绑即可,
 * 管道其余部分零改动。
 */
interface LocationBuffer {

    suspend fun append(point: LocationPoint, context: LocationCaptureContext)

    /** 取出待上传的点,保持先进先出。 */
    suspend fun peek(limit: Int): List<BufferedPoint>

    suspend fun markUploaded(ids: List<Long>)

    suspend fun pendingCount(): Int

    suspend fun clear()
}

/**
 * 有界内存队列。进程被 ROM 杀掉就会丢,这是当前实现的已知代价;
 * 容量按「10 秒一个点 × 8 小时」留了余量,超出后丢最旧的点(最新轨迹比历史轨迹更有价值)。
 */
class InMemoryLocationBuffer(private val capacity: Int = DEFAULT_CAPACITY) : LocationBuffer {

    private val mutex = Mutex()
    private val queue = ArrayDeque<BufferedPoint>()
    private val sequence = AtomicLong(0)

    override suspend fun append(point: LocationPoint, context: LocationCaptureContext) = mutex.withLock {
        queue.addLast(BufferedPoint(sequence.incrementAndGet(), point, context))
        while (queue.size > capacity) queue.removeFirst()
    }

    override suspend fun peek(limit: Int): List<BufferedPoint> = mutex.withLock {
        queue.take(limit)
    }

    override suspend fun markUploaded(ids: List<Long>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val uploaded = ids.toHashSet()
        queue.removeAll { it.id in uploaded }
        Unit
    }

    override suspend fun pendingCount(): Int = mutex.withLock { queue.size }

    override suspend fun clear() = mutex.withLock { queue.clear() }

    private companion object {
        const val DEFAULT_CAPACITY = 3000
    }
}
