package com.yulin.rider.feature.map

import com.yulin.rider.core.location.GeoMath
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.model.TaskCard
import com.yulin.rider.core.model.WaveRoute

enum class MapStopKind {
    STORE,
    PENDING,
    DONE,
}

/** 地图上的一个站点。文字版路线与地图气泡共用同一份数据,无 Key 时不会出现信息缺失。 */
data class MapStop(
    val taskId: Long,
    val seqNo: Int,
    val point: GeoPoint?,
    val title: String,
    val addressDetail: String,
    val floorLabel: String?,
    val kind: MapStopKind,
    val distanceFromRiderMeters: Int? = null,
    val legDistanceMeters: Long? = null,
    val etaText: String? = null,
    val coldChainText: String? = null,
    val receiverLabel: String? = null,
)

data class RiderMapUiState(
    val store: MapStop? = null,
    val stops: List<MapStop> = emptyList(),
    /** 门店 → 各未送站点的道路几何。服务端给不出时由端上算路补上。 */
    val routeLine: List<GeoPoint> = emptyList(),
    /** 骑手 → 门店那一段的道路几何，取货段单独上色。 */
    val pickupLine: List<GeoPoint> = emptyList(),
    val riderPoint: GeoPoint? = null,
    val riderBearing: Float? = null,
    val totalDistanceMeters: Long? = null,
    val totalDurationSeconds: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val nextStop: MapStop? get() = stops.firstOrNull { it.kind == MapStopKind.PENDING }

    val hasAnyGeo: Boolean
        get() = riderPoint != null || store?.point != null || stops.any { it.point != null }
}

/**
 * 由 04 §1.3 的 TaskCard 与 §1.3 的 WaveRoute 拼出地图状态。
 * 放在这里而不是 A8 的页面里,是为了让「地图气泡」和「文字版路线」永远来自同一份数据。
 */
fun buildRiderMapUiState(
    route: WaveRoute?,
    tasks: List<TaskCard>,
    riderPoint: GeoPoint? = null,
    riderBearing: Float? = null,
    loading: Boolean = false,
    error: String? = null,
): RiderMapUiState {
    val tasksById = tasks.associateBy { it.taskId }
    val legByTaskId = route?.stops.orEmpty().associate { it.taskId to it.legDistanceMeters }
    val seqByTaskId = route?.stops.orEmpty().associate { it.taskId to it.seqNo }

    val stops = tasks
        .sortedBy { seqByTaskId[it.taskId] ?: it.seqNo ?: Int.MAX_VALUE }
        .mapIndexed { index, task ->
            val point = task.location
            MapStop(
                taskId = task.taskId,
                seqNo = seqByTaskId[task.taskId] ?: task.seqNo ?: (index + 1),
                point = point,
                title = task.areaLabel?.takeIf { it.isNotBlank() }
                    ?: task.addressDetail?.takeIf { it.isNotBlank() }
                    ?: "站点 ${index + 1}",
                addressDetail = task.addressDetail.orEmpty(),
                floorLabel = buildFloorLabel(task),
                kind = if (task.status in DONE_STATUSES) MapStopKind.DONE else MapStopKind.PENDING,
                distanceFromRiderMeters = task.distanceFromRiderMeters
                    ?: estimateDistance(riderPoint, point),
                legDistanceMeters = legByTaskId[task.taskId],
                etaText = task.etaAt?.takeLast(TIME_TAIL_LENGTH),
                coldChainText = task.coldChainText,
                receiverLabel = listOfNotNull(task.receiverName, task.receiverPhoneMasked)
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() },
            )
        }

    val store = route?.origin?.let { origin ->
        MapStop(
            taskId = 0L,
            seqNo = 0,
            point = GeoPoint(origin.lat, origin.lng),
            title = origin.name ?: "禹邻优鲜门店",
            addressDetail = origin.name ?: "取货门店",
            floorLabel = null,
            kind = MapStopKind.STORE,
        )
    }

    return RiderMapUiState(
        store = store,
        stops = stops,
        routeLine = decodePolyline(route?.polyline),
        riderPoint = riderPoint,
        riderBearing = riderBearing,
        totalDistanceMeters = route?.totalDistanceMeters,
        totalDurationSeconds = route?.totalDurationSeconds,
        loading = loading,
        error = error,
    )
}

private fun buildFloorLabel(task: TaskCard): String? {
    val parts = buildList {
        task.buildingLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        task.unitNo?.let { add("${it}单元") }
        task.floorNo?.let { add("${it}楼") }
        task.roomNo?.takeIf { it.isNotBlank() }?.let { add("${it}室") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun estimateDistance(from: GeoPoint?, to: GeoPoint?): Int? {
    if (from == null || to == null) return null
    return GeoMath.distanceMeters(from, to).toInt()
}

private val DONE_STATUSES = setOf("DELIVERED", "RETURNED", "CANCELLED")

/** etaAt 是 "2026-08-11T15:42:00",取尾部 "15:42:00" 再截到分钟。 */
private const val TIME_TAIL_LENGTH = 8
