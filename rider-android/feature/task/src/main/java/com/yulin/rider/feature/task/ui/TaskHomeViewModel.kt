package com.yulin.rider.feature.task.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.feature.task.data.TaskBoard
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.TaskSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaskHomeUiState(
    val board: TaskBoard = TaskBoard(),
    val section: TaskSection = TaskSection.IN_PROGRESS,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** 离线/未装配时的横幅文案,不阻断操作。 */
    val notice: String? = null,
    val pendingSyncCount: Int = 0,
    val hasSyncFailure: Boolean = false,
)

class TaskHomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TaskRepository.get(app)
    private val _state = MutableStateFlow(TaskHomeUiState(loading = true))
    val state: StateFlow<TaskHomeUiState> = _state.asStateFlow()

    /** 骑手手动切过分区之后就不再自动跳,否则他刚点到「今日已完成」就会被弹回去。 */
    private var sectionPickedByRider = false

    init {
        viewModelScope.launch {
            repository.observeBoard().collect { board ->
                _state.value = _state.value.copy(
                    board = board,
                    loading = false,
                    // 打开就落在有活干的分区,单人场景不需要骑手自己找
                    section = if (sectionPickedByRider) {
                        _state.value.section
                    } else {
                        _state.value.section.takeIf { board.count(it) > 0 } ?: firstNonEmpty(board)
                    },
                )
            }
        }
        viewModelScope.launch {
            repository.pendingActions.collect { pending ->
                _state.value = _state.value.copy(
                    pendingSyncCount = pending.size,
                    hasSyncFailure = pending.any { it.stuck },
                )
            }
        }
        refresh()
    }

    fun selectSection(section: TaskSection) {
        sectionPickedByRider = true
        _state.value = _state.value.copy(section = section)
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch {
            val outcome = repository.refresh()
            runCatching { repository.syncNow() }
            _state.value = _state.value.copy(
                refreshing = false,
                loading = false,
                // 刷新失败不清空列表:缓存里的单照样能送,只是提示一下数据可能不是最新的
                notice = (outcome as? RiderResult.Failure)?.let { "离线中 · ${it.message}" },
            )
        }
    }

    fun retrySync() {
        viewModelScope.launch { runCatching { repository.retryStuck() } }
    }

    fun accept(taskId: Long) = viewModelScope.launch { repository.accept(taskId) }

    fun depart(taskId: Long) = viewModelScope.launch { repository.depart(taskId) }

    fun arrive(taskId: Long) = viewModelScope.launch { repository.arrive(taskId) }

    private fun firstNonEmpty(board: TaskBoard): TaskSection =
        TaskSection.entries.firstOrNull { board.count(it) > 0 } ?: TaskSection.IN_PROGRESS
}
