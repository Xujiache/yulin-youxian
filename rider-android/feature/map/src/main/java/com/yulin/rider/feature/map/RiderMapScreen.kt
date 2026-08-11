package com.yulin.rider.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshEmpty
import com.yulin.rider.core.designsystem.FreshError
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshPageHeader
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSecondaryButton
import com.yulin.rider.core.designsystem.FreshShell
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.location.AmapKeyState
import kotlinx.coroutines.delay

/**
 * 配送地图是底部 tab，因此页头明确不显示返回按钮。无地图 Key 时文字路线保持完整可操作。
 */
@Composable
fun RiderMapScreen(
    state: RiderMapUiState,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStopClick: (MapStop) -> Unit = {},
) {
    val context = LocalContext.current
    val mapAvailable = remember { AmapKeyState.isMapAvailable(context) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(TOAST_MILLIS)
            notice = null
        }
    }

    fun navigate(stop: MapStop) {
        val point = stop.point
        if (point == null) {
            notice = "该站缺少坐标，请按地址手动导航：${stop.addressDetail}"
            return
        }
        notice = when (
            RiderNavigationLauncher.navigateTo(
                context = context,
                destination = point,
                destinationName = stop.title,
                start = state.riderPoint,
            )
        ) {
            RiderNavigationLauncher.Result.UNAVAILABLE ->
                "手机上没有可用地图，请按地址手动前往：${stop.addressDetail}"

            RiderNavigationLauncher.Result.EXTERNAL_MAP -> "已打开手机地图"
            else -> null
        }
    }

    MapContent(
        state = state,
        mapAvailable = mapAvailable,
        notice = notice,
        modifier = modifier,
        onRetry = onRetry,
        onNavigate = ::navigate,
        onStopClick = onStopClick,
    )
}

@Composable
private fun MapContent(
    state: RiderMapUiState,
    mapAvailable: Boolean,
    notice: String?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onNavigate: (MapStop) -> Unit = {},
    onStopClick: (MapStop) -> Unit = {},
) {
    FreshShell(
        modifier = modifier,
        topBar = {
            FreshPageHeader(
                title = "配送地图",
                subtitle = state.nextStop?.let { "下一站 · ${it.title}" } ?: "本趟文字路线",
                showBack = false,
            )
        },
    ) { insets ->
        when {
            state.loading && state.stops.isEmpty() -> FreshLoading(
                modifier = Modifier.padding(insets).fillMaxSize(),
                label = "正在加载配送路线",
            )

            state.error != null && state.stops.isEmpty() -> FreshError(
                message = state.error,
                modifier = Modifier.padding(insets).fillMaxSize(),
                onRetry = onRetry,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(FreshSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
            ) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT),
                    ) {
                        if (mapAvailable && state.hasAnyGeo) {
                            AmapMapCanvas(state = state, modifier = Modifier.fillMaxSize())
                        } else {
                            MapPlaceholderCard(mapAvailable)
                        }
                    }
                }

                item { RouteSummary(state) }

                if (notice != null) {
                    item {
                        FreshBanner(
                            text = notice,
                            tone = StatusTone.WARNING,
                            icon = FreshIconType.MAP,
                        )
                    }
                }

                state.nextStop?.let { next ->
                    item {
                        BigActionButton(
                            text = "导航去下一站 · ${next.title}",
                            icon = FreshIconType.ROUTE,
                            onClick = { onNavigate(next) },
                        )
                    }
                }

                if (state.stops.isEmpty()) {
                    item {
                        FreshEmpty(
                            title = "当前没有待配送站点",
                            message = "接单后路线会自动出现在这里",
                            icon = FreshIconType.MAP,
                        )
                    }
                }

                items(state.stops, key = { it.taskId }) { stop ->
                    StopCard(
                        stop = stop,
                        onNavigate = { onNavigate(stop) },
                        onClick = { onStopClick(stop) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MapPlaceholderCard(mapAvailable: Boolean) {
    FreshPanel(
        modifier = Modifier.fillMaxSize(),
        title = if (mapAvailable) "坐标尚未同步" else "地图底图暂不可用",
        eyebrow = "文字路线仍可使用",
        spineTone = StatusTone.INFO,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FreshIcon(
                FreshIconType.MAP,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                size = 48.dp,
            )
            Text(
                text = if (mapAvailable) {
                    "订单暂未拿到经纬度，按下方地址与站点顺序配送。"
                } else {
                    "不影响配送：下方保留完整地址、楼层、距离与系统地图导航。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FreshSpacing.Sm),
            )
        }
    }
}

@Composable
private fun RouteSummary(state: RiderMapUiState) {
    val remaining = state.stops.count { it.kind == MapStopKind.PENDING }
    FreshPanel(
        title = "本趟概览",
        eyebrow = "路线状态",
        spineTone = if (remaining == 0) StatusTone.SUCCESS else StatusTone.INFO,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
            FreshStatusBadge("共 ${state.stops.size} 站")
            FreshStatusBadge(
                "待送 $remaining 站",
                tone = if (remaining == 0) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        }
        state.totalDistanceMeters?.let { meters ->
            Text(
                "规划里程 ${formatDistance(meters)}" +
                    (state.totalDurationSeconds?.let { " · 预计 ${it / 60} 分钟" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.store?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                FreshIcon(
                    FreshIconType.STORE,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "起点：${it.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StopCard(
    stop: MapStop,
    onNavigate: () -> Unit,
    onClick: () -> Unit,
) {
    val done = stop.kind == MapStopKind.DONE
    val cold = !stop.coldChainText.isNullOrBlank()
    FreshPanel(
        title = "${stop.seqNo}. ${stop.title}",
        eyebrow = if (done) "站点已完成" else "待配送站点",
        spineTone = when {
            done -> StatusTone.SUCCESS
            cold -> StatusTone.COLD
            else -> StatusTone.WARNING
        },
        action = {
            FreshStatusBadge(
                text = if (done) "已送达" else "待送",
                tone = if (done) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        },
    ) {
        if (cold) {
            FreshStatusBadge(
                text = stop.coldChainText.orEmpty(),
                tone = StatusTone.COLD,
                icon = FreshIconType.COLD,
            )
        }
        stop.floorLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (stop.addressDetail.isNotBlank()) {
            Text(stop.addressDetail, style = MaterialTheme.typography.bodyLarge)
        }
        stop.receiverLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildList {
                stop.distanceFromRiderMeters?.let { add("距我 ${formatDistance(it.toLong())}") }
                stop.legDistanceMeters?.takeIf { it > 0 }?.let { add("本段 ${formatDistance(it)}") }
                stop.etaText?.let { add("预计 $it 到达") }
            }.joinToString(" · ").ifBlank { "距离待定位后计算" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            FreshSecondaryButton(
                text = "导航",
                icon = FreshIconType.ROUTE,
                enabled = !done,
                tone = StatusTone.INFO,
                modifier = Modifier.weight(1f),
                onClick = onNavigate,
            )
            FreshSecondaryButton(
                text = "查看订单",
                icon = FreshIconType.TASK,
                modifier = Modifier.weight(1f),
                onClick = onClick,
            )
        }
    }
}

private fun formatDistance(meters: Long): String =
    if (meters < 1000) "$meters 米" else String.format("%.1f 公里", meters / 1000.0)

private val MAP_HEIGHT = 320.dp
private const val TOAST_MILLIS = 5_000L

@Preview(name = "地图 · 文字路线", showBackground = true)
@Composable
private fun MapRoutePreview() {
    RiderTheme {
        MapContent(
            state = previewMapState(),
            mapAvailable = false,
            notice = null,
        )
    }
}

@Preview(name = "地图 · 空态", showBackground = true)
@Composable
private fun MapEmptyPreview() {
    RiderTheme { MapContent(RiderMapUiState(), mapAvailable = false, notice = null) }
}

@Preview(name = "地图 · 错误", showBackground = true)
@Composable
private fun MapErrorPreview() {
    RiderTheme {
        MapContent(
            RiderMapUiState(error = "路线同步失败，请检查网络"),
            mapAvailable = false,
            notice = null,
        )
    }
}

private fun previewMapState() = RiderMapUiState(
    stops = listOf(
        MapStop(
            taskId = 1,
            seqNo = 1,
            point = null,
            title = "锦江花园",
            addressDetail = "林荫路 18 号 3 栋 2 单元",
            floorLabel = "12 楼 1202",
            kind = MapStopKind.PENDING,
            distanceFromRiderMeters = 860,
            legDistanceMeters = 1_260,
            etaText = "04:32",
            coldChainText = "冷冻 · 优先送达",
            receiverLabel = "林女士 138****6608",
        ),
        MapStop(
            taskId = 2,
            seqNo = 2,
            point = null,
            title = "滨河里",
            addressDetail = "滨河路 6 号",
            floorLabel = "5 楼 502",
            kind = MapStopKind.DONE,
            distanceFromRiderMeters = 1_600,
        ),
    ),
    totalDistanceMeters = 8_600,
    totalDurationSeconds = 3_200,
)
