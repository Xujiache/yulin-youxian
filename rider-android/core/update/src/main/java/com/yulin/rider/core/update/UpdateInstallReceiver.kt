package com.yulin.rider.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status message=$message")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) {
                context.startActivity(confirm)
            }
        }
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
    }
}
