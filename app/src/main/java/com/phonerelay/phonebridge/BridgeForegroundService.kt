package com.phonerelay.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phone_bridge_service_channel"
        const val NOTIFICATION_ID = 1001
        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning
    }

    private var batteryReceiver: BroadcastReceiver? = null
    private var telemetryThread: Thread? = null
    private var shouldRun = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        BridgePreferences.setServiceRunning(this, true)

        val notification = buildNotification("PhoneBridge Active", "Relaying Calls, SMS & Notifications to Xperia")
        startForeground(NOTIFICATION_ID, notification)

        registerBatteryReceiver()
        startTelemetrySyncLoop()

        return START_STICKY
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return

        batteryReceiver = object : BroadcastReceiver() {
            private var lastAlertedLow = false

            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale) else -1

                when (action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        FirebaseRelay.sendEvent(
                            context,
                            "BATTERY_ALERT",
                            "Charger Connected ⚡",
                            "Realme phone is now charging. Battery: $batteryPct%",
                            "System",
                            extra = "$batteryPct%"
                        )
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        FirebaseRelay.sendEvent(
                            context,
                            "BATTERY_ALERT",
                            "Charger Disconnected 🔌",
                            "Realme phone charger was unplugged! Battery: $batteryPct%",
                            "System",
                            extra = "$batteryPct%"
                        )
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        if (batteryPct in 1..15 && !lastAlertedLow && !isCharging) {
                            lastAlertedLow = true
                            FirebaseRelay.sendEvent(
                                context,
                                "BATTERY_ALERT",
                                "⚠️ Low Battery Warning!",
                                "Realme battery is critical at $batteryPct%. Please plug in charger!",
                                "System",
                                extra = "$batteryPct%"
                            )
                        } else if (batteryPct > 20) {
                            lastAlertedLow = false
                        }
                    }
                }

                val network = getNetworkType(context)
                FirebaseRelay.updateTelemetry(context, batteryPct, isCharging, network)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun startTelemetrySyncLoop() {
        shouldRun = true
        telemetryThread = Thread {
            while (shouldRun) {
                try {
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    val pct = if (level != -1 && scale != -1) (level * 100 / scale) else -1
                    val network = getNetworkType(this)

                    FirebaseRelay.updateTelemetry(this, pct, isCharging, network)
                    Thread.sleep(60000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.apply { start() }
    }

    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                else -> "Connected"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhoneBridge Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PhoneBridge alive to forward SMS, Calls & Notifications"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        shouldRun = false
        BridgePreferences.setServiceRunning(this, false)
        batteryReceiver?.let { unregisterReceiver(it) }
        telemetryThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
