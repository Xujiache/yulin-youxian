package com.yulin.rider.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yulin.rider.core.location.LocationController
import com.yulin.rider.core.model.WaveRoute
import com.yulin.rider.feature.map.RiderMapScreen
import com.yulin.rider.feature.map.buildRiderMapUiState
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.TaskSection
import kotlinx.coroutines.launch

/**
 * 地图页的装配层。
 *
 * feature/map 是无状态的,数据来自 feature/task 的缓存(离线可读),骑手位置来自 core/location。
 * 两个 feature 不能互相依赖,所以这份拼装只能放在 app 层。
 */
@Composable
fun RiderMapRoute(
    locationController: LocationController,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenTask: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { TaskRepository.get(context) }
    val boardFlow = remember(repository) { repository.observeBoard() }

    val board by boardFlow.collectAsState(initial = null)
    val riderPoint by locationController.riderPoint.collectAsState(initial = null)
    val riderBearing by locationController.riderBearing.collectAsState(initial = null)

    // 地图跟着「正在跑的那一趟」走:单人单店同一时刻只会有一个波次在路上
    val wave = remember(board) {
        board?.waves?.firstOrNull { group -> group.stops.any { it.section == TaskSection.IN_PROGRESS } }
            ?: board?.waves?.firstOrNull()
    }
    val waveId = wave?.wave?.waveId

    val route by produceState<WaveRoute?>(initialValue = null, waveId) {
        value = null
        if (waveId != null) repository.observeWaveRoute(waveId).collect { value = it }
    }

    val tasks = remember(board, wave) {
        (wave?.stops ?: board?.standalone.orEmpty()).map { it.card }
    }

    RiderMapScreen(
        state = buildRiderMapUiState(
            route = route,
            tasks = tasks,
            riderPoint = riderPoint,
            riderBearing = riderBearing,
            loading = board == null,
        ),
        modifier = modifier,
        onBack = onBack,
        onRetry = { scope.launch { repository.refresh() } },
        onStopClick = { onOpenTask(it.taskId) },
    )
}
