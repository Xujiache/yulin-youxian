package com.yulin.rider.core.common

import android.os.Build

/**
 * 厂商识别。国产 ROM 的后台限制策略差异极大,保活向导、权限引导都要按厂商分支(06 §3.2 第三层)。
 * 判定一律用小写包含匹配:同一家的 MANUFACTURER 在不同机型上写法并不统一(HUAWEI/Huawei/HONOR…)。
 */
enum class DeviceBrand(val displayName: String) {
    XIAOMI("小米 / Redmi"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    OPPO("OPPO"),
    VIVO("vivo / iQOO"),
    ONEPLUS("一加"),
    REALME("realme"),
    SAMSUNG("三星"),
    MEIZU("魅族"),
    OTHER("其他品牌");

    companion object {

        fun current(): DeviceBrand = of(Build.MANUFACTURER, Build.BRAND)

        fun of(manufacturer: String?, brand: String? = null): DeviceBrand {
            val key = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
            return when {
                key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") -> XIAOMI
                key.contains("honor") -> HONOR
                key.contains("huawei") -> HUAWEI
                key.contains("oneplus") -> ONEPLUS
                key.contains("realme") -> REALME
                key.contains("oppo") -> OPPO
                key.contains("vivo") || key.contains("iqoo") -> VIVO
                key.contains("samsung") -> SAMSUNG
                key.contains("meizu") -> MEIZU
                else -> OTHER
            }
        }
    }
}

/** 机型信息,登录与设备上报都要带(04 §1.1 deviceInfo)。 */
data class DeviceSpec(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
) {
    companion object {
        fun current(): DeviceSpec = DeviceSpec(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        )
    }
}
