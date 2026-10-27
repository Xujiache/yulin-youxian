package com.yulin.rider.feature.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import com.yulin.rider.core.model.GeoPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val routed = rememberRoadRoute(context, state, enabled = mapUnavailable == null)

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
        state = routed,
        mapUnavailable = mapUnavailable,
        notice = notice,
        modifier = modifier,
        onBack = onBack,
        onRetry = onRetry,
        onNavigate = ::navigate,
        onStopClick = onStopClick,
    )
}

/**
 * 补上道路几何。
 *
 * 服务端只给站点顺序和里程，给不出沿途道路（那要另配高德 Web Key），
 * 地图上就只有几个孤零零的点。这里用包里已有的导航 SDK 在端上算一次，
 * 骑手不用再开一次导航就能看出该走哪条路。
 *
 * 站点集合没变就不重算 —— 每 10 秒一次的定位刷新都去算路既费流量又费电。
 */
@Composable
private fun rememberRoadRoute(
    context: android.content.Context,
    state: RiderMapUiState,
    enabled: Boolean,
): RiderMapUiState {
    val store = state.store?.point
    val pending = state.stops.filter { it.kind != MapStopKind.DONE }.mapNotNull { it.point }
    // 只按「起点 + 站点序列」做键，骑手每次挪动都重算就没意义了
    val key = remember(state) { listOfNotNull(store) + pending }
    var delivery by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var pickup by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

    LaunchedEffect(enabled, key) {
        delivery = emptyList()
        if (!enabled || store == null || pending.isEmpty()) return@LaunchedEffect
        delivery = RideRoutePlanner.calculate(
            context = context,
            start = store,
            wayPoints = pending.dropLast(1),
            end = pending.last(),
        )
    }
    // 取货段的起点是骑手，位置会动，但没必要跟着每次定位刷新重算，
    // 按百米量级取整做键，走出一段距离才重新算。
    val riderKey = state.riderPoint?.let { "${(it.lat * 1000).toInt()},${(it.lng * 1000).toInt()}" }
    LaunchedEffect(enabled, riderKey, store) {
        pickup = emptyList()
        val rider = state.riderPoint
        if (!enabled || rider == null || store == null) return@LaunchedEffect
        pickup = RideRoutePlanner.calculate(context, rider, emptyList(), store)
    }

    return state.copy(routeLine = state.routeLine.takeIf { it.size >= 2 } ?: delivery, pickupLine = pickup)
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
                    AmapMapCanvas(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        onUnavailable = { MapPlaceholder(MapUnavailable.NATIVE_MISSING) },
                    )
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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 抽屉能上下拖:占了大半屏时挡着地图,骑手想看路线得先把它推下去。
    // 往下拖到底只留一条抓手,再往上拖回来。
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    // 露出的部分要落在导航栏上方,否则抓手被系统手势区盖住,骑手拽不回来
    val bottomInset = WindowInsets.safeDrawing.getBottom(density)
    val peekPx = with(density) { SHEET_PEEK_HEIGHT.toPx() } + bottomInset
    val maxOffset = (sheetHeightPx - peekPx).coerceAtLeast(0f)
    val offsetY = remember { Animatable(0f) }
    var dragJob by remember { mutableStateOf<Job?>(null) }

    // 抽屉变矮时(站点变少)偏移量要跟着收,否则会被推出屏幕外再也拉不回来
    LaunchedEffect(maxOffset) {
        if (offsetY.value > maxOffset) offsetY.snapTo(maxOffset)
    }

    val dragModifier = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            // 和 SlideToConfirm 同一个坑:位移协程没落地就开始动画,
            // Animatable 互斥会把动画取消掉,抽屉停在半路
            dragJob = scope.launch {
                offsetY.snapTo((offsetY.value + delta).coerceIn(0f, maxOffset))
            }
        },
        onDragStopped = { velocity ->
            dragJob?.join()
            // 甩得够快就顺着方向吸附,否则就近吸附
            val target = when {
                velocity > SHEET_FLING_VELOCITY -> maxOffset
                velocity < -SHEET_FLING_VELOCITY -> 0f
                offsetY.value > maxOffset / 2 -> maxOffset
                else -> 0f
            }
            offsetY.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
        },
    )

    MtMapSheet(
        modifier = modifier
            .heightIn(max = SHEET_MAX_HEIGHT)
            .onSizeChanged { sheetHeightPx = it.height }
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .then(dragModifier),
    ) {
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

/** 推到底时露出来的高度：一条抓手加一行标题，够骑手看见并拽回来。 */
private val SHEET_PEEK_HEIGHT = 56.dp

/** 超过这个速度就按甩的方向吸附，不看松手位置。单位是 px/s。 */
private const val SHEET_FLING_VELOCITY = 800f
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
