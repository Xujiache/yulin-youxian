package com.yulin.rider.feature.map

import androidx.compose.runtime.Composable

/**
 * 保留 Wave 0 的入口名,直接转发到真实地图页,避免已经接好这个名字的导航图断掉。
 * 新代码请直接用 [RiderMapScreen],并用 [buildRiderMapUiState] 传入波次路线与任务列表。
 */
@Deprecated(
    message = "改用 RiderMapScreen(state = buildRiderMapUiState(route, tasks, riderPoint, riderBearing))",
    replaceWith = ReplaceWith("RiderMapScreen(RiderMapUiState())"),
)
@Composable
fun RiderMapPlaceholderScreen() {
    RiderMapScreen(state = RiderMapUiState())
}
