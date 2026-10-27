package com.yulin.rider.feature.shift

import com.yulin.rider.core.location.KeepAliveStatus
import com.yulin.rider.core.model.GeoPoint

/**
 * 本模块与其他模块的接缝。
 *
 * 网络走 core/network 的 `RiderApis.of(context)`,不用装配。
 * 需要 app 层接的只有 A9 那侧的四件事:保活体检、当前坐标、设备号、定位前台服务的启停——
 * 它们都是 Hilt 单例([com.yulin.rider.core.location.LocationController]、
 * [com.yulin.rider.core.location.KeepAliveChecker]、`RiderDeviceStore`),
 * 而本模块没引 Hilt 插件,拿不到。
 *
 * 未装配时:检查清单全部按「已满足」处理并在弹窗里明示(客户要求不因检测缺失挡住上班),
 * 上下班仍能正常调服务端,只是不会自动拉起定位服务。
 */
interface ShiftFeatureDependencies {

    /** A9: `KeepAliveChecker.current()`。 */
    fun keepAliveStatus(): KeepAliveStatus? = null

    /** A9: `RiderDeviceStore.deviceId`。 */
    val deviceId: String

    /** A9: `LocationController.lastFix`。 */
    fun currentLocation(): GeoPoint? = null

    /** 用户确认手势内先启动 waiting 前台服务；false 时本次上班必须中止。 */
    fun onShiftStartRequested(): Boolean = true

    /** 服务端确认后才允许定位管道真正采集。 */
    fun onShiftStarted(shiftId: Long, onDutyAt: String) {}

    fun onShiftStartFailed() {}

    fun onShiftStopped() {}
}

object ShiftFeature {

    @Volatile
    private var dependencies: ShiftFeatureDependencies? = null

    fun install(dependencies: ShiftFeatureDependencies) {
        this.dependencies = dependencies
    }

    internal fun depsOrNull(): ShiftFeatureDependencies? = dependencies
}
