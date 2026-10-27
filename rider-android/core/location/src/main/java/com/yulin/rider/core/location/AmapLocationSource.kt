package com.yulin.rider.core.location

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption

/**
 * 高德定位实现(06 §3.1)。选它而不是 FusedLocationProviderClient,是因为国内设备普遍缺失
 * Google Play 服务,而高德原生做 GPS + WiFi + 基站融合。
 *
 * 合规前置:`updatePrivacyShow` / `updatePrivacyAgree` 必须在实例化任何 SDK 对象之前调用,
 * app 的 RiderApplication.onCreate 首行已经做了;这里再兜一次,避免本模块被单测/其他宿主直接使用时漏掉。
 */
class AmapLocationSource(private val context: Context) : RiderLocationSource {

    override val type: LocationSourceType = LocationSourceType.AMAP

    private var client: AMapLocationClient? = null
    private var option: AMapLocationClientOption? = null

    override fun isAvailable(): Boolean = AmapKeyState.isMapAvailable(context)

    override fun start(
        intervalMillis: Long,
        onFix: (RiderLocationFix) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (!isAvailable()) return false
        return try {
            if (!AmapPrivacyConsent.applyStoredConsent(context)) {
                onError("尚未同意高德定位隐私条款")
                return false
            }
            AmapKeyState.apiKey(context)?.let { AMapLocationClient.setApiKey(it) }

            val opt = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                interval = intervalMillis
                isNeedAddress = false
                isOnceLocation = false
                isMockEnable = false
                // 前台服务已经自己持有常驻通知,不再让 SDK 另起一套
                isLocationCacheEnable = true
                httpTimeOut = 15_000L
            }
            option = opt

            val newClient = AMapLocationClient(context.applicationContext)
            newClient.setLocationOption(opt)
            newClient.setLocationListener { location -> dispatch(location, onFix, onError) }
            newClient.startLocation()
            client = newClient
            true
        } catch (e: Throwable) {
            Log.w(TAG, "高德定位启动失败,将回落系统定位", e)
            onError("高德定位启动失败:${e.message}")
            stop()
            false
        }
    }

    override fun updateInterval(intervalMillis: Long) {
        val opt = option ?: return
        val c = client ?: return
        runCatching {
            opt.interval = intervalMillis
            c.setLocationOption(opt)
        }.onFailure { Log.w(TAG, "更新高德采样间隔失败", it) }
    }

    override fun stop() {
        runCatching {
            client?.stopLocation()
            client?.onDestroy()
        }.onFailure { Log.w(TAG, "停止高德定位失败", it) }
        client = null
        option = null
    }

    private fun dispatch(
        location: AMapLocation?,
        onFix: (RiderLocationFix) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (location == null) {
            onError("高德定位返回空结果")
            return
        }
        if (location.errorCode != AMapLocation.LOCATION_SUCCESS) {
            onError("高德定位失败[${location.errorCode}] ${location.errorInfo}")
            return
        }
        if (!GeoMath.isValid(location.latitude, location.longitude)) {
            onError("高德定位坐标非法")
            return
        }
        onFix(
            RiderLocationFix(
                lat = location.latitude,
                lng = location.longitude,
                accuracyMeters = location.accuracy.toDouble(),
                speedMps = location.speed.toDouble(),
                bearing = location.bearing.toDouble(),
                altitude = location.altitude,
                provider = providerOf(location),
                // 用 SDK 的定位时刻而非回调时刻,批量上报的 locatedAt 才准
                locatedAtMillis = if (location.time > 0) location.time else System.currentTimeMillis(),
                sourceType = LocationSourceType.AMAP,
            )
        )
    }

    private fun providerOf(location: AMapLocation): String = when (location.locationType) {
        AMapLocation.LOCATION_TYPE_GPS -> "GPS"
        AMapLocation.LOCATION_TYPE_WIFI -> "WIFI"
        AMapLocation.LOCATION_TYPE_CELL -> "CELL"
        AMapLocation.LOCATION_TYPE_OFFLINE -> "OFFLINE"
        else -> "NETWORK"
    }

    private companion object {
        const val TAG = "AmapLocationSource"
    }
}
