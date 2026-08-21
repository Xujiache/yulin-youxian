package com.yulin.rider.core.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 位置点搭车上报的设备遥测。调度台用电量判断骑手能不能接远单,用网络类型解释掉线。
 *
 * networkType 只做到 WIFI / MOBILE 粒度:细分 4G/5G 需要 READ_PHONE_STATE,
 * 为一个诊断字段申请通话权限会显著抬高 PIPL 合规成本和骑手的授权抵触,不划算。
 */
@Singleton
class DeviceTelemetry @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun batteryLevel(): Int? = runCatching {
        val manager = ContextCompat.getSystemService(context, BatteryManager::class.java)
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    }.getOrNull()

    fun networkType(): String = runCatching {
        val manager = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
            ?: return@runCatching "UNKNOWN"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return@runCatching "NONE"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }.getOrDefault("UNKNOWN")

    fun isOnline(): Boolean = networkType() !in setOf("NONE", "UNKNOWN")
}
