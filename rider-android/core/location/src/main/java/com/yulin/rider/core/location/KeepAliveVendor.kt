package com.yulin.rider.core.location

import android.os.Build

/**
 * 保活向导的厂商分支(06 §3.2 第三层)。客户要求覆盖全部安卓品牌,不能只做四大厂。
 * 识别同时看 MANUFACTURER 与 BRAND —— 红米/POCO 的 MANUFACTURER 是 Xiaomi,
 * 而 realme 早期机型的 MANUFACTURER 又写成 OPPO,只看一个字段会漏。
 */
enum class KeepAliveVendor(val displayName: String, val settingsAppName: String) {
    XIAOMI("小米 / 红米 / POCO", "手机管家"),
    HUAWEI("华为", "手机管家"),
    HONOR("荣耀", "手机管家"),
    OPPO("OPPO / realme / 一加", "手机管家"),
    VIVO("vivo / iQOO", "i 管家"),
    SAMSUNG("三星", "设备维护"),
    MEIZU("魅族", "手机管家"),
    NUBIA("努比亚", "安全中心"),
    ZTE("中兴", "安全管家"),
    TRANSSION("传音 TECNO / Infinix / itel", "手机管家"),
    LENOVO("联想 / 摩托罗拉", "安全中心"),
    GENERIC("通用安卓", "系统设置");

    companion object {

        fun detect(
            manufacturer: String = Build.MANUFACTURER.orEmpty(),
            brand: String = Build.BRAND.orEmpty(),
        ): KeepAliveVendor {
            val tokens = listOf(manufacturer, brand).joinToString(" ").lowercase()
            return when {
                tokens.containsAny("xiaomi", "redmi", "poco", "blackshark") -> XIAOMI
                tokens.containsAny("honor", "hihonor") -> HONOR
                tokens.containsAny("huawei") -> HUAWEI
                tokens.containsAny("oppo", "realme", "oneplus") -> OPPO
                tokens.containsAny("vivo", "iqoo") -> VIVO
                tokens.containsAny("samsung") -> SAMSUNG
                tokens.containsAny("meizu", "flyme") -> MEIZU
                tokens.containsAny("nubia") -> NUBIA
                tokens.containsAny("zte") -> ZTE
                tokens.containsAny("transsion", "tecno", "infinix", "itel") -> TRANSSION
                tokens.containsAny("lenovo", "motorola", "moto") -> LENOVO
                else -> GENERIC
            }
        }

        private fun String.containsAny(vararg needles: String): Boolean =
            needles.any { contains(it) }
    }
}

/**
 * 深链目标。ROM 版本一变类名就没了,所以每项都给多个候选,依次尝试,
 * 全部失败时由 [KeepAliveIntents] 回退到应用详情页。
 */
data class ComponentTarget(
    val packageName: String,
    val className: String,
    /** 值里的 {pkg} / {label} 会在运行时替换成本应用的包名与名称。 */
    val extras: Map<String, String> = emptyMap(),
)

sealed interface KeepAliveAction {
    /** ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS:跳设置页,无需权限,不用受限的直接请求。 */
    data object BatteryOptimization : KeepAliveAction

    data object AppDetails : KeepAliveAction
    data object NotificationSettings : KeepAliveAction
    data object LocationSourceSettings : KeepAliveAction

    /** 厂商深链,失败自动回退到应用详情页。 */
    data class Vendor(val candidates: List<ComponentTarget>) : KeepAliveAction

    /** 没有任何可跳转入口,只能给文字步骤(如「在最近任务里下拉锁定」)。 */
    data object ManualOnly : KeepAliveAction
}

/** 可程序化检测的项;其余项只能让骑手自己勾确认。 */
enum class KeepAliveAutoDetect {
    FINE_LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATION,
    BATTERY_OPTIMIZATION,
}

data class KeepAliveStep(
    val id: String,
    val title: String,
    /** 一句话讲清「不做会怎样」。骑手不是工程师,只讲操作不讲后果他不会做。 */
    val summary: String,
    val instructions: List<String>,
    val action: KeepAliveAction,
    val autoDetect: KeepAliveAutoDetect? = null,
    val required: Boolean = true,
)

data class KeepAliveSection(
    val title: String,
    val steps: List<KeepAliveStep>,
)
