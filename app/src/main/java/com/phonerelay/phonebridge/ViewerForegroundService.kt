package com.phonerelay.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap

class ViewerForegroundService : Service() {

    companion object {
        const val ONGOING_CHANNEL_ID = "viewer_ongoing_channel"
        const val ALERTS_CHANNEL_ID = "viewer_alerts_channel"
        const val CALLS_CHANNEL_ID = "viewer_calls_channel"
        const val ONGOING_NOTIF_ID = 2001

        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning

        const val ACTION_NEW_EVENT = "com.phonerelay.phonebridge.NEW_EVENT"
        const val ACTION_TELEMETRY_UPDATED = "com.phonerelay.phonebridge.TELEMETRY_UPDATED"

        // Only raise a sound/heads-up alert for events that are actually recent. Older events
        // arriving via reconnect-replay or backlog catch-up are saved silently (never missed,
        // never spammy).
        private const val FRESH_WINDOW_MS = 40_000L
    }

    private var streamThread: Thread? = null
    private var catchUpThread: Thread? = null
    @Volatile private var shouldRun = true
    @Volatile private var lastCatchUp = 0L

    // LRU sliding window to suppress duplicate heads-up alerts within 45 seconds
    private val recentAlertFingerprints = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(60, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 150
        }
    )

    @Synchronized
    private fun isRecentDuplicate(type: String, title: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "${type.trim()}|${title.trim()}|${body.trim()}".lowercase()
        val lastSeen = recentAlertFingerprints[key]
        if (lastSeen != null && (now - lastSeen) < 45000L) return true
        recentAlertFingerprints[key] = now
        return false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        val ongoingNotif = buildOngoingNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ONGOING_NOTIF_ID, ongoingNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ONGOING_NOTIF_ID, ongoingNotif)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 1. Zero-latency local Wi-Fi receiver (P2P)
        LanBridge.startViewerLanReceiver(
            context = this,
            onTelemetry = { telemetry ->
                ingestTelemetry(telemetry)
                // We just heard from the host over the LAN → we're home. Pull any backlog it
                // buffered while we were away (debounced).
                maybeCatchUp(force = false)
            },
            onEvent = { event -> ingestEvent(event, broadcastUi = true) }
        )

        // 2. Redundant cloud stream with self-healing reconnect
        startEventStream()

        // 3. Periodic backlog catch-up (also recovers events missed while the app was closed)
        startCatchUpLoop()

        return START_STICKY
    }

    // ---- Ingestion (single path for LAN, cloud stream, and catch-up) ----

    private fun ingestEvent(event: NativeEvent, broadcastUi: Boolean) {
        val fresh = (System.currentTimeMillis() - event.timestamp) < FRESH_WINDOW_MS
        val isDup = isRecentDuplicate(event.type, event.title, event.body)
        EventCache.saveEvent(this, event)
        if (fresh && !isDup) {
            val isCall = event.type.startsWith("CALL")
            val isSms = event.type == "SMS"
            postHeadsUpNotification(event.title, event.body, isCall, isSms, event.otp.takeIf { it.isNotBlank() })
        }
        if (broadcastUi) sendBroadcast(Intent(ACTION_NEW_EVENT).setPackage(packageName))
    }

    private fun ingestTelemetry(telemetry: NativeTelemetry) {
        val intent = Intent(ACTION_TELEMETRY_UPDATED).setPackage(packageName).apply {
            putExtra("battery", telemetry.battery)
            putExtra("isCharging", telemetry.isCharging)
            putExtra("network", telemetry.network)
            putExtra("lastSeen", telemetry.lastSeen)
            putExtra("deviceModel", telemetry.deviceModel)
        }
        sendBroadcast(intent)
    }

    // ---- Cloud stream ----

    private fun startEventStream() {
        streamThread = Thread {
            val servers = FirebaseRelay.CLOUD_RELAY_ENDPOINTS
            var serverIndex = 0
            while (shouldRun) {
                val server = servers[serverIndex % servers.size]
                serverIndex++
                var conn: HttpURLConnection? = null
                try {
                    val topic = FirebaseRelay.getPrimaryTopic(this)
                    // since=60s replays the last minute on (re)connect so a brief drop loses nothing.
                    val url = URL("$server/$topic/json?since=60s")
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        // Not infinite: ntfy sends keepalives ~every 45s. If nothing arrives in
                        // 65s the socket is presumed dead and we reconnect.
                        readTimeout = 65000
                    }
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        val pairCode = BridgePreferences.getPairCode(this)
                        var line: String? = null
                        while (shouldRun && reader.readLine().also { line = it } != null) {
                            val inner = FirebaseRelay.decodeMessageLine(pairCode, line) ?: continue
                            if (EventCodec.kindOf(inner) == EventCodec.KIND_TELEMETRY) {
                                val t = EventCodec.parseTelemetry(inner)
                                ingestTelemetry(t.copy(network = "${t.network} (Cloud ☁️)"))
                            } else {
                                ingestEvent(EventCodec.parseEvent(inner), broadcastUi = true)
                            }
                        }
                        reader.close()
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // fall through to backoff
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
                if (!shouldRun) break
                try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
            }
        }.apply { start() }
    }

    // ---- Backlog catch-up ----

    private fun startCatchUpLoop() {
        catchUpThread = Thread {
            while (shouldRun) {
                try { Thread.sleep(5 * 60_000L) } catch (_: InterruptedException) { break }
                if (!shouldRun) break
                maybeCatchUp(force = true)
            }
        }.apply { start() }
    }

    private fun maybeCatchUp(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCatchUp < 60_000L) return
        lastCatchUp = now
        FirebaseRelay.fetchEvents(this) { list ->
            if (list.isNotEmpty()) {
                for (ev in list) ingestEvent(ev, broadcastUi = false)
                sendBroadcast(Intent(ACTION_NEW_EVENT).setPackage(packageName))
            }
        }
    }

    // ---- Notifications ----

    private fun postHeadsUpNotification(
        title: String,
        body: String,
        isCall: Boolean,
        isSms: Boolean,
        otp: String?
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channelId = if (isCall) CALLS_CHANNEL_ID else ALERTS_CHANNEL_ID
        val notifId = Math.abs("${channelId}_${title.trim()}_${body.trim()}".hashCode() % 90000) + 1000

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingContent = PendingIntent.getActivity(
            this, notifId, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_pb_notification)
            .setContentIntent(pendingContent)
            .setAutoCancel(true)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)

        if (isCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        } else {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        if (!otp.isNullOrBlank()) {
            val copyIntent = Intent(this, CopyOtpReceiver::class.java).apply { putExtra("EXTRA_OTP", otp) }
            val copyPendingIntent = PendingIntent.getBroadcast(
                this, notifId + 1, copyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_save, "📋 Copy OTP ($otp)", copyPendingIntent)
        }

        manager.notify(notifId, builder.build())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val ongoing = NotificationChannel(
                ONGOING_CHANNEL_ID, "PhoneBridge Viewer Status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows that Xperia is actively listening for Realme events" }
            manager.createNotificationChannel(ongoing)

            val callSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttr = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()
            val callsChannel = NotificationChannel(
                CALLS_CHANNEL_ID, "Incoming Calls from Home", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Rings when the home phone receives an incoming call"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(callSound, audioAttr)
            }
            manager.createNotificationChannel(callsChannel)

            val alertsChannel = NotificationChannel(
                ALERTS_CHANNEL_ID, "SMS & App Alerts from Home", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies for OTPs, SMS, and app alerts"
                enableVibration(true)
            }
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle("Xperia Bridge Active 🟢")
            .setContentText("Listening for Realme calls, OTPs & SMS via Wi-Fi & Cloud")
            .setSmallIcon(R.drawable.ic_pb_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        shouldRun = false
        LanBridge.stopViewerLanReceiver()
        streamThread?.interrupt()
        catchUpThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
