package com.yulin.rider.feature.shift

import com.yulin.rider.core.location.KeepAliveStatus

/** 检查清单的一行。[blocking] 为 true 时不满足就不让上班。 */
data class CheckItem(
    val key: String,
    val label: String,
    val why: String,
    val satisfied: Boolean,
    val blocking: Boolean,
)

/**
 * 上班前置检查(06 §3.2 / 04 §1.2 OnDutyChecks)。
 *
 * 只有精确定位是硬门槛(A9 的 [KeepAliveStatus.readyForDuty] 也是这么定的),其余是警告。
 * 单人单店场景里,拦住店主上班的代价远大于漏检一项后台设置。
 */
data class OnDutyChecklist(
    val items: List<CheckItem>,
    /** 保活体检模块未接入,全部按已满足处理。 */
    val unverified: Boolean,
) {
    val blocked: Boolean get() = items.any { it.blocking && !it.satisfied }

    fun satisfiedOf(key: String): Boolean = items.firstOrNull { it.key == key }?.satisfied ?: true

    companion object {
        fun from(status: KeepAliveStatus?): OnDutyChecklist = OnDutyChecklist(
            unverified = status == null,
            items = listOf(
                CheckItem(
                    key = "fineLocation",
                    label = "定位权限",
                    why = "没有定位,顾客看不到你在哪",
                    satisfied = status?.fineLocationGranted ?: true,
                    blocking = true,
                ),
                CheckItem(
                    key = "backgroundLocation",
                    label = "后台定位",
                    why = "锁屏骑车时不掉线",
                    satisfied = status?.backgroundLocationGranted ?: true,
                    blocking = false,
                ),
                CheckItem(
                    key = "notification",
                    label = "通知权限",
                    why = "新单来了要能响",
                    satisfied = status?.notificationGranted ?: true,
                    blocking = false,
                ),
                CheckItem(
                    key = "batteryOptimizationIgnored",
                    label = "电池优化白名单",
                    why = "省电策略会把定位服务杀掉",
                    satisfied = status?.batteryOptimizationIgnored ?: true,
                    blocking = false,
                ),
                CheckItem(
                    key = "keepaliveGuideDone",
                    label = "保活设置向导",
                    why = "国产 ROM 需要按厂商单独放行",
                    satisfied = status?.keepAliveGuideDone ?: true,
                    blocking = false,
                ),
            ),
        )
    }
}
