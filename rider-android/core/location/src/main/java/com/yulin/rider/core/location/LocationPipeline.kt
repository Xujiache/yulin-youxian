package com.yulin.rider.core.location

import android.content.Context
import android.util.Log
import com.yulin.rider.core.model.LocationBatchResponse
import com.yulin.rider.core.model.LocationPoint
import com.yulin.rider.core.model.ServerCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定位管道:数据源 → 清洗 → 采样 → 缓冲 → 批量上报。
 *
 * 放在 @Singleton 而不是 ViewModel:Activity 会被销毁,骑手锁屏骑车时 UI 早就没了(06 §3.1)。
 * 由 [LocationForegroundService] 驱动生命周期,UI 只观察这里的 StateFlow。
 */
@Singleton
class LocationPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buffer: LocationBuffer,
    private val uploader: LocationUploader,
    private val telemetry: DeviceTelemetry,
) {

    /** 常驻通知要显示的班次信息,由 A8 在上下班/任务变化时更新。 */
    data class ShiftDisplay(
        val shiftId: Long? = null,
        val onDutyAtMillis: Long? = null,
        val activeTaskCount: Int = 0,
    )

    private val sampler = LocationSampler()
    private val cleaner = TrackCleaner()

    private val _state = MutableStateFlow(LocationServiceState())
    val state: StateFlow<LocationServiceState> = _state.asStateFlow()

    private val _lastFix = MutableStateFlow<RiderLocationFix?>(null)
    val lastFix: StateFlow<RiderLocationFix?> = _lastFix.asStateFlow()

    private val _shift = MutableStateFlow(ShiftDisplay())
    val shift: StateFlow<ShiftDisplay> = _shift.asStateFlow()

    /** 服务端搭车下发的指令(REFRESH_TASKS / MARK_ARRIVED ...),A8 订阅后刷新任务列表。 */
    private val _commands = MutableSharedFlow<ServerCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<ServerCommand> = _commands.asSharedFlow()

    @Volatile
    private var currentTaskId: Long? = null

    @Volatile
    private var currentShiftId: Long? = null

    @Volatile
    private var currentWaveId: Long? = null

    private var source: RiderLocationSource? = null

    fun updateShift(shiftId: Long?, onDutyAtMillis: Long?, activeTaskCount: Int) {
        currentShiftId = shiftId
        _shift.value = ShiftDisplay(shiftId, onDutyAtMillis, activeTaskCount)
    }

    fun bindTask(taskId: Long?, waveId: Long?) {
        currentTaskId = taskId
        currentWaveId = waveId
    }

    /** 由前台服务在自己的作用域里调用,协程被取消即整体停机。 */
    suspend fun run() = coroutineScope {
        sampler.reset()
        cleaner.reset()
        uploader.resetBackoff()

        // 定位回调在主线程,清洗/采样是纯计算可以就地做,入库改由单消费者协程串行处理以保证顺序
        val inbox = Channel<CapturedPoint>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val sourceFixReceived = AtomicBoolean(false)

        // 骑手上班时可能还没打开系统定位开关。这里不放弃、隔一会儿重试,
        // 等他在保活向导里打开开关后服务能自己恢复,不用重新上班一次。
        var activeSource: RiderLocationSource? = null
        while (currentCoroutineContext().isActive && activeSource == null) {
            activeSource = openSource { fix ->
                sourceFixReceived.set(true)
                onFix(fix, inbox)
            }
            if (activeSource == null) {
                _state.update {
                    it.copy(running = false, lastError = "定位不可用,请检查手机定位开关,正在重试")
                }
                delay(SOURCE_RETRY_MILLIS)
            }
        }
        val started = activeSource ?: return@coroutineScope
        source = started
        _state.update {
            it.copy(
                running = true,
                sourceType = started.type,
                intervalSeconds = sampler.currentIntervalSeconds(),
                lastError = null,
            )
        }

        val writer = launch {
            for (captured in inbox) {
                buffer.append(captured.point, captured.context)
                if (isActivelyDelivering() && captured.point.motionState == "RIDING") {
                    uploader.requestImmediateFlush()
                }
                val pending = buffer.pendingCount()
                _state.update { it.copy(pendingUploadCount = pending) }
            }
        }
        val fallbackMonitor = if (started.type == LocationSourceType.AMAP) {
            launch {
                delay(AMAP_FIRST_FIX_TIMEOUT_MILLIS)
                if (!sourceFixReceived.get() && currentCoroutineContext().isActive) {
                    Log.w(TAG, "高德定位首点超时，回落系统 LocationManager")
                    started.stop()
                    val system = SystemLocationSource(context)
                    val switched = system.start(
                        intervalMillis = sampler.currentIntervalSeconds() * 1000L,
                        onFix = { fix -> onFix(fix, inbox) },
                        onError = { message -> _state.update { it.copy(lastError = message) } },
                    )
                    if (switched) {
                        source = system
                        _state.update {
                            it.copy(
                                running = true,
                                sourceType = LocationSourceType.SYSTEM,
                                lastError = "高德定位不可用，已切换系统定位",
                            )
                        }
                    }
                }
            }
        } else {
            null
        }

        try {
            uploadLoop()
        } finally {
            fallbackMonitor?.cancel()
            writer.cancel()
            inbox.close()
            closeSource()
            _state.update { it.copy(running = false, sourceType = null) }
        }
    }

    private fun onFix(fix: RiderLocationFix, inbox: Channel<CapturedPoint>) {
        _lastFix.value = fix
        when (val result = cleaner.clean(fix)) {
            is TrackCleaner.Result.Rejected -> {
                _state.update { it.copy(lastFixAtMillis = fix.locatedAtMillis) }
                Log.d(TAG, "丢弃定位点:${result.cause.label}")
            }

            is TrackCleaner.Result.Accepted -> {
                val clean = result.fix
                val previousInterval = sampler.currentIntervalSeconds()
                if (sampler.offer(clean)) {
                    inbox.trySend(
                        CapturedPoint(
                            point = clean.toPoint(sampler.motionState),
                            context = LocationCaptureContext(
                                shiftId = currentShiftId,
                                taskId = currentTaskId,
                                waveId = currentWaveId,
                            ),
                        )
                    )
                }
                val interval = sampler.currentIntervalSeconds()
                if (interval != previousInterval) {
                    source?.updateInterval(interval * 1000L)
                }
                _state.update {
                    it.copy(
                        motionState = sampler.motionState,
                        intervalSeconds = interval,
                        lastFixAtMillis = clean.locatedAtMillis,
                    )
                }
            }
        }
    }

    private suspend fun uploadLoop() {
        while (currentCoroutineContext().isActive) {
            uploader.awaitNextSlot(isActivelyDelivering())
            val outcome = try {
                uploader.uploadOnce(currentShiftId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "上传循环异常", e)
                LocationUploader.Outcome(success = false, error = e.message)
            }

            outcome.response?.let { applyServerResponse(it) }
            val pending = buffer.pendingCount()
            if (outcome.success) {
                _state.update {
                    it.copy(
                        lastUploadOkAtMillis = System.currentTimeMillis(),
                        pendingUploadCount = pending,
                        lastError = null,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        pendingUploadCount = pending,
                        lastError = outcome.error ?: "位置上报失败",
                    )
                }
            }
        }
    }

    private suspend fun applyServerResponse(response: LocationBatchResponse) {
        response.nextIntervalSeconds?.let { seconds ->
            sampler.applyServerInterval(seconds)
            source?.updateInterval(sampler.currentIntervalSeconds() * 1000L)
        }
        response.commands.forEach { _commands.emit(it) }
    }

    private fun isActivelyDelivering(): Boolean {
        return currentTaskId != null || _shift.value.activeTaskCount > 0
    }

    private fun openSource(onFix: (RiderLocationFix) -> Unit): RiderLocationSource? {
        val intervalMillis = sampler.currentIntervalSeconds() * 1000L
        val candidates = buildList {
            if (AmapKeyState.isMapAvailable(context)) add(AmapLocationSource(context))
            add(SystemLocationSource(context))
        }
        for (candidate in candidates) {
            if (!candidate.isAvailable()) continue
            val started = candidate.start(
                intervalMillis = intervalMillis,
                onFix = onFix,
                onError = { message ->
                    _state.update { it.copy(lastError = message) }
                    Log.w(TAG, message)
                },
            )
            if (started) return candidate
            candidate.stop()
        }
        return null
    }

    private fun closeSource() {
        runCatching { source?.stop() }
        source = null
    }

    private fun RiderLocationFix.toPoint(motionState: MotionState) = LocationPoint(
        lat = lat,
        lng = lng,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        bearing = bearing,
        altitude = altitude,
        provider = provider,
        motionState = motionState.serverValue,
        batteryLevel = telemetry.batteryLevel(),
        networkType = telemetry.networkType(),
        locatedAt = DeliveryTime.isoLocal(locatedAtMillis),
    )

    private data class CapturedPoint(
        val point: LocationPoint,
        val context: LocationCaptureContext,
    )

    private companion object {
        const val TAG = "LocationPipeline"
        const val SOURCE_RETRY_MILLIS = 15_000L
        const val AMAP_FIRST_FIX_TIMEOUT_MILLIS = 12_000L
    }
}
