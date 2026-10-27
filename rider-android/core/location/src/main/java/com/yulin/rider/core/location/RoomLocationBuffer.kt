package com.yulin.rider.core.location

import com.yulin.rider.core.database.LocationBufferDao
import com.yulin.rider.core.database.LocationBufferEntity
import com.yulin.rider.core.model.LocationPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 位置缓冲的持久化实现,落 core:database 的 location_buffer 表。
 *
 * 换掉内存队列的理由:地下车库、电梯、老小区楼道既是最容易断网的地方,也是国产 ROM 最容易
 * 回收后台进程的时候。内存队列一旦随进程消失,这段轨迹就永远补不回来,
 * 而「骑手说送到了但轨迹上没有」正是事后最难说清的纠纷。
 *
 * 管道其余部分零改动:[LocationBuffer] 的五个方法语义保持不变,只是换了存储介质。
 */
class RoomLocationBuffer(private val dao: LocationBufferDao) : LocationBuffer {

    private val appendsSinceTrim = AtomicInteger(0)
    private val fallbackMutex = Mutex()
    private val fallback = ArrayDeque<BufferedPoint>()
    private val fallbackIds = AtomicLong(0)

    override suspend fun append(point: LocationPoint, context: LocationCaptureContext) {
        try {
            dao.insert(point.toEntity(context))
            // 每个点都修剪就是每 10 秒一次带子查询的 DELETE,攒一批再做
            if (appendsSinceTrim.incrementAndGet() >= TRIM_EVERY) {
                appendsSinceTrim.set(0)
                dao.trimPending(MAX_PENDING)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            // SQLite 磁盘满/锁损坏不能把整个前台服务一并打死；本进程内先降级为有界内存缓冲。
            Log.e(TAG, "Room 位置写入失败，临时降级到内存", error)
            fallbackMutex.withLock {
                fallback.addLast(
                    BufferedPoint(
                        id = fallbackIds.decrementAndGet(),
                        point = point,
                        context = context,
                    )
                )
                while (fallback.size > MAX_FALLBACK) fallback.removeFirst()
            }
        }
    }

    override suspend fun peek(limit: Int): List<BufferedPoint> {
        val persisted = try {
            dao.pendingUpload(limit).map { it.toBufferedPoint() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            Log.e(TAG, "Room 位置读取失败，继续处理内存缓冲", error)
            emptyList()
        }
        if (persisted.size >= limit) return persisted
        val memory = fallbackMutex.withLock { fallback.take(limit - persisted.size) }
        return persisted + memory
    }

    override suspend fun markUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val persisted = ids.filter { it > 0L }
        if (persisted.isNotEmpty()) {
            try {
                dao.markUploaded(persisted, now)
                // 已上传的点保留一小时再清,便于当场排查轨迹纠纷(06 §3.9)
                dao.purgeUploadedBefore(now - RETENTION_MILLIS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.e(TAG, "Room 位置确认失败，保留记录等待重试", error)
            }
        }
        val memoryIds = ids.filter { it < 0L }.toHashSet()
        if (memoryIds.isNotEmpty()) {
            fallbackMutex.withLock { fallback.removeAll { it.id in memoryIds } }
        }
    }

    override suspend fun pendingCount(): Int {
        val persisted = try {
            dao.pendingCount()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            Log.e(TAG, "Room 位置计数失败", error)
            0
        }
        return persisted + fallbackMutex.withLock { fallback.size }
    }

    override suspend fun clear() {
        appendsSinceTrim.set(0)
        runCatching { dao.clear() }.onFailure { Log.e(TAG, "Room 位置清理失败", it) }
        fallbackMutex.withLock { fallback.clear() }
    }

    private fun LocationPoint.toEntity(context: LocationCaptureContext) = LocationBufferEntity(
        lat = lat,
        lng = lng,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        bearing = bearing,
        altitude = altitude,
        provider = provider,
        motionState = motionState,
        batteryLevel = batteryLevel,
        networkType = networkType,
        // 表里按毫秒排序以保证轨迹先后,取出时再还原成 04 §0 约定的本地时间串
        locatedAt = DeliveryTime.epochMillis(locatedAt) ?: System.currentTimeMillis(),
        shiftId = context.shiftId,
        taskId = context.taskId,
        waveId = context.waveId,
    )

    private fun LocationBufferEntity.toBufferedPoint() = BufferedPoint(
        id = id,
        point = LocationPoint(
            lat = lat,
            lng = lng,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            bearing = bearing,
            altitude = altitude,
            provider = provider,
            motionState = motionState,
            batteryLevel = batteryLevel,
            networkType = networkType,
            locatedAt = DeliveryTime.isoLocal(locatedAt),
        ),
        context = LocationCaptureContext(
            shiftId = shiftId,
            taskId = taskId,
            waveId = waveId,
        ),
    )

    private companion object {
        /** 10 秒一个点、连续断网 8 小时约 2900 个,超出后丢最旧的(最新轨迹更有价值)。 */
        const val MAX_PENDING = 3000
        const val MAX_FALLBACK = 300
        const val TRIM_EVERY = 50
        const val RETENTION_MILLIS = 60 * 60 * 1000L
        const val TAG = "RoomLocationBuffer"
    }
}
