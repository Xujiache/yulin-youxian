package com.yulin.rider.core.location

/**
 * 定位数据源抽象。高德与系统原生两套实现共用同一接口,运行时按有无 Key 自动选择,
 * 上层(采样/清洗/上传)完全不感知差异。
 */
interface RiderLocationSource {

    val type: LocationSourceType

    /** 数据源可用性。高德未初始化成功时返回 false,交由工厂回落到系统实现。 */
    fun isAvailable(): Boolean

    /**
     * 只能在已授予 ACCESS_FINE_LOCATION 且服务处于前台态时调用。
     * @return 启动是否成功;失败原因写入 [onError]。
     */
    fun start(intervalMillis: Long, onFix: (RiderLocationFix) -> Unit, onError: (String) -> Unit): Boolean

    /** 采样间隔变化时热更新,避免停/启带来的首定位延迟。 */
    fun updateInterval(intervalMillis: Long)

    fun stop()
}
