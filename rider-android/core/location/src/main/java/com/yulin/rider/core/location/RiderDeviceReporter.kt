package com.yulin.rider.core.location

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.yulin.rider.core.model.DeviceReport
import com.yulin.rider.core.network.api.RiderMessageApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * POST /api/rider/devices 的唯一出口。
 *
 * 保活向导完成状态和推送 registrationId 是同一条设备记录上的两个字段,
 * 分别上报会互相覆盖成 null,所以两边都必须经过这里,由 [RiderDeviceStore] 持有完整快照。
 */
@Singleton
class RiderDeviceReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RiderMessageApi,
    private val store: RiderDeviceStore,
    private val keepAliveChecker: KeepAliveChecker,
) {

    suspend fun reportKeepAliveGuideDone(done: Boolean): Boolean {
        store.guideDone = done
        return report()
    }

    suspend fun reportRegistrationId(registrationId: String?): Boolean {
        store.registrationId = registrationId
        return report()
    }

    /** 未登录时服务端会回 401/1001,静默失败即可,下一次上报会补上。 */
    suspend fun report(): Boolean = try {
        val status = keepAliveChecker.current()
        val response = api.reportDevice(
            DeviceReport(
                deviceId = store.deviceId,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
                appVersion = appVersion(),
                pushRegistrationId = store.registrationId,
                pushVendor = store.registrationId?.let { "JPUSH" },
                batteryOptimizationIgnored = status.batteryOptimizationIgnored,
                notificationEnabled = status.notificationGranted,
                backgroundLocationGranted = status.backgroundLocationGranted,
                keepaliveGuideDone = store.guideDone,
            )
        )
        response.code == 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "设备信息上报失败", e)
        false
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    private companion object {
        const val TAG = "RiderDeviceReporter"
    }
}
