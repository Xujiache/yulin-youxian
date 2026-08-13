package com.yulin.rider.feature.task.data

import android.content.Context
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.database.TaskCacheEntity
import com.yulin.rider.core.database.WaveCacheEntity
import com.yulin.rider.core.database.di.RiderDatabases
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.model.TaskCard
import com.yulin.rider.core.model.TaskDetail
import com.yulin.rider.core.model.TaskList
import com.yulin.rider.core.model.TaskTransitionRequest
import com.yulin.rider.core.model.WaveGroup
import com.yulin.rider.core.model.WaveRoute
import com.yulin.rider.core.network.RiderApis
import com.yulin.rider.feature.task.TaskFeature
import com.yulin.rider.feature.task.util.RiderFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

/** 一条任务在 UI 上需要的全部东西:服务端卡片 + 波次归属 + 本单里程 + 同步状态。 */
data class TaskCardUi(
    val card: TaskCard,
    val waveId: Long?,
    val waveNo: String?,
    val legDistanceMeters: Long?,
    val displayStatus: String,
    val pendingSync: Boolean,
    val syncFailed: Boolean,
) {
    val taskId: Long get() = card.taskId
    val section: TaskSection get() = TaskSection.of(displayStatus)
    val nextStep: NextStep get() = NextStep.of(displayStatus)

    /**
     * 状态中文文案优先用服务端下发的,本地枚举只作兜底。
     * 服务端将来加了 App 还不认识的状态时,骑手看到的仍是中文而不是原始英文枚举。
     */
    val displayStatusText: String
        get() = card.statusText?.takeIf { it.isNotBlank() } ?: TaskStatus.text(displayStatus)
}

data class WaveUi(
    val wave: WaveGroup,
    val stops: List<TaskCardUi>,
)

data class TaskBoard(
    val waves: List<WaveUi> = emptyList(),
    val standalone: List<TaskCardUi> = emptyList(),
) {
    val allTasks: List<TaskCardUi> get() = waves.flatMap { it.stops } + standalone

    fun section(section: TaskSection): List<TaskCardUi> = allTasks.filter { it.section == section }

    fun count(section: TaskSection): Int = section(section).size
}

/**
 * 任务数据入口。
 *
 * 读走 core/database 的缓存(离线可读),写走离线队列(断网可完成),
 * 网络刷新只是把缓存改写成服务端真值。
 */
class TaskRepository private constructor(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val taskDao get() = RiderDatabases.of(appContext).taskCacheDao()
    private val waveDao get() = RiderDatabases.of(appContext).waveCacheDao()
    private val queue = PendingActionQueue.get(appContext)

    val pendingActions: Flow<List<PendingAction>> get() = queue.pending

    fun observeBoard(): Flow<TaskBoard> =
        combine(taskDao.observeAll(), waveDao.observeAll(), queue.pending) { tasks, waves, pending ->
            buildBoard(tasks, waves, pending)
        }

    fun observeTask(taskId: Long): Flow<TaskCardUi?> =
        combine(taskDao.observeTask(taskId), waveDao.observeAll(), queue.pending) { entity, waves, pending ->
            entity?.let { toUi(it, pending, legDistancesOf(waves), waves.associate { w -> w.waveId to w.waveNo }) }
        }

    fun observeWave(waveId: Long): Flow<WaveUi?> = observeBoard().map { board ->
        board.waves.firstOrNull { it.wave.waveId == waveId }
    }

    /** 地图页要整份路线(门店起点 / polyline / 总里程),刷新时已随波次一起落库,断网照样读得到。 */
    fun observeWaveRoute(waveId: Long): Flow<WaveRoute?> = waveDao.observe(waveId).map { row ->
        row?.routeJson?.let {
            runCatching { json.decodeFromString(WaveRoute.serializer(), it) }.getOrNull()
        }
    }

    suspend fun refresh(): RiderResult<Unit> {
        val apis = RiderApis.of(appContext)
        return apis.caller.runCatchingApi {
            // status=null 只返回待接单 + 进行中(后端 riderTasks 的 default 分支),已完成要单独取一次
            val active = apis.caller.data { apis.task.getTasks(null) }
            val doneResult = runCatching {
                apis.caller.data { apis.task.getTasks(TaskSection.TODAY_DONE.apiValue) }
            }
            val done = doneResult.getOrDefault(TaskList())
            // 已完成这一路单独失败时（超时最常见）不能按「服务端说没有」处理，
            // 否则下面的 pruneExcept 会把本机所有已完成单删掉，骑手以为白干了一天。
            val doneReliable = doneResult.isSuccess

            // 路线整份缓存下来:「本单里程」和地图 polyline 都从它出,断网时仍在
            val routeJsons = mutableMapOf<Long, String>()
            val polylines = mutableMapOf<Long, String?>()
            for (wave in active.waves) {
                val route = runCatching {
                    apis.caller.data { apis.task.getWaveRoute(wave.waveId) }
                }.getOrNull() ?: continue
                routeJsons[wave.waveId] = json.encodeToString(WaveRoute.serializer(), route)
                polylines[wave.waveId] = route.polyline
            }

            val now = System.currentTimeMillis()
            val taskRows = mutableListOf<TaskCacheEntity>()
            val waveRows = mutableListOf<WaveCacheEntity>()

            for (data in listOf(active, done)) {
                for (wave in data.waves) {
                    waveRows += WaveCacheEntity(
                        waveId = wave.waveId,
                        waveNo = wave.waveNo,
                        status = wave.status,
                        taskCount = wave.taskCount,
                        completedCount = wave.completedCount,
                        polyline = polylines[wave.waveId],
                        routeJson = routeJsons[wave.waveId],
                        payloadJson = json.encodeToString(
                            WaveGroup.serializer(), wave.copy(stops = emptyList())
                        ),
                        updatedAt = now,
                    )
                    wave.stops.forEach { card -> taskRows += card.toEntity(wave.waveId, now) }
                }
                data.standaloneTasks.forEach { card -> taskRows += card.toEntity(null, now) }
            }

            val rows = taskRows.distinctBy { it.taskId }
            // 本地乐观状态还没同步上去时,不能被服务端的旧数据抹回去
            val dirty = rows.mapNotNull { row ->
                taskDao.findTask(row.taskId)?.takeIf { it.localDirty }?.taskId
            }.toSet()
            if (doneReliable) {
                taskDao.pruneExcept(rows.map { it.taskId })
            } else {
                // 只清理服务端确认还在进行中的那部分，已完成的先留着，下一轮再对齐
                taskDao.pruneExcept(rows.map { it.taskId } + taskDao.findFinishedTaskIds())
            }
            taskDao.upsert(rows.filterNot { it.taskId in dirty })
            waveDao.upsert(waveRows.distinctBy { it.waveId })
        }
    }

    /** 详情接口返回独立 DTO；同时把 card 刷进缓存，详细商品/事件/凭证交给详情 ViewModel。 */
    suspend fun refreshTask(taskId: Long): RiderResult<TaskDetail> {
        val apis = RiderApis.of(appContext)
        return apis.caller.runCatchingApi {
            val detail = apis.caller.data { apis.task.getTask(taskId) }
            val cached = taskDao.findTask(taskId)
            if (cached?.localDirty != true) {
                taskDao.upsert(detail.card.toEntity(cached?.waveId, System.currentTimeMillis()))
            }
            detail
        }
    }

    suspend fun syncNow(): PendingActionQueue.ReplayResult = queue.replay()

    suspend fun retryStuck(): PendingActionQueue.ReplayResult = queue.retryStuck()

    // ---- 状态流转:一律先入队再乐观更新,让骑手能马上做下一单 ----

    suspend fun accept(taskId: Long) = transition(PendingActionTypes.ACCEPT, taskId = taskId)

    suspend fun depart(taskId: Long) = transition(PendingActionTypes.DEPART, taskId = taskId)

    suspend fun arrive(taskId: Long) = transition(PendingActionTypes.ARRIVE, taskId = taskId)

    /** 单任务取货。没有波次的单走这条，否则会一直卡在「已接单」。 */
    suspend fun pickupTask(taskId: Long) = transition(PendingActionTypes.PICKUP, taskId = taskId)

    suspend fun pickupWave(waveId: Long, checkedTaskIds: List<Long>, actualPackageCount: Int) {
        // 乐观更新只能覆盖服务端真正会改的那几单：勾选的、且当前是「已接单」的。
        // 原来是整波次一把梭，结果没接单的、已经送达的都会被本地改成「已取货」，
        // 而服务端根本没动它们，界面就和真实状态脱节了。
        val inWave = taskDao.findByWave(waveId)
        val target = inWave
            .filter { checkedTaskIds.isEmpty() || it.taskId in checkedTaskIds }
            .filter { it.status == TaskStatus.ACCEPTED }
            .map { it.taskId }
        transition(
            actionType = PendingActionTypes.PICKUP,
            waveId = waveId,
            optimisticTaskIds = target,
            extra = { it.copy(checkedTaskIds = checkedTaskIds, actualPackageCount = actualPackageCount) },
        )
    }

    suspend fun deliver(
        taskId: Long,
        photoPaths: List<String>,
        receiveMethod: ReceiveMethod,
        verifyCode: String?,
    ) = transition(
        actionType = PendingActionTypes.DELIVER,
        taskId = taskId,
        photoPaths = photoPaths,
        // 必须是服务端 EvidenceService.ALLOWED_TYPES 里的值,写错会被静默归成 EXCEPTION,
        // 送达照片就会跑到异常凭证里去
        evidenceType = "DELIVERED",
        extra = {
            it.copy(
                receiveMethod = receiveMethod.apiValue,
                verifyCode = verifyCode?.takeIf(String::isNotBlank),
            )
        },
    )

    private suspend fun transition(
        actionType: String,
        taskId: Long? = null,
        waveId: Long? = null,
        photoPaths: List<String> = emptyList(),
        evidenceType: String? = null,
        optimisticTaskIds: List<Long> = listOfNotNull(taskId),
        extra: (TaskTransitionRequest) -> TaskTransitionRequest = { it },
    ) {
        val location: GeoPoint? = TaskFeature.currentLocation()
        val currentRows = optimisticTaskIds.mapNotNull { taskDao.findTask(it) }
        val actionWaveId = waveId ?: taskId?.let { id -> taskDao.findTask(id)?.waveId }
        val evidenceRecords = photoPaths.map { path ->
            RiderDatabases.evidenceRegistry(appContext).prepare(path)
        }
        val request = extra(
            TaskTransitionRequest(
                clientEventId = UUID.randomUUID().toString(),
                clientEventAt = RiderFormats.nowServerLocal(),
                location = location,
            )
        )
        val enqueued = queue.enqueue(
            PendingActionPayload(
                actionType = actionType,
                taskId = taskId,
                waveId = actionWaveId,
                lat = location?.lat,
                lng = location?.lng,
                createdAt = System.currentTimeMillis(),
                transition = request,
                evidences = evidenceRecords.map {
                    QueuedEvidence(
                        contentHash = it.contentHash,
                        localPath = it.localPath,
                        capturedAt = it.capturedAt,
                        uploadedId = it.uploadedId,
                    )
                },
                evidenceType = evidenceType,
                optimisticStates = currentRows.map {
                    OptimisticTaskSnapshot(
                        taskId = it.taskId,
                        status = it.status,
                        statusText = it.statusText,
                    )
                },
            )
        ) ?: return

        // 乐观状态同时写进缓存:进程被杀重启后骑手看到的仍是他按过的那一步。
        // 必须排在重放调度之前,否则重放先成功会把还没写下的乐观标记清掉。
        val now = System.currentTimeMillis()
        currentRows.forEach { current ->
            val next = optimisticStatusAfter(actionType, current.status)
            taskDao.markLocalStatus(current.taskId, next, TaskStatus.text(next), now)
        }
        queue.scheduleSync()
    }

    // ---- 缓存 ⇄ UI 模型 ----

    /**
     * 「本单里程」不在 TaskCard 里,只有波次路线给得出。
     * 路线整份存在 wave_cache.routeJson,这里解出来按 taskId 摊平,不用再加一列。
     */
    private fun legDistancesOf(waves: List<WaveCacheEntity>): Map<Long, Long> = buildMap {
        waves.forEach { row ->
            val route = row.routeJson?.let {
                runCatching { json.decodeFromString(WaveRoute.serializer(), it) }.getOrNull()
            } ?: return@forEach
            route.stops.forEach { put(it.taskId, it.legDistanceMeters) }
        }
    }

    private fun buildBoard(
        tasks: List<TaskCacheEntity>,
        waves: List<WaveCacheEntity>,
        pending: List<PendingAction>,
    ): TaskBoard {
        val legs = legDistancesOf(waves)
        val waveNos = waves.associate { it.waveId to it.waveNo }
        val cards = tasks.mapNotNull { toUi(it, pending, legs, waveNos) }
        val waveGroups = waves.mapNotNull { row ->
            runCatching { json.decodeFromString(WaveGroup.serializer(), row.payloadJson) }.getOrNull()
        }
        val byWave = cards.filter { it.waveId != null }.groupBy { it.waveId }
        return TaskBoard(
            waves = waveGroups
                .map { wave -> WaveUi(wave, byWave[wave.waveId].orEmpty().sortedBy { it.card.seqNo ?: 0 }) }
                .filter { it.stops.isNotEmpty() },
            standalone = cards.filter { it.waveId == null },
        )
    }

    private fun toUi(
        entity: TaskCacheEntity,
        pending: List<PendingAction>,
        legs: Map<Long, Long>,
        waveNos: Map<Long, String?>,
    ): TaskCardUi? {
        val card = runCatching { json.decodeFromString(TaskCard.serializer(), entity.payloadJson) }
            .getOrNull() ?: return null
        // 缓存里的 status 已经含乐观更新;队列只用来判断「在同步中 / 卡住了」
        val related = pending.filter {
            it.payload.taskId == entity.taskId ||
                (entity.waveId != null && it.payload.waveId == entity.waveId)
        }
        return TaskCardUi(
            // statusText 跟着 status 一起取本地缓存值:乐观更新时 markLocalStatus 会把两者一起改,
            // 只覆盖 status 会让文案停留在服务端的上一个状态。
            card = card.copy(status = entity.status, statusText = entity.statusText),
            waveId = entity.waveId,
            waveNo = entity.waveId?.let { waveNos[it] },
            legDistanceMeters = legs[entity.taskId],
            displayStatus = entity.status,
            pendingSync = related.isNotEmpty(),
            syncFailed = related.any { it.stuck },
        )
    }

    private fun TaskCard.toEntity(waveId: Long?, now: Long) = TaskCacheEntity(
        taskId = taskId,
        taskNo = taskNo,
        waveId = waveId,
        seqNo = seqNo,
        status = status,
        statusText = statusText,
        receiverName = receiverName,
        addressLabel = addressDetail ?: areaLabel,
        lat = location?.lat,
        lng = location?.lng,
        promisedAtMillis = RiderFormats.parseEpochMillis(promisedAt),
        etaAtMillis = RiderFormats.parseEpochMillis(etaAt),
        payloadJson = json.encodeToString(TaskCard.serializer(), this),
        updatedAt = now,
        localDirty = false,
    )

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        fun get(context: Context): TaskRepository = instance ?: synchronized(this) {
            instance ?: TaskRepository(context.applicationContext).also { instance = it }
        }
    }
}
