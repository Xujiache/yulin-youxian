package com.yulin.rider.feature.task.data

import android.content.Context
import androidx.room.withTransaction
import com.yulin.rider.core.database.OfflineActionQueue
import com.yulin.rider.core.database.PendingActionEntity
import com.yulin.rider.core.database.di.RiderDatabases
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.model.TaskTransitionRequest
import com.yulin.rider.core.network.BusinessException
import com.yulin.rider.core.network.RiderApis
import com.yulin.rider.core.network.RiderErrorCodes
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID

/** 队列里的一条动作,把 payloadJson 解开成本模块用得上的形状。 */
data class PendingAction(
    val clientEventId: String,
    val payload: PendingActionPayload,
    val attemptCount: Int,
    val stuck: Boolean,
)

/**
 * 离线写队列的业务侧(06 §3.3)。
 *
 * 排队与顺序性由 core/database 的 [OfflineActionQueue] 负责(FIFO + 同任务串行都在一条 SQL 里);
 * 这里只做两件事:把业务动作序列化进队列,以及在有网时把队头翻译成具体的 API 调用。
 */
class PendingActionQueue private constructor(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val replayLock = Mutex()

    private val queue: OfflineActionQueue get() = RiderDatabases.offlineQueue(appContext)

    val pending: Flow<List<PendingAction>>
        get() = queue.all.map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * 写队列并立刻返回,调用方随即做乐观更新——这一步不能等网络。
     * 返回 null 表示同一幂等键已在队列里(重复点击),调用方无需再动 UI。
     *
     * 不在这里触发重放:重放成功会清掉乐观标记,必须等调用方把乐观状态写完再排。
     */
    suspend fun enqueue(payload: PendingActionPayload): String? {
        val clientEventId = payload.transition?.clientEventId
            ?: payload.exception?.clientEventId
            ?: UUID.randomUUID().toString()
        val clientEventAt = RiderFormats.parseEpochMillis(
            payload.transition?.clientEventAt ?: payload.exception?.clientEventAt
        ) ?: System.currentTimeMillis()

        val accepted = queue.enqueue(
            clientEventId = clientEventId,
            actionType = payload.actionType,
            payloadJson = json.encodeToString(PendingActionPayload.serializer(), payload),
            taskId = payload.taskId,
            waveId = payload.waveId,
            clientEventAt = clientEventAt,
            lat = payload.lat,
            lng = payload.lng,
        )
        return if (accepted) clientEventId else null
    }

    fun scheduleSync() = ActionSyncWorker.enqueueNow(appContext)

    /**
     * 重放一轮。[OfflineActionQueue.nextBatch] 每个任务组只放出队头,
     * 失败的那条不删除,于是自动阻塞了同一任务的后续动作,其他任务照常推进。
     */
    suspend fun replay(): ReplayResult = replayLock.withLock {
        var success = 0
        var failed = 0
        var rolledBack = 0
        for (row in queue.nextBatch()) {
            if (row.isStuck) continue
            val decoded = row.toDomain()
            if (decoded == null) {
                // 载荷解不出来就永远重放不了；删除与 dirty 清理仍必须在同一 Room 事务。
                finishCorruptAction(row)
                continue
            }
            val action = try {
                decoded.copy(payload = migrateLegacyEvidences(decoded))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                queue.markFailure(row.clientEventId, error.message)
                failed++
                continue
            }
            val execution = runCatching { execute(action) }
            if (execution.isSuccess) {
                finishSuccessfulAction(row, action)
                RiderDatabases.evidenceRegistry(appContext).complete(
                    execution.getOrThrow().map { it.contentHash }
                )
                success++
            } else {
                val error = execution.exceptionOrNull()
                if (error is CancellationException) throw error
                if (error is BusinessException && RiderErrorCodes.isTerminalActionFailure(error.code)) {
                    val discardedEvidenceHashes = rollbackTerminalGroup(row, action)
                    RiderDatabases.evidenceRegistry(appContext)
                        .complete(discardedEvidenceHashes)
                    rolledBack++
                } else {
                    queue.markFailure(row.clientEventId, error?.message)
                    failed++
                }
            }
        }
        ReplayResult(success, failed, rolledBack)
    }

    /** 骑手在「同步异常」提示里手动重试:清零退避计数后再跑一轮。 */
    suspend fun retryStuck(): ReplayResult {
        queue.snapshot().filter { it.isStuck }.forEach { queue.retryNow(it.clientEventId) }
        return replay()
    }

    /** 失败时抛异常,让 [replay] 把原因写进 lastError,骑手能看到到底卡在哪。 */
    private suspend fun execute(action: PendingAction): List<QueuedEvidence> {
        val apis = RiderApis.of(appContext)
        var payload = action.payload

        // 照片走独立队列:体积大、可乱序,先换成 evidenceId 再带进主动作
        val evidenceIds = mutableListOf<Long>()
        payload.transition?.evidenceIds?.let(evidenceIds::addAll)
        payload.exception?.evidenceIds?.let(evidenceIds::addAll)
        for (queued in payload.evidences) {
            val registry = RiderDatabases.evidenceRegistry(appContext)
            val record = registry.find(queued.contentHash)
            val id = queued.uploadedId ?: record?.uploadedId ?: EvidenceUploader.upload(
                    api = apis.exception,
                    caller = apis.caller,
                    path = queued.localPath,
                    evidenceType = payload.evidenceType ?: "DELIVERED",
                    taskId = payload.taskId,
                    capturedAt = queued.capturedAt,
                    contentHash = queued.contentHash,
                    location = payload.lat?.let { lat -> payload.lng?.let { lng -> GeoPoint(lat, lng) } },
                ).also { uploadedId ->
                    registry.markUploaded(queued.contentHash, uploadedId)
                    // 上传 ID 必须先于主动作持久化；主动作超时或进程在下一行被杀时，重启不会重传图片。
                    payload = payload.copy(
                        evidences = payload.evidences.map {
                            if (it.contentHash == queued.contentHash) it.copy(uploadedId = uploadedId) else it
                        }
                    )
                    persistPayload(action.clientEventId, payload)
                }
            evidenceIds += id
        }

        val taskId = payload.taskId
        val transition = payload.transition
        // 这些接口回的是任务卡片,这里只关心业务 code 是否成功,卡片本身由下一次同步覆盖,
        // 所以用 dataOrNull 而不是 ok(ok 只接受 ApiResponse<Unit>)。
        when (payload.actionType) {
            PendingActionTypes.ACCEPT ->
                apis.caller.dataOrNull { apis.task.accept(requireNotNull(taskId), requireNotNull(transition)) }

            PendingActionTypes.PICKUP ->
                apis.caller.dataOrNull {
                    apis.task.pickupWave(requireNotNull(payload.waveId), requireNotNull(transition))
                }

            PendingActionTypes.DEPART ->
                apis.caller.dataOrNull { apis.task.depart(requireNotNull(taskId), requireNotNull(transition)) }

            PendingActionTypes.ARRIVE ->
                apis.caller.dataOrNull { apis.task.arrive(requireNotNull(taskId), requireNotNull(transition)) }

            PendingActionTypes.DELIVER ->
                apis.caller.dataOrNull {
                    apis.task.deliver(
                        requireNotNull(taskId),
                        requireNotNull(transition).withEvidences(evidenceIds),
                    )
                }

            PendingActionTypes.EXCEPTION ->
                apis.caller.data {
                    apis.exception.createException(
                        requireNotNull(payload.exception).copy(evidenceIds = evidenceIds)
                    )
                }
        }
        return payload.evidences
    }

    private suspend fun migrateLegacyEvidences(action: PendingAction): PendingActionPayload {
        val payload = action.payload
        if (payload.evidences.isNotEmpty() || payload.localPhotoPaths.isEmpty()) return payload
        val registry = RiderDatabases.evidenceRegistry(appContext)
        val migrated = payload.localPhotoPaths.map { path ->
            val record = registry.prepare(path)
            QueuedEvidence(
                contentHash = record.contentHash,
                localPath = record.localPath,
                capturedAt = record.capturedAt,
                uploadedId = record.uploadedId,
            )
        }
        return payload.copy(localPhotoPaths = emptyList(), evidences = migrated)
            .also { persistPayload(action.clientEventId, it) }
    }

    private suspend fun persistPayload(clientEventId: String, payload: PendingActionPayload) {
        queue.updatePayload(
            clientEventId,
            json.encodeToString(PendingActionPayload.serializer(), payload),
        )
    }

    private suspend fun finishSuccessfulAction(row: PendingActionEntity, action: PendingAction) {
        val db = RiderDatabases.of(appContext)
        val waveId = row.waveId
        val taskId = row.taskId
        db.withTransaction {
            val pendingDao = db.pendingActionDao()
            val taskDao = db.taskCacheDao()
            pendingDao.delete(row.clientEventId)
            val hasFollowing = when {
                waveId != null -> pendingDao.findByWave(waveId).isNotEmpty()
                taskId != null -> pendingDao.findByTask(taskId).isNotEmpty()
                else -> pendingDao.getAll().isNotEmpty()
            }
            if (!hasFollowing) {
                affectedTaskIds(db, row, action).forEach { taskDao.clearDirty(it) }
            }
        }
    }

    private suspend fun rollbackTerminalGroup(
        row: PendingActionEntity,
        action: PendingAction,
    ): List<String> {
        val db = RiderDatabases.of(appContext)
        val waveId = row.waveId
        val taskId = row.taskId
        val groupRows = when {
            waveId != null -> db.pendingActionDao().findByWave(waveId)
            taskId != null -> db.pendingActionDao().findByTask(taskId)
            else -> db.pendingActionDao().getAll()
        }
        val evidenceHashes = groupRows.mapNotNull { it.toDomain() }
            .flatMap { it.payload.evidences }
            .map { it.contentHash }
        db.withTransaction {
            val pendingDao = db.pendingActionDao()
            when {
                waveId != null -> pendingDao.deleteByWave(waveId)
                taskId != null -> pendingDao.deleteByTask(taskId)
                else -> pendingDao.clear()
            }
            val taskDao = db.taskCacheDao()
            val snapshots = action.payload.optimisticStates
            if (snapshots.isNotEmpty()) {
                snapshots.forEach {
                    taskDao.restoreStatus(
                        taskId = it.taskId,
                        status = it.status,
                        statusText = it.statusText,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                val restored = snapshots.mapTo(mutableSetOf()) { it.taskId }
                affectedTaskIds(db, row, action)
                    .filterNot { it in restored }
                    .forEach { taskDao.clearDirty(it) }
            } else {
                affectedTaskIds(db, row, action).forEach { taskDao.clearDirty(it) }
            }
        }
        return evidenceHashes
    }

    private suspend fun finishCorruptAction(row: PendingActionEntity) {
        val db = RiderDatabases.of(appContext)
        val taskId = row.taskId
        val waveId = row.waveId
        db.withTransaction {
            db.pendingActionDao().delete(row.clientEventId)
            val taskDao = db.taskCacheDao()
            when {
                taskId != null -> taskDao.clearDirty(taskId)
                waveId != null -> taskDao.findByWave(waveId).forEach { taskDao.clearDirty(it.taskId) }
            }
        }
    }

    private suspend fun affectedTaskIds(
        db: com.yulin.rider.core.database.RiderDatabase,
        row: PendingActionEntity,
        action: PendingAction,
    ): List<Long> {
        val waveId = row.waveId
        if (waveId != null) return db.taskCacheDao().findByWave(waveId).map { it.taskId }
        val snapshots = action.payload.optimisticStates.map { it.taskId }
        if (snapshots.isNotEmpty()) return snapshots
        val taskId = row.taskId
        return when {
            taskId != null -> listOf(taskId)
            else -> emptyList()
        }
    }

    private fun PendingActionEntity.toDomain(): PendingAction? = runCatching {
        PendingAction(
            clientEventId = clientEventId,
            payload = json.decodeFromString(PendingActionPayload.serializer(), payloadJson),
            attemptCount = attemptCount,
            stuck = isStuck,
        )
    }.getOrNull()

    private fun TaskTransitionRequest.withEvidences(ids: List<Long>): TaskTransitionRequest =
        if (ids.isEmpty()) this else copy(evidenceIds = ids)

    data class ReplayResult(
        val success: Int,
        /** 仅统计可重试失败；终态回滚不会让 WorkManager 无限重试。 */
        val failed: Int,
        val rolledBack: Int = 0,
    )

    companion object {
        @Volatile
        private var instance: PendingActionQueue? = null

        fun get(context: Context): PendingActionQueue = instance ?: synchronized(this) {
            instance ?: PendingActionQueue(context.applicationContext).also { instance = it }
        }
    }
}
