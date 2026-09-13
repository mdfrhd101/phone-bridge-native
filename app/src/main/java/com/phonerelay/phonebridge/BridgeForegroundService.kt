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
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phone_bridge_service_channel"
        const val NOTIFICATION_ID = 1001
        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning

        fun sendInstantTelemetry(context: Context) {
            Thread {
                try {
                    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    val pct = if (level != -1 && scale != -1) (level * 100 / scale) else -1

                    val network = getNetworkType(context)
                    FirebaseRelay.updateTelemetry(context, pct, isCharging, network)
                } catch (_: Exception) {}
            }.start()
        }

        fun getNetworkType(context: Context): String {
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
    }

    private var batteryReceiver: BroadcastReceiver? = null
    private var telemetryThread: Thread? = null
    private var shouldRun = true
    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var lastCallEventTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        BridgePreferences.setServiceRunning(this, true)

        val notification = buildNotification(
            "PhoneBridge Active 🟢",
            "Relaying Calls, SMS & Notifications to Xperia"
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Start Zero-Latency Local Wi-Fi Server (P2P Engine)
        LanBridge.startHostLanServices(this)

        registerBatteryReceiver()
        registerLiveCallMonitor()
        sendInstantTelemetry(this)
        startTelemetrySyncLoop()

        return START_STICKY
    }

    private fun registerLiveCallMonitor() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    private var wasRinging = false
                    private var ringTime = 0L

                    override fun onCallStateChanged(state: Int) {
                        if (!BridgePreferences.isForwardCalls(this@BridgeForegroundService)) return
                        val now = System.currentTimeMillis()
                        when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> {
                                if (now - lastCallEventTime > 3000) {
                                    lastCallEventTime = now
                                    wasRinging = true
                                    ringTime = now
                                    val (number, displayName) = CallReceiver.resolveLatestCaller(this@BridgeForegroundService, null, isCallEnded = false)
                                    FirebaseRelay.sendEvent(
                                        context = this@BridgeForegroundService,
                                        eventType = "CALL_RINGING",
                                        title = "Incoming Call: $displayName",
                                        body = "Phone is currently ringing at home ($number)",
                                        sender = displayName,
                                        extra = "Ringing"
                                    )
                                }
                            }
                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                wasRinging = false
                            }
                            TelephonyManager.CALL_STATE_IDLE -> {
                                if (wasRinging && (now - ringTime > 1000)) {
                                    val dur = ((now - ringTime) / 1000).coerceAtLeast(1)
                                    val (number, displayName) = CallReceiver.resolveLatestCaller(this@BridgeForegroundService, null, isCallEnded = true)
                                    FirebaseRelay.sendEvent(
                                        context = this@BridgeForegroundService,
                                        eventType = "CALL_MISSED",
                                        title = "Missed Call: $displayName",
                                        body = "Rang for ${dur}s without being answered.",
                                        sender = displayName,
                                        extra = "${dur}s"
                                    )
                                }
                                wasRinging = false
                            }
                        }
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
            } else {
                val listener = object : PhoneStateListener() {
                    private var wasRinging = false
                    private var ringTime = 0L

                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                        if (!BridgePreferences.isForwardCalls(this@BridgeForegroundService)) return
                        val now = System.currentTimeMillis()
                        when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> {
                                if (now - lastCallEventTime > 3000) {
                                    lastCallEventTime = now
                                    wasRinging = true
                                    ringTime = now
                                    val (number, displayName) = CallReceiver.resolveLatestCaller(this@BridgeForegroundService, incomingNumber, isCallEnded = false)
                                    FirebaseRelay.sendEvent(
                                        context = this@BridgeForegroundService,
                                        eventType = "CALL_RINGING",
                                        title = "Incoming Call: $displayName",
                                        body = "Phone is currently ringing at home ($number)",
                                        sender = displayName,
                                        extra = "Ringing"
                                    )
                                }
                            }
                            TelephonyManager.CALL_STATE_OFFHOOK -> {
                                wasRinging = false
                            }
                            TelephonyManager.CALL_STATE_IDLE -> {
                                if (wasRinging && (now - ringTime > 1000)) {
                                    val dur = ((now - ringTime) / 1000).coerceAtLeast(1)
                                    val (number, displayName) = CallReceiver.resolveLatestCaller(this@BridgeForegroundService, incomingNumber, isCallEnded = true)
                                    FirebaseRelay.sendEvent(
                                        context = this@BridgeForegroundService,
                                        eventType = "CALL_MISSED",
                                        title = "Missed Call: $displayName",
                                        body = "Rang for ${dur}s without being answered.",
                                        sender = displayName,
                                        extra = "${dur}s"
                                    )
                                }
                                wasRinging = false
                            }
                        }
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

                // Immediately update live telemetry to Xperia
                FirebaseRelay.updateTelemetry(context, batteryPct, isCharging, getNetworkType(context))

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
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
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
                    Thread.sleep(15000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.apply { start() }
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        shouldRun = false
        BridgePreferences.setServiceRunning(this, false)
        LanBridge.stopHostLanServices()
        batteryReceiver?.let { unregisterReceiver(it) }
        telemetryThread?.interrupt()

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
        } else {
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                tm?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
