package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class RouteOrigin(
    val lat: Double,
    val lng: Double,
    val name: String? = null,
)

@Serializable
data class RouteStop(
    val taskId: Long,
    val seqNo: Int,
    val location: GeoPoint,
    val legDistanceMeters: Long = 0,
    val legDurationSeconds: Long = 0,
    val handoffEstimateSeconds: Long = 0,
    val planArriveAt: String? = null,
    val planDepartAt: String? = null,
)

@Serializable
data class WaveRoute(
    val waveId: Long,
    val planVersion: Int = 1,
    val optimizerName: String? = null,
    val matrixProvider: String? = null,
    val origin: RouteOrigin? = null,
    val stops: List<RouteStop> = emptyList(),
    val totalDistanceMeters: Long = 0,
    val totalDurationSeconds: Long = 0,
    val polyline: String? = null,
)
