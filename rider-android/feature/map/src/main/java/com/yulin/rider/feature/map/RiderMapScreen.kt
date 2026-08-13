package com.yulin.rider.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yulin.rider.core.designsystem.FreshBorder
import com.yulin.rider.core.designsystem.FreshElevation
import com.yulin.rider.core.designsystem.FreshError
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtEmptyState
import com.yulin.rider.core.designsystem.MtGhostAction
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtLeg
import com.yulin.rider.core.designsystem.MtLegBadge
import com.yulin.rider.core.designsystem.MtMapControl
import com.yulin.rider.core.designsystem.MtMapSheet
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.core.location.AmapKeyState
import com.yulin.rider.core.location.MapUnavailable
import kotlinx.coroutines.delay

/**
 * 配送地图。
 *
 * 整屏是地图，路线与站点压在下方的圆角抽屉里，右侧悬浮定位与刷新 —— 与美团骑手端的地图页同构。
 * 没有地图 Key 时底图退化成说明卡，抽屉里的文字路线照常可用。
 */
@Composable
fun RiderMapScreen(
    state: RiderMapUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStopClick: (MapStop) -> Unit = {},
) {
    val context = LocalContext.current
    val mapUnavailable = remember { AmapKeyState.unavailableReason(context) }
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
        mapUnavailable = mapUnavailable,
        notice = notice,
        modifier = modifier,
        onBack = onBack,
        onRetry = onRetry,
        onNavigate = ::navigate,
        onStopClick = onStopClick,
    )
}

@Composable
private fun MapContent(
    state: RiderMapUiState,
    mapUnavailable: MapUnavailable?,
    notice: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNavigate: (MapStop) -> Unit = {},
    onStopClick: (MapStop) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.loading && state.stops.isEmpty() ->
                FreshLoading(modifier = Modifier.fillMaxSize(), label = "正在加载配送路线")

            state.error != null && state.stops.isEmpty() ->
                FreshError(
                    message = state.error,
                    modifier = Modifier.fillMaxSize(),
                    onRetry = onRetry,
                )

            else -> {
                if (mapUnavailable == null && state.hasAnyGeo) {
                    AmapMapCanvas(state = state, modifier = Modifier.fillMaxSize())
                } else {
                    MapPlaceholder(mapUnavailable)
                }

                MapOverlay(
                    state = state,
                    notice = notice,
                    onBack = onBack,
                    onRetry = onRetry,
                )

                RouteSheet(
                    state = state,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onNavigate = onNavigate,
                    onStopClick = onStopClick,
                )
            }
        }
    }
}

/** 压在地图上的一层：返回、下一站气泡、右侧圆形控件。 */
@Composable
private fun MapOverlay(
    state: RiderMapUiState,
    notice: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(FreshSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier
                    .size(RiderDimens.TouchTarget)
                    .clickable(onClick = onBack)
                    .semantics {
                        contentDescription = "返回"
                        role = Role.Button
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = FreshElevation.StickyBar,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    FreshIcon(
                        FreshIconType.BACK,
                        contentDescription = null,
                        tint = RiderColors.Ink,
                        size = 20.dp,
                    )
                }
            }

            state.nextStop?.distanceFromRiderMeters?.let { meters ->
                // 白色小气泡显示到下一站的直线距离，和美团地图上的「距取 908m」是同一处
                Surface(
                    modifier = Modifier.padding(start = FreshSpacing.Xs),
                    shape = RoundedCornerShape(FreshRadius.Pill),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = FreshElevation.StickyBar,
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = FreshSpacing.Sm,
                            vertical = FreshSpacing.Xxs,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
                    ) {
                        MtLegBadge(MtLeg.DELIVER, size = 16.dp)
                        Text(
                            text = "距下一站 ${formatDistance(meters.toLong())}",
                            style = MaterialTheme.typography.labelLarge.tabularFigures(),
                            color = RiderColors.Ink,
                        )
                    }
                }
            }
        }

        if (notice != null) {
            MtInfoBar(
                text = notice,
                tone = StatusTone.WARNING,
                icon = FreshIconType.MAP,
                modifier = Modifier.clip(RoundedCornerShape(FreshRadius.Control)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            MtMapControl(
                icon = FreshIconType.REFRESH,
                contentDescription = "刷新路线",
                onClick = onRetry,
            )
        }
    }
}

/** 底部抽屉：本趟概览 + 站点列表 + 导航主按钮。 */
@Composable
private fun RouteSheet(
    state: RiderMapUiState,
    modifier: Modifier = Modifier,
    onNavigate: (MapStop) -> Unit,
    onStopClick: (MapStop) -> Unit,
) {
    val remaining = state.stops.count { it.kind == MapStopKind.PENDING }

    MtMapSheet(modifier = modifier.heightIn(max = SHEET_MAX_HEIGHT)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            Text(
                text = "共 ${state.stops.size} 站 · 待送 $remaining 站",
                style = MaterialTheme.typography.titleMedium.tabularFigures(),
                color = RiderColors.Ink,
                modifier = Modifier.weight(1f),
            )
            // 后端没跑出路径规划时这两个字段是 0。显示「0 米 · 0 分钟」比不显示更糟——
            // 骑手会以为下一站就在眼前。宁可空着，站点卡里还有各自的直线距离。
            state.totalDistanceMeters?.takeIf { it > 0 }?.let { meters ->
                Text(
                    text = formatDistance(meters) +
                        (state.totalDurationSeconds?.takeIf { it > 0 }?.let { " · ${it / 60} 分钟" } ?: ""),
                    style = MaterialTheme.typography.bodySmall.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MtDivider()

        if (state.stops.isEmpty()) {
            MtEmptyState(
                title = "当前没有待配送站点",
                message = "接单后路线会自动出现在这里",
                icon = FreshIconType.MAP,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Xs,
                    bottom = FreshSpacing.Xs,
                ),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                items(state.stops, key = { it.taskId }) { stop ->
                    StopCard(
                        stop = stop,
                        onNavigate = { onNavigate(stop) },
                        onClick = { onStopClick(stop) },
                    )
                }
            }
        }

        state.nextStop?.let { next ->
            MtDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(FreshSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                MtGhostAction("订单", FreshIconType.TASK) { onStopClick(next) }
                MtPrimaryButton(
                    text = "导航去 ${next.title}",
                    action = MtAction.DELIVER,
                    modifier = Modifier.weight(1f),
                    icon = FreshIconType.NAVIGATE,
                    onClick = { onNavigate(next) },
                )
            }
        }
    }
}

@Composable
private fun MapPlaceholder(reason: MapUnavailable?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(FreshSpacing.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FreshIcon(
            FreshIconType.MAP,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 56.dp,
        )
        Text(
            text = when (reason) {
                null -> "订单暂未拿到经纬度"
                MapUnavailable.KEY_MISSING -> "地图 Key 未配置"
                MapUnavailable.CONSENT_MISSING -> "尚未同意位置信息授权"
                MapUnavailable.NATIVE_MISSING -> "当前设备不支持地图底图"
                MapUnavailable.SDK_MISSING -> "地图底图暂不可用"
            },
            style = MaterialTheme.typography.titleMedium,
            color = RiderColors.Ink,
            modifier = Modifier.padding(top = FreshSpacing.Sm),
        )
        Text(
            text = "不影响配送：下方保留完整地址、楼层、距离与系统地图导航。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = FreshSpacing.Xxs),
        )
    }
}

@Composable
private fun StopCard(
    stop: MapStop,
    onNavigate: () -> Unit,
    onClick: () -> Unit,
) {
    val done = stop.kind == MapStopKind.DONE
    MtCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FreshSpacing.Sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            if (done) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(RiderColors.DeliverContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    FreshIcon(
                        FreshIconType.CHECK,
                        contentDescription = null,
                        tint = RiderColors.Deliver,
                        size = 13.dp,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(RiderColors.Deliver, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${stop.seqNo}",
                        style = MaterialTheme.typography.labelSmall.tabularFigures(),
                        color = Color.White,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = stop.floorLabel?.let { "${stop.title} $it" } ?: stop.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else RiderColors.Ink,
                )
                if (stop.addressDetail.isNotBlank()) {
                    Text(
                        text = stop.addressDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                stop.receiverLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.tabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = FreshSpacing.Xxs),
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!stop.coldChainText.isNullOrBlank()) {
                        MtTag(stop.coldChainText.orEmpty(), tone = StatusTone.COLD)
                    }
                    Text(
                        text = buildList {
                            stop.distanceFromRiderMeters?.let { add("距我 ${formatDistance(it.toLong())}") }
                            stop.etaText?.let { add("预计 $it 到达") }
                        }.joinToString(" · ").ifBlank { "距离待定位后计算" },
                        style = MaterialTheme.typography.bodySmall.tabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!done) {
                Box(
                    modifier = Modifier
                        .size(RiderDimens.TouchTarget)
                        .clickable(onClick = onNavigate)
                        .semantics {
                            contentDescription = "导航到 ${stop.title}"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .border(
                                FreshBorder.Hairline,
                                MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        FreshIcon(
                            FreshIconType.NAVIGATE,
                            contentDescription = null,
                            tint = RiderColors.Ink,
                            size = 16.dp,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDistance(meters: Long): String =
    if (meters < 1000) "$meters 米" else String.format("%.1f 公里", meters / 1000.0)

private val SHEET_MAX_HEIGHT = 420.dp
private const val TOAST_MILLIS = 5_000L

@Preview(name = "地图 · 文字路线", showBackground = true, heightDp = 780)
@Composable
private fun MapRoutePreview() {
    RiderTheme {
        MapContent(
            state = previewMapState(),
            mapUnavailable = MapUnavailable.KEY_MISSING,
            notice = null,
        )
    }
}

@Preview(name = "地图 · 空态", showBackground = true, heightDp = 780)
@Composable
private fun MapEmptyPreview() {
    RiderTheme {
        MapContent(RiderMapUiState(), mapUnavailable = MapUnavailable.KEY_MISSING, notice = null)
    }
}

@Preview(name = "地图 · 错误", showBackground = true, heightDp = 780)
@Composable
private fun MapErrorPreview() {
    RiderTheme {
        MapContent(
            RiderMapUiState(error = "路线同步失败，请检查网络"),
            mapUnavailable = MapUnavailable.KEY_MISSING,
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
            coldChainText = "冷冻",
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
