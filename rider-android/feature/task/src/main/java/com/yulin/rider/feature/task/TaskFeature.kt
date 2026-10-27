package com.yulin.rider.feature.task

import com.yulin.rider.core.model.GeoPoint

/**
 * 本模块与其他模块的接缝。
 *
 * 网络与数据库都不用装配:core/network 的 `RiderApis.of(context)` 和 core/database 的
 * `RiderDatabases.of(context)` 已经给非 Hilt 模块留了取用入口,拿到的是 Hilt 图里同一批单例。
 *
 * 只剩定位需要接:A9 的 LocationController 是 Hilt 单例,本模块没引 Hilt 插件拿不到,
 * 由 app 层在 Application.onCreate 里注入一个取值函数。未注入时流转动作不带坐标——
 * 04 §1.3 的 location 本就是可选字段,服务端不会因此拒收。
 */
object TaskFeature {

    @Volatile
    private var locationProvider: (() -> GeoPoint?)? = null

    fun installLocationProvider(provider: () -> GeoPoint?) {
        locationProvider = provider
    }

    internal fun currentLocation(): GeoPoint? = runCatching { locationProvider?.invoke() }.getOrNull()
}
