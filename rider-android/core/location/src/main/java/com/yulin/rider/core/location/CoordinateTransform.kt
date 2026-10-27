package com.yulin.rider.core.location

import com.yulin.rider.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Android LocationManager 输出 WGS84；业务地图与服务端统一使用 GCJ-02。 */
object CoordinateTransform {

    fun wgs84ToGcj02(lat: Double, lng: Double): GeoPoint {
        if (!GeoMath.isValid(lat, lng) || outsideMainlandChina(lat, lng)) return GeoPoint(lat, lng)

        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((SEMI_MAJOR * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (SEMI_MAJOR / sqrtMagic * kotlin.math.cos(radLat) * PI)
        return GeoPoint(lat + dLat, lng + dLng)
    }

    private fun outsideMainlandChina(lat: Double, lng: Double): Boolean =
        lng !in 72.004..137.8347 || lat !in 0.8293..55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
            0.1 * x * y + 0.2 * sqrt(abs(x))
        value += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        value += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        value += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return value
    }

    private fun transformLng(x: Double, y: Double): Double {
        var value = 300.0 + x + 2.0 * y + 0.1 * x * x +
            0.1 * x * y + 0.1 * sqrt(abs(x))
        value += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        value += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        value += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return value
    }

    private const val SEMI_MAJOR = 6_378_245.0
    private const val EE = 0.006693421622965943
}
