package com.phonerelay.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ViewerForegroundService : Service() {

    companion object {
        const val ONGOING_CHANNEL_ID = "viewer_ongoing_channel"
        const val ALERTS_CHANNEL_ID = "viewer_alerts_channel"
        const val CALLS_CHANNEL_ID = "viewer_calls_channel"
        const val ONGOING_NOTIF_ID = 2001

        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning

        const val ACTION_NEW_EVENT = "com.phonerelay.phonebridge.NEW_EVENT"
    }

    private var streamThread: Thread? = null
    private var shouldRun = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        val ongoingNotif = buildOngoingNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ONGOING_NOTIF_ID,
                    ongoingNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(ONGOING_NOTIF_ID, ongoingNotif)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startEventStream()
        return START_STICKY
    }

    private fun startEventStream() {
        shouldRun = true
        streamThread = Thread {
            while (shouldRun) {
                try {
                    val topic = FirebaseRelay.getPrimaryTopic(this)

                    val url = URL("https://ntfy.sh/$topic/json")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 0 // Infinite stream
                    }

                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        var line: String? = null
                        while (shouldRun && reader.readLine().also { line = it } != null) {
                            if (line.isNullOrBlank()) continue
                            try {
                                val json = JSONObject(line!!)
                                if (json.optString("event") == "message") {
                                    handleIncomingRelayMessage(json)
                                }
                            } catch (_: Exception) {}
                        }
                        reader.close()
                    }
                    conn.disconnect()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply { start() }
    }

    private fun handleIncomingRelayMessage(json: JSONObject) {
        val title = json.optString("title", "PhoneBridge Alert")
        val message = json.optString("message", "")
        if (message.startsWith("{") && message.contains("battery")) {
            // Telemetry ping, skip notification
            return
        }

        val tags = json.optJSONArray("tags")
        var isCall = false
        var isSms = false
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val t = tags.optString(i)
                if (t == "phone" || t == "telephone_receiver") isCall = true
                if (t == "key" || t == "incoming_envelope") isSms = true
            }
        }

        if (title.contains("Call", true)) isCall = true
        if (title.contains("SMS", true) || message.contains("OTP", true)) isSms = true

        val otp = extractOtp(message)
        postHeadsUpNotification(title, message, isCall, isSms, otp)

        // Notify ViewerActivity if visible
        val intent = Intent(ACTION_NEW_EVENT)
        sendBroadcast(intent)
    }

    private fun postHeadsUpNotification(
        title: String,
        body: String,
        isCall: Boolean,
        isSms: Boolean,
        otp: String?
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notifId = (System.currentTimeMillis() % 100000).toInt() + 100

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingContent = PendingIntent.getActivity(
            this,
            notifId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = if (isCall) CALLS_CHANNEL_ID else ALERTS_CHANNEL_ID

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingContent)
            .setAutoCancel(true)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)

        if (isCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        } else {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        // Add 1-tap Copy OTP Action if OTP found
        if (!otp.isNullOrBlank()) {
            val copyIntent = Intent(this, CopyOtpReceiver::class.java).apply {
                putExtra("EXTRA_OTP", otp)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(
                this,
                notifId + 1,
                copyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_save, "📋 Copy OTP ($otp)", copyPendingIntent)
        }

        manager.notify(notifId, builder.build())
    }

    private fun extractOtp(text: String): String? {
        val pattern = java.util.regex.Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. Ongoing service channel
            val ongoing = NotificationChannel(
                ONGOING_CHANNEL_ID,
                "PhoneBridge Viewer Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that Xperia is actively listening for Realme events"
            }
            manager.createNotificationChannel(ongoing)

            // 2. High priority calls channel
            val callSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttr = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val callsChannel = NotificationChannel(
                CALLS_CHANNEL_ID,
                "Incoming Calls from Home",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Rings when home phone receives an incoming call"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(callSound, audioAttr)
            }
            manager.createNotificationChannel(callsChannel)

            // 3. Alerts channel (SMS, OTP, Notifications)
            val alertsChannel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "SMS & App Alerts from Home",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies for OTPs, SMS, and App alerts"
                enableVibration(true)
            }
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle("Xperia Bridge Active 🟢")
            .setContentText("Listening for Realme Calls, OTPs & SMS in background")
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
        streamThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
