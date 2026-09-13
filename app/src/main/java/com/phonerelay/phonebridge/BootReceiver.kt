package com.phonerelay.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val role = BridgePreferences.getDeviceRole(context)
            val wasRunning = BridgePreferences.isServiceRunning(context)

            if (role == "HOST" || wasRunning) {
                val serviceIntent = Intent(context, BridgeForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
