package com.yulin.rider.feature.exception

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.database.EvidenceRecord
import com.yulin.rider.core.database.di.RiderDatabases
import com.yulin.rider.core.model.ExceptionCreate
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.network.RiderApis
import com.yulin.rider.core.network.ApiCaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 离线时写进 core/database 的 pending_action,由 feature/task 的 ActionSyncWorker 统一重放。
 *
 * 字段名必须与 feature/task 的 PendingActionPayload 对齐(那边解码开了 ignoreUnknownKeys,
 * 其余字段都有默认值,所以这里只带异常重放真正需要的几项)。
 * feature 之间不能互相依赖,这份重复是模块边界换来的代价;
 * 集成阶段把载荷类上收到 core/database 就能消掉。
 */
@Serializable
private data class QueuedExceptionPayload(
    val actionType: String = "EXCEPTION",
    val taskId: Long?,
    val createdAt: Long,
    val exception: ExceptionCreate,
    val localPhotoPaths: List<String> = emptyList(),
    val evidences: List<QueuedEvidencePayload> = emptyList(),
    val evidenceType: String = "EXCEPTION",
)

@Serializable
private data class QueuedEvidencePayload(
    val contentHash: String,
    val localPath: String,
    val capturedAt: String,
    val uploadedId: Long? = null,
)

data class ExceptionReportUiState(
    val selected: ExceptionKind? = null,
    val description: String = "",
    val photos: List<String> = emptyList(),
    val submitting: Boolean = false,
    /** 提交结果:在线时是服务端的 guidance,离线时是本地兜底文案。 */
    val result: ExceptionResult? = null,
    val error: String? = null,
)

data class ExceptionResult(
    val exceptionNo: String?,
    val guidance: String,
    val allowedNextActions: List<String>,
    val holdUntilAt: String?,
    val queuedOffline: Boolean,
)

class ExceptionReportViewModel(app: Application, private val taskId: Long) : AndroidViewModel(app) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val evidenceRepository = ExceptionEvidenceRepository(app)
    private val _state = MutableStateFlow(ExceptionReportUiState())
    val state: StateFlow<ExceptionReportUiState> = _state.asStateFlow()

    fun select(kind: ExceptionKind) {
        _state.value = _state.value.copy(selected = kind, error = null)
    }

    /**
     * 选错了重新挑。已拍的照片和已写的说明留着 ——
     * 现场还是那个现场,换个类型不代表要重拍一遍。
     */
    fun clearSelection() {
        _state.value = _state.value.copy(selected = null, error = null)
    }

    fun updateDescription(text: String) {
        _state.value = _state.value.copy(description = text)
    }

    fun addPhoto(path: String) {
        _state.value = _state.value.copy(photos = _state.value.photos + path)
    }

    fun removePhoto(path: String) {
        _state.value = _state.value.copy(photos = _state.value.photos - path)
    }

    /**
     * 先试在线提交:服务端的 guidance 与 allowedNextActions 是骑手现在最需要的东西。
     * 失败就转离线入队,绝不让骑手在楼道里因为没信号而无法上报。
     */
    fun submit() {
        val kind = _state.value.selected ?: return
        if (_state.value.submitting) return
        _state.value = _state.value.copy(submitting = true, error = null)

        viewModelScope.launch {
            val location = ExceptionFeature.currentLocation()
            val request = ExceptionCreate(
                clientEventId = UUID.randomUUID().toString(),
                clientEventAt = nowServerLocal(),
                taskId = taskId,
                exceptionType = kind.apiValue,
                description = _state.value.description.takeIf { it.isNotBlank() },
                location = location,
            )

            when (val online = submitOnline(kind, request, location)) {
                is OnlineSubmit.Success -> {
                    _state.value = _state.value.copy(submitting = false, result = online.result)
                }

                is OnlineSubmit.BusinessFailure -> {
                    // 照片必须留着。业务失败多半是异常类型选错或任务状态不对，
                    // 改完再提交一次就能过；这时把本地文件删掉，重试必然再失败，
                    // 而骑手已经离开那个门口，这张照片补不回来了。
                    // 已经传上去的那几张仍带着 uploadedId，重试不会重传。
                    _state.value = _state.value.copy(submitting = false, error = online.message)
                }

                is OnlineSubmit.Offline -> {
                    val queued = queueOffline(request, online.evidences)
                    _state.value = if (queued) {
                        _state.value.copy(
                            submitting = false,
                            result = ExceptionResult(
                                exceptionNo = null,
                                guidance = kind.offlineGuidance,
                                allowedNextActions = emptyList(),
                                holdUntilAt = null,
                                queuedOffline = true,
                            ),
                        )
                    } else {
                        _state.value.copy(
                            submitting = false,
                            error = "凭证保存失败，未加入离线队列，请检查存储空间后重试",
                        )
                    }
                }
            }
        }
    }

    private suspend fun submitOnline(
        kind: ExceptionKind,
        request: ExceptionCreate,
        location: GeoPoint?,
    ): OnlineSubmit {
        val apis = RiderApis.of(getApplication())
        val evidences = mutableListOf<EvidenceRecord>()
        for (path in _state.value.photos) {
            when (val upload = evidenceRepository.upload(path, taskId, location)) {
                is RiderResult.Success -> evidences += upload.data
                is RiderResult.Failure -> {
                    return if (upload.code == ApiCaller.NETWORK_ERROR_CODE) {
                        OnlineSubmit.Offline(evidences)
                    } else {
                        OnlineSubmit.BusinessFailure(upload.message)
                    }
                }
                RiderResult.Loading -> return OnlineSubmit.Offline(evidences)
            }
        }
        val result = apis.caller.result {
            apis.exception.createException(request.copy(evidenceIds = evidences.mapNotNull { it.uploadedId }))
        }
        return when (result) {
            is RiderResult.Success -> {
                evidenceRepository.complete(evidences)
                OnlineSubmit.Success(
                    ExceptionResult(
                        exceptionNo = result.data.exceptionNo,
                        guidance = result.data.guidance ?: kind.offlineGuidance,
                        allowedNextActions = result.data.allowedNextActions,
                        holdUntilAt = result.data.holdUntilAt,
                        queuedOffline = false,
                    )
                )
            }

            is RiderResult.Failure -> if (result.code == ApiCaller.NETWORK_ERROR_CODE) {
                OnlineSubmit.Offline(evidences)
            } else {
                OnlineSubmit.BusinessFailure(result.message)
            }

            RiderResult.Loading -> OnlineSubmit.Offline(evidences)
        }
    }

    private suspend fun queueOffline(
        request: ExceptionCreate,
        alreadyPrepared: List<EvidenceRecord>,
    ): Boolean {
        val byPath = alreadyPrepared.associateBy { it.localPath }.toMutableMap()
        val allEvidence = try {
            _state.value.photos.map { path ->
                byPath[path] ?: evidenceRepository.prepare(path).also { byPath[path] = it }
            }
        } catch (_: Exception) {
            return false
        }
        val payload = QueuedExceptionPayload(
            taskId = taskId,
            createdAt = System.currentTimeMillis(),
            exception = request,
            evidences = allEvidence.map {
                QueuedEvidencePayload(
                    contentHash = it.contentHash,
                    localPath = it.localPath,
                    capturedAt = it.capturedAt,
                    uploadedId = it.uploadedId,
                )
            },
        )
        return runCatching {
            RiderDatabases.offlineQueue(getApplication()).enqueue(
                clientEventId = request.clientEventId,
                actionType = "EXCEPTION",
                payloadJson = json.encodeToString(QueuedExceptionPayload.serializer(), payload),
                taskId = taskId,
            )
        }.getOrDefault(false)
    }

    private fun nowServerLocal(): String =
        LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    companion object {
        fun factory(taskId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return ExceptionReportViewModel(app, taskId) as T
            }
        }
    }

    private sealed interface OnlineSubmit {
        data class Success(val result: ExceptionResult) : OnlineSubmit
        data class Offline(val evidences: List<EvidenceRecord>) : OnlineSubmit
        data class BusinessFailure(val message: String) : OnlineSubmit
    }
}
