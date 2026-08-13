package com.yulin.rider.feature.map

import android.os.Bundle
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.model.GeoPoint

/**
 * 高德 MapView 的 Compose 包装。
 *
 * **必须手动转发 onCreate / onResume / onPause / onSaveInstanceState / onDestroy**——
 * MapView 持有 OpenGL 上下文和一条渲染线程,漏掉 onDestroy 就是稳定复现的内存泄漏,
 * 漏掉 onPause 会让骑手切到后台时地图仍在满帧渲染,直接影响续航。
 */
@Composable
internal fun AmapMapCanvas(
    state: RiderMapUiState,
    modifier: Modifier = Modifier,
    onUnavailable: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val metrics = context.resources.displayMetrics

    // Bundle 是 Parcelable,rememberSaveable 会随 Activity 一起保存/恢复相机位置
    val savedState = rememberSaveable { Bundle() }
    // AmapKeyState 的缺库判定是往「有」兜底的(误判成没有会连带掐掉定位和导航),
    // 所以真的加载不出 native 库时要在这里兜住 —— UnsatisfiedLinkError 是 Error,
    // 不接会直接崩在渲染线程上。
    val mapView = remember {
        runCatching { MapView(context).apply { onCreate(savedState) } }.getOrNull()
    }
    if (mapView == null) {
        onUnavailable()
        return
    }
    val renderer = remember { MapRenderer() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onSaveInstanceState(savedState)
                    mapView.onPause()
                }

                Lifecycle.Event.ON_STOP -> mapView.onSaveInstanceState(savedState)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onSaveInstanceState(savedState)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            // 地图渲染进程异常时不能让整页崩掉,骑手至少还要能看文字版路线
            runCatching { renderer.render(view.map, metrics, state) }
        },
    )
}

/**
 * 每个地图实例一个渲染器。用实例字段记住上一次的站点组合,
 * 只有站点真的变了才重新框选视野 —— 否则每 10 秒一次的定位刷新都会把骑手手动缩放的地图拽回去。
 */
private class MapRenderer {

    private var lastStopSignature: Int? = null

    fun render(map: AMap?, metrics: DisplayMetrics, state: RiderMapUiState) {
        if (map == null) return
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isScaleControlsEnabled = true

        val signature = state.stopSignature()
        val shouldFitCamera = signature != lastStopSignature
        lastStopSignature = signature

        map.clear()
        drawRoute(map, state)
        drawStore(map, metrics, state)
        drawStops(map, metrics, state)
        drawRider(map, metrics, state)
        if (shouldFitCamera) fitCamera(map, state)
    }

    /**
     * 路线分两段上色：骑手到门店是取货段走橙色，门店到各站点是送达段走绿色。
     * 与卡片上的按钮颜色和「取」「送」标记一致，骑手扫一眼颜色就知道自己在哪一段。
     */
    private fun drawRoute(map: AMap, state: RiderMapUiState) {
        val line = state.routeLine.ifEmpty { state.fallbackRouteLine() }
        if (line.size < 2) return

        val rider = state.riderPoint
        val store = state.store?.point
        if (rider != null && store != null && state.hasPendingPickup()) {
            map.addPolyline(
                PolylineOptions()
                    .add(rider.toLatLng(), store.toLatLng())
                    .width(POLYLINE_WIDTH)
                    .color(RiderColors.Pickup.toArgb())
                    .geodesic(false)
            )
        }

        map.addPolyline(
            PolylineOptions()
                .addAll(line.map { it.toLatLng() })
                .width(POLYLINE_WIDTH)
                .color(RiderColors.Deliver.toArgb())
                .geodesic(false)
        )
    }

    private fun drawStore(map: AMap, metrics: DisplayMetrics, state: RiderMapUiState) {
        val store = state.store ?: return
        val point = store.point ?: return
        map.addMarker(
            MarkerOptions()
                .position(point.toLatLng())
                .icon(
                    BitmapDescriptorFactory.fromBitmap(
                        // 自营单门店，门店就是取货点，直接用美团那套「取」标记
                        MapMarkerIcons.labeledPin(metrics, "取", RiderColors.Pickup.toArgb())
                    )
                )
                .anchor(0.5f, 1f)
                .title(store.title)
                .zIndex(1f)
        )
    }

    private fun drawStops(map: AMap, metrics: DisplayMetrics, state: RiderMapUiState) {
        state.stops.forEach { stop ->
            val point = stop.point ?: return@forEach
            val icon = when (stop.kind) {
                MapStopKind.DONE -> MapMarkerIcons.checkedPin(
                    metrics,
                    RiderColors.Deliver.toArgb(),
                )
                else -> MapMarkerIcons.numberedPin(
                    metrics,
                    stop.seqNo,
                    if (stop.coldChainText.isNullOrBlank()) {
                        RiderColors.Deliver.toArgb()
                    } else {
                        RiderColors.Ice.toArgb()
                    },
                )
            }
            map.addMarker(
                MarkerOptions()
                    .position(point.toLatLng())
                    .icon(BitmapDescriptorFactory.fromBitmap(icon))
                    .anchor(0.5f, 1f)
                    .title("${stop.seqNo}. ${stop.title}")
                    .snippet(listOfNotNull(stop.floorLabel, stop.addressDetail).joinToString(" · "))
                    .zIndex(2f)
            )
        }
    }

    private fun drawRider(map: AMap, metrics: DisplayMetrics, state: RiderMapUiState) {
        val point = state.riderPoint ?: return
        map.addMarker(
            MarkerOptions()
                .position(point.toLatLng())
                .icon(
                    BitmapDescriptorFactory.fromBitmap(
                        MapMarkerIcons.riderArrow(metrics, RiderColors.Info.toArgb())
                    )
                )
                .anchor(0.5f, 0.5f)
                .setFlat(true)
                // 高德的 rotateAngle 是逆时针,而 bearing 是顺时针正北角,取负号
                .rotateAngle(-(state.riderBearing ?: 0f))
                .title("我的位置")
                .zIndex(3f)
        )
    }

    private fun fitCamera(map: AMap, state: RiderMapUiState) {
        val points = buildList {
            state.riderPoint?.let { add(it) }
            state.store?.point?.let { add(it) }
            state.stops.mapNotNullTo(this) { it.point }
        }
        when {
            points.isEmpty() -> Unit
            points.size == 1 -> map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(points.first().toLatLng(), SINGLE_POINT_ZOOM)
            )

            else -> {
                val builder = LatLngBounds.builder()
                points.forEach { builder.include(it.toLatLng()) }
                runCatching {
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING_PX)
                    )
                }
            }
        }
    }
}

/** 服务端没给 polyline 时,用门店 → 各未送站点的直线串联,至少让骑手看出走向。 */
private fun RiderMapUiState.fallbackRouteLine(): List<GeoPoint> = buildList {
    store?.point?.let { add(it) }
    stops.filter { it.kind != MapStopKind.DONE }.mapNotNullTo(this) { it.point }
}

/** 还有站点没取货时才画骑手到门店那段橙线；全都取过了就只剩送达段。 */
private fun RiderMapUiState.hasPendingPickup(): Boolean =
    stops.any { it.kind == MapStopKind.PENDING }

private fun RiderMapUiState.stopSignature(): Int =
    stops.joinToString(",") { "${it.taskId}:${it.kind}" }.hashCode() * 31 + (store?.point?.hashCode() ?: 0)

private fun GeoPoint.toLatLng() = LatLng(lat, lng)

private const val POLYLINE_WIDTH = 14f
private const val SINGLE_POINT_ZOOM = 16f
private const val BOUNDS_PADDING_PX = 140
