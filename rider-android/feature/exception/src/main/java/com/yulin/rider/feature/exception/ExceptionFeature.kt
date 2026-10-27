package com.yulin.rider.feature.exception

import com.yulin.rider.core.model.GeoPoint

/**
 * 本模块与其他模块的接缝。
 *
 * 网络走 `RiderApis.of(context)`、离线队列走 `RiderDatabases.offlineQueue(context)`,都不用装配;
 * 只有定位需要 app 层注入取值函数(理由同 feature/task 的 TaskFeature)。
 */
object ExceptionFeature {

    @Volatile
    private var locationProvider: (() -> GeoPoint?)? = null

    fun installLocationProvider(provider: () -> GeoPoint?) {
        locationProvider = provider
    }

    internal fun currentLocation(): GeoPoint? = runCatching { locationProvider?.invoke() }.getOrNull()
}
