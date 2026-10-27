package com.yulin.rider.feature.shift

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.model.OffDutyRequest
import com.yulin.rider.core.model.OnDutyChecks
import com.yulin.rider.core.model.OnDutyRequest
import com.yulin.rider.core.model.ShiftCurrent
import com.yulin.rider.core.network.RiderApis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ShiftUiState(
    val shift: ShiftCurrent = ShiftCurrent(),
    /** 服务端 onlineSeconds 只在拉取那一刻准确,本地按秒补齐,顶部时长才不会卡住。 */
    val liveOnlineSeconds: Long = 0,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val checklist: OnDutyChecklist? = null,
    val message: String? = null,
)

/**
 * 上下班。
 *
 * 疲劳管控按客户要求关闭:不做 4 小时提示条、8 小时确认弹窗、12 小时强制下班。
 * ShiftCurrent.fatigue 字段照收不误,但不驱动任何 UI —— 需要恢复时在这里加回来即可。
 */
class ShiftViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ShiftUiState())
    val state: StateFlow<ShiftUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _state.value
                if (current.shift.onDuty) {
                    _state.value = current.copy(liveOnlineSeconds = current.liveOnlineSeconds + 1)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            when (val result = apis.caller.result { apis.shift.getCurrentShift() }) {
                is RiderResult.Success -> _state.value = _state.value.copy(
                    loading = false,
                    shift = result.data,
                    liveOnlineSeconds = result.data.onlineSeconds,
                )

                is RiderResult.Failure -> _state.value = _state.value.copy(loading = false)
                RiderResult.Loading -> Unit
            }
        }
    }

    /** 点「上班」先出清单,骑手确认后才真正提交。 */
    fun openChecklist() {
        _state.value = _state.value.copy(
            checklist = OnDutyChecklist.from(ShiftFeature.depsOrNull()?.keepAliveStatus())
        )
    }

    fun dismissChecklist() {
        _state.value = _state.value.copy(checklist = null)
    }

    fun recheck() = openChecklist()

    fun confirmOnDuty() {
        val checklist = _state.value.checklist ?: return
        if (checklist.blocked) return
        val deps = ShiftFeature.depsOrNull()
        if (deps?.onShiftStartRequested() == false) {
            _state.value = _state.value.copy(message = "定位前台服务未能启动，请保持 App 在前台后重试")
            return
        }
        _state.value = _state.value.copy(submitting = true)

        viewModelScope.launch {
            var confirmed = false
            try {
                val apis = RiderApis.of(getApplication())
                val request = OnDutyRequest(
                    deviceId = deps?.deviceId.orEmpty(),
                    checks = OnDutyChecks(
                        fineLocation = checklist.satisfiedOf("fineLocation"),
                        backgroundLocation = checklist.satisfiedOf("backgroundLocation"),
                        notification = checklist.satisfiedOf("notification"),
                        batteryOptimizationIgnored = checklist.satisfiedOf("batteryOptimizationIgnored"),
                        keepaliveGuideDone = checklist.satisfiedOf("keepaliveGuideDone"),
                        // 单人自营场景没有头盔签到流程,固定为已确认
                        helmetConfirmed = true,
                    ),
                    location = deps?.currentLocation(),
                )
                when (val result = apis.caller.result { apis.shift.onDuty(request) }) {
                    is RiderResult.Success -> {
                        confirmed = true
                        deps?.onShiftStarted(result.data.shiftId, result.data.onDutyAt)
                        _state.value = _state.value.copy(
                            submitting = false,
                            checklist = null,
                            message = result.data.warnings.joinToString("；").takeIf { it.isNotBlank() },
                        )
                        refresh()
                    }

                    is RiderResult.Failure -> _state.value = _state.value.copy(
                        submitting = false,
                        message = result.message,
                    )

                    RiderResult.Loading -> Unit
                }
            } finally {
                if (!confirmed) deps?.onShiftStartFailed()
            }
        }
    }

    fun offDuty() {
        _state.value = _state.value.copy(submitting = true)
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            when (val result = apis.caller.result { apis.shift.offDuty(OffDutyRequest()) }) {
                is RiderResult.Success -> {
                    ShiftFeature.depsOrNull()?.onShiftStopped()
                    _state.value = _state.value.copy(submitting = false)
                    refresh()
                }

                // 1011 = 还有未完成任务,服务端会把待办带回来
                is RiderResult.Failure -> _state.value = _state.value.copy(
                    submitting = false,
                    message = result.message,
                )

                RiderResult.Loading -> Unit
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
