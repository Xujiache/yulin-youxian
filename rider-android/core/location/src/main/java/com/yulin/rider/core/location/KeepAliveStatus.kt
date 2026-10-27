package com.yulin.rider.core.location

/**
 * 保活体检结果(06 §3.2)。A8 的上班检查清单直接映射到 04 §1.2 的 OnDutyChecks 字段。
 *
 * 只有 [batteryOptimizationIgnored] 与三项权限能被程序化检测;
 * [keepAliveGuideDone] 是骑手在保活向导页逐项自述确认后落的标记 —— 国产 ROM 的自启动、
 * 后台弹出、锁定后台都没有公开查询 API,只能靠骑手自己勾。
 */
data class KeepAliveStatus(
    val fineLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val keepAliveGuideDone: Boolean,
) {

    val allGranted: Boolean
        get() = fineLocationGranted && backgroundLocationGranted &&
            notificationGranted && batteryOptimizationIgnored && keepAliveGuideDone

    /** 上班前必须满足的最低门槛:没有精确定位就完全没法配送。 */
    val readyForDuty: Boolean
        get() = fineLocationGranted
}

interface KeepAliveChecker {
    fun current(): KeepAliveStatus
}
