package com.yulin.rider.core.update

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RiderDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    private companion object {
        const val TAG = "RiderDeviceAdmin"
    }
}
