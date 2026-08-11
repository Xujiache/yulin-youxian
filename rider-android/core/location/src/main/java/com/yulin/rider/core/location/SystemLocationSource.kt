package com.yulin.rider.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 无高德 Key 时的兜底定位:Android 原生 LocationManager,GPS + NETWORK 双 provider 同时注册。
 *
 * 两个 provider 会各自回调,这里按「精度更好 / 时间更新」择优取一个,避免把同一时刻的两个点
 * 都塞进上报队列(服务端有 uk_location_dedup(rider_id, located_at) 去重,但没必要浪费流量)。
 */
class SystemLocationSource(private val context: Context) : RiderLocationSource {

    override val type: LocationSourceType = LocationSourceType.SYSTEM

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    private var listener: LocationListener? = null
    private var lastAccepted: Location? = null

    override fun isAvailable(): Boolean {
        val lm = locationManager ?: return false
        return lm.allProviders.any { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
    }

    override fun start(
        intervalMillis: Long,
        onFix: (RiderLocationFix) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val lm = locationManager
        if (lm == null) {
            onError("系统定位服务不可用")
            return false
        }
        if (!hasFineLocationPermission()) {
            onError("未授予精确定位权限")
            return false
        }

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!GeoMath.isValid(location.latitude, location.longitude)) return
                if (!isBetterThanLast(location)) return
                lastAccepted = location
                onFix(location.toFix())
            }

            // API 29 起这三个回调不再被系统调用,但接口在 minSdk 26 上仍是抽象方法,必须实现
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                onError("系统定位 provider 已关闭:$provider")
            }
        }
        listener = l

        var registered = false
        for (provider in PROVIDERS) {
            registered = requestUpdates(lm, provider, intervalMillis, l, onError) || registered
        }
        if (!registered) {
            onError("GPS 与网络定位均不可用,请检查手机定位开关")
            stop()
            return false
        }

        // 先把最后一次已知位置抛出去,避免上班后几十秒地图上没有骑手
        lastKnown(lm)?.let { onFix(it.toFix()) }
        return true
    }

    override fun updateInterval(intervalMillis: Long) {
        val lm = locationManager ?: return
        val l = listener ?: return
        runCatching { lm.removeUpdates(l) }
        for (provider in PROVIDERS) {
            requestUpdates(lm, provider, intervalMillis, l) { }
        }
    }

    override fun stop() {
        val lm = locationManager
        val l = listener
        if (lm != null && l != null) {
            runCatching { lm.removeUpdates(l) }.onFailure { Log.w(TAG, "注销系统定位监听失败", it) }
        }
        listener = null
        lastAccepted = null
    }

    private fun requestUpdates(
        lm: LocationManager,
        provider: String,
        intervalMillis: Long,
        l: LocationListener,
        onError: (String) -> Unit,
    ): Boolean = try {
        if (lm.allProviders.contains(provider)) {
            lm.requestLocationUpdates(provider, intervalMillis, MIN_DISTANCE_METERS, l, Looper.getMainLooper())
            true
        } else {
            false
        }
    } catch (e: SecurityException) {
        onError("系统定位权限被拒:${e.message}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "注册 $provider 失败", e)
        false
    }

    private fun lastKnown(lm: LocationManager): Location? = try {
        PROVIDERS.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { GeoMath.isValid(it.latitude, it.longitude) }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }

    /** GPS 与 NETWORK 并发回调时的择优:新点明显更新,或精度更好。 */
    private fun isBetterThanLast(candidate: Location): Boolean {
        val last = lastAccepted ?: return true
        val newerBy = candidate.time - last.time
        if (newerBy > STALE_THRESHOLD_MILLIS) return true
        if (newerBy < -STALE_THRESHOLD_MILLIS) return false
        return candidate.accuracy <= last.accuracy
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toFix(): RiderLocationFix {
        val gcj = CoordinateTransform.wgs84ToGcj02(latitude, longitude)
        return RiderLocationFix(
            lat = gcj.lat,
            lng = gcj.lng,
            // 0 表示「这台设备没给精度」,由 TrackCleaner 放行交服务端判定;不要伪造一个大值直接丢掉
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
            bearing = if (hasBearing()) bearing.toDouble() else 0.0,
            altitude = if (hasAltitude()) altitude else 0.0,
            provider = when (provider) {
                LocationManager.GPS_PROVIDER -> "GPS_WGS84_TO_GCJ02"
                LocationManager.NETWORK_PROVIDER -> "NETWORK_WGS84_TO_GCJ02"
                else -> provider?.uppercase() ?: "UNKNOWN"
            },
            locatedAtMillis = if (time > 0) time else System.currentTimeMillis(),
            sourceType = LocationSourceType.SYSTEM,
        )
    }

    private companion object {
        const val TAG = "SystemLocationSource"
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val MIN_DISTANCE_METERS = 0f
        const val STALE_THRESHOLD_MILLIS = 5_000L
    }
}
