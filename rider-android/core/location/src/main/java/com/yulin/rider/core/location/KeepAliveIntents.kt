package com.yulin.rider.core.location

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 保活向导的跳转入口。
 *
 * 三条铁律:
 * 1. 每个深链都 try/catch —— ROM 版本一变类名就没了,一次未捕获异常就是一次崩溃。
 * 2. 全部失败时回退到 ACTION_APPLICATION_DETAILS_SETTINGS,并由 UI 给出文字指引。
 * 3. 电池优化用 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS(跳设置页,无需权限),
 *    而不是 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS(直接请求,受 Play 政策限制)。
 */
object KeepAliveIntents {

    enum class OpenOutcome {
        /** 按预期跳到了目标设置页。 */
        OPENED,

        /** 深链失效,已回退到应用详情页,需要骑手照文字步骤自己找。 */
        FELL_BACK,

        /** 连应用详情页都打不开,只能全靠文字指引。 */
        FAILED,
    }

    fun open(context: Context, action: KeepAliveAction, appLabel: String): OpenOutcome = when (action) {
        KeepAliveAction.BatteryOptimization -> openFirstAvailable(
            context,
            listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
        )

        KeepAliveAction.AppDetails -> if (openAppDetails(context)) OpenOutcome.OPENED else OpenOutcome.FAILED

        KeepAliveAction.NotificationSettings -> openFirstAvailable(
            context,
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            ),
        )

        KeepAliveAction.LocationSourceSettings -> openFirstAvailable(
            context,
            listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
        )

        is KeepAliveAction.Vendor -> openFirstAvailable(
            context,
            action.candidates.map { it.toIntent(context, appLabel) },
        )

        KeepAliveAction.ManualOnly -> OpenOutcome.FAILED
    }

    private fun openFirstAvailable(context: Context, candidates: List<Intent>): OpenOutcome {
        for (intent in candidates) {
            if (launch(context, intent)) return OpenOutcome.OPENED
        }
        return if (openAppDetails(context)) OpenOutcome.FELL_BACK else OpenOutcome.FAILED
    }

    private fun openAppDetails(context: Context): Boolean = launch(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null)),
    )

    private fun launch(context: Context, intent: Intent): Boolean = try {
        // 从非 Activity 上下文启动必须带 NEW_TASK;向导页本身是 Activity,加上也无副作用
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        // ActivityNotFound / SecurityException(部分 ROM 的管家页不导出)都在这里被吃掉
        Log.d(TAG, "跳转失败:${intent.component ?: intent.action}", e)
        false
    }

    private fun ComponentTarget.toIntent(context: Context, appLabel: String): Intent {
        val target = this
        return Intent().apply {
            component = ComponentName(target.packageName, target.className)
            // 不能写成 extras.forEach:apply 的接收者是 Intent,extras 会解析成 Intent.getExtras()
            target.extras.forEach { (key, template) ->
                putExtra(
                    key,
                    template
                        .replace("{pkg}", context.packageName)
                        .replace("{label}", appLabel),
                )
            }
        }
    }

    private const val TAG = "KeepAliveIntents"
}
