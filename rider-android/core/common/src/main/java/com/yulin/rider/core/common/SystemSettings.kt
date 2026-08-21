package com.yulin.rider.core.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 跳系统/厂商设置页。
 *
 * 厂商深链的 Activity 类名会随 ROM 版本消失,所以这里的每一次跳转都必须 try/catch,
 * 失败一律回退到「应用详情页」并由 UI 给出文字指引 —— 宁可多一步,不能崩(06 §3.2 实现要点)。
 */
object SystemSettings {

    /** 应用详情页,所有厂商都有,是最后的兜底。 */
    fun openAppDetails(context: Context): Boolean = startSafely(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )

    /**
     * 电池优化白名单。用 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS(跳列表页,无需权限),
     * 不用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS(直接请求,受分发政策限制)。
     */
    fun openBatteryOptimization(context: Context): Boolean =
        startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetails(context)

    /** 通知设置页。 */
    fun openNotificationSettings(context: Context): Boolean = startSafely(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    ) || openAppDetails(context)

    /** 系统定位总开关。骑手关掉 GPS 时,权限给了也定不到位。 */
    fun openLocationSourceSettings(context: Context): Boolean =
        startSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    /** 电池优化是唯一能程序化检测的项,其余厂商项只能靠骑手自述。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /**
     * 厂商自启动 / 后台管理页。按 [DeviceBrand] 逐个尝试候选组件,全失败则回落到应用详情页。
     */
    fun openAutoStartSettings(context: Context, brand: DeviceBrand = DeviceBrand.current()): Boolean {
        for (component in autoStartComponents(brand)) {
            val intent = Intent().setComponent(component)
            if (startSafely(context, intent)) return true
        }
        return openAppDetails(context)
    }

    /** 各厂商的自启动/后台管理入口候选,按命中概率从高到低排列。 */
    fun autoStartComponents(brand: DeviceBrand): List<ComponentName> = when (brand) {
        DeviceBrand.XIAOMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.securitycenter.Main"),
        )

        DeviceBrand.HUAWEI, DeviceBrand.HONOR -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )

        DeviceBrand.OPPO, DeviceBrand.REALME, DeviceBrand.ONEPLUS -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )

        DeviceBrand.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity"),
        )

        DeviceBrand.MEIZU -> listOf(
            ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        )

        DeviceBrand.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )

        DeviceBrand.OTHER -> emptyList()
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
