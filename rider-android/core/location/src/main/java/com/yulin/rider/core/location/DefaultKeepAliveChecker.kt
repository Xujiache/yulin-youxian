package com.yulin.rider.core.location

import android.content.Context
import android.os.PowerManager
import com.yulin.rider.core.common.AndroidRuntimeGates
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultKeepAliveChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RiderDeviceStore,
) : KeepAliveChecker {

    override fun current(): KeepAliveStatus = KeepAliveStatus(
        fineLocationGranted = AndroidRuntimeGates.hasFineLocation(context),
        backgroundLocationGranted = AndroidRuntimeGates.hasBackgroundLocation(context),
        notificationGranted = AndroidRuntimeGates.notificationsEnabled(context),
        batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
        keepAliveGuideDone = store.guideDone,
    )

    fun isBatteryOptimizationIgnored(): Boolean = runCatching {
        val manager = ContextCompat.getSystemService(context, PowerManager::class.java) ?: return@runCatching false
        manager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}
