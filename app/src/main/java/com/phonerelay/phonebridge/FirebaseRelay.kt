package com.phonerelay.phonebridge

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class NativeEvent(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val sender: String,
    val otp: String,
    val extra: String,
    val timestamp: Long,
    val deviceModel: String
)

data class NativeTelemetry(
    val battery: Int,
    val isCharging: Boolean,
    val network: String,
    val lastSeen: Long,
    val deviceModel: String
) {
    val isOnline: Boolean
        get() {
            if (lastSeen == 0L) return false
            return (System.currentTimeMillis() - lastSeen) < 180000L
        }
}

object FirebaseRelay {

    // Single unified topic to prevent cross-topic duplication
    const val PRIMARY_TOPIC = "pb_vault_farhad_realme_xperia_8829"

    // Multi-mirror cloud relay cluster (order = failover priority)
    val CLOUD_RELAY_ENDPOINTS = listOf(
        "https://ntfy.envs.net",
        "https://ntfy.ca",
        "https://ntfy.sh"
    )

    // Endpoints we POST to (first two are the fastest from BD; the third is a read fallback).
    private val PUBLISH_ENDPOINTS = listOf("https://ntfy.envs.net", "https://ntfy.ca")

    fun getTopics(context: Context): List<String> {
        val code = BridgePreferences.getPairCode(context)
        return if (code.isBlank() || code == "realme-xperia") {
            listOf(PRIMARY_TOPIC)
        } else {
            listOf("pb_vault_" + code.replace(Regex("[^a-zA-Z0-9_]"), "_"))
        }
    }

    fun getPrimaryTopic(context: Context): String = getTopics(context).first()

    fun getTelemetryTopic(context: Context): String = getPrimaryTopic(context) + "_telemetry"
    private fun telemetryTopic(context: Context): String = getTelemetryTopic(context)

    fun generateDeterministicId(eventType: String, title: String, body: String): String {
        val clean = "${eventType.trim().lowercase()}|${title.trim().lowercase()}|${body.trim().lowercase()}"
        val hash = Math.abs(clean.hashCode()).toString(16)
        val timeBucket = System.currentTimeMillis() / 60000L
        return "evt_${hash}_$timeBucket"
    }

    fun sendEvent(
        context: Context,
        eventType: String,
        title: String,
        body: String,
        sender: String = "",
        otp: String = "",
        extra: String = ""
    ) {
        // 1. Store locally in the host event log (for the Host screen)
        BridgePreferences.addLog(context, eventType, title, if (otp.isNotBlank()) "OTP: $otp | $body" else body)

        val eventId = generateDeterministicId(eventType, title, body)
        val event = NativeEvent(
            id = eventId,
            type = eventType,
            title = title,
            body = body,
            sender = sender.ifBlank { title },
            otp = otp,
            extra = extra,
            timestamp = System.currentTimeMillis(),
            deviceModel = android.os.Build.MODEL ?: "Realme"
        )

        // 2. Persist to the host outbox so a viewer that was offline for a long time can pull
        //    the full backlog over the LAN when it returns home (survives host reboot).
        EventCache.saveEvent(context, event)

        // 3. Broadcast immediately over Local Wi-Fi LAN (encrypted, ~0ms latency)
        LanBridge.broadcastEvent(context, event)

        // 4. Dispatch to the redundant cloud relays as an ENCRYPTED blob (no plaintext leak)
        val pairCode = BridgePreferences.getPairCode(context)
        val cipher = Crypto.encrypt(pairCode, EventCodec.serializeEvent(event))
        val topic = getPrimaryTopic(context)

        Thread {
            for (endpoint in PUBLISH_ENDPOINTS) {
                try {
                    val url = URL("$endpoint/$topic")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                        // Generic, non-revealing metadata only.
                        setRequestProperty("Title", "PhoneBridge")
                        setRequestProperty("Priority", "high")
                        setRequestProperty("Tags", "lock")
                        connectTimeout = 3500
                        readTimeout = 3500
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(cipher); it.flush() }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {
                    // failover to next mirror
                }
            }
            dispatchToFirebaseIfConfigured(context, eventType, title, body, sender, otp, extra)
        }.start()
    }

    fun updateTelemetry(
        context: Context,
        batteryPercent: Int,
        isCharging: Boolean,
        networkType: String
    ) {
        val telemetry = NativeTelemetry(
            battery = batteryPercent,
            isCharging = isCharging,
            network = networkType,
            lastSeen = System.currentTimeMillis(),
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        )

        // 1. Broadcast immediately to Local Wi-Fi LAN (encrypted)
        LanBridge.broadcastTelemetry(context, telemetry)

        val pairCode = BridgePreferences.getPairCode(context)
        val cipher = Crypto.encrypt(pairCode, EventCodec.serializeTelemetry(telemetry))
        val topic = telemetryTopic(context)

        Thread {
            for (endpoint in PUBLISH_ENDPOINTS) {
                try {
                    val url = URL("$endpoint/$topic")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                        connectTimeout = 3500
                        readTimeout = 3500
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(cipher); it.flush() }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }.start()
    }

    fun fetchTelemetry(context: Context, callback: (NativeTelemetry?) -> Unit) {
        Thread {
            val pairCode = BridgePreferences.getPairCode(context)

            // 1. If we discovered a LAN host recently, query it first
            val hostIp = LanBridge.lastDiscoveredHostIp
            if (!hostIp.isNullOrBlank() && (System.currentTimeMillis() - LanBridge.lastDiscoveredHostTime < 30000L)) {
                val lanTelemetry = LanBridge.queryLanTelemetry(context, hostIp, 1500)
                if (lanTelemetry != null) {
                    callback(lanTelemetry)
                    return@Thread
                }
            }

            // 2. Query cloud relays with fast failover
            val topic = telemetryTopic(context)
            for (server in CLOUD_RELAY_ENDPOINTS) {
                try {
                    val url = URL("$server/$topic/json?poll=1&limit=1")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3000
                        readTimeout = 3000
                    }
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        var line: String? = null
                        var lastTelemetry: NativeTelemetry? = null
                        while (reader.readLine().also { line = it } != null) {
                            val inner = decodeMessageLine(pairCode, line) ?: continue
                            if (EventCodec.kindOf(inner) == EventCodec.KIND_TELEMETRY) {
                                lastTelemetry = EventCodec.parseTelemetry(inner).let {
                                    it.copy(network = "${it.network} (Cloud ☁️)")
                                }
                            }
                        }
                        reader.close()
                        conn.disconnect()
                        if (lastTelemetry != null) {
                            callback(lastTelemetry)
                            return@Thread
                        }
                    } else {
                        conn.disconnect()
                    }
                } catch (_: Exception) {}
            }
            callback(null)
        }.start()
    }

    fun fetchEvents(context: Context, callback: (List<NativeEvent>) -> Unit) {
        Thread {
            val pairCode = BridgePreferences.getPairCode(context)
            val list = mutableListOf<NativeEvent>()

            // 1. If a LAN host is known, pull its full outbox directly (fast home re-sync)
            val hostIp = LanBridge.lastDiscoveredHostIp
            if (!hostIp.isNullOrBlank()) {
                list.addAll(LanBridge.queryLanEvents(context, hostIp, 2500))
            }

            // 2. Query cloud relays; ask for everything the server still retains
            val topic = getPrimaryTopic(context)
            for (server in CLOUD_RELAY_ENDPOINTS) {
                try {
                    val url = URL("$server/$topic/json?poll=1&since=all")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3000
                        readTimeout = 4000
                    }
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        var line: String? = null
                        var gotAny = false
                        while (reader.readLine().also { line = it } != null) {
                            val inner = decodeMessageLine(pairCode, line) ?: continue
                            if (EventCodec.kindOf(inner) != EventCodec.KIND_TELEMETRY) {
                                gotAny = true
                                list.add(EventCodec.parseEvent(inner))
                            }
                        }
                        reader.close()
                        conn.disconnect()
                        if (gotAny) break
                    } else {
                        conn.disconnect()
                    }
                } catch (_: Exception) {}
            }

            // Deduplicate by id, then by content within a 90s window
            val uniqueList = mutableListOf<NativeEvent>()
            for (ev in list.sortedByDescending { it.timestamp }) {
                val isDup = uniqueList.any { existing ->
                    (existing.id.isNotBlank() && ev.id.isNotBlank() && existing.id == ev.id) ||
                    (existing.type == ev.type &&
                     existing.title.trim().equals(ev.title.trim(), ignoreCase = true) &&
                     existing.body.trim().equals(ev.body.trim(), ignoreCase = true) &&
                     Math.abs(existing.timestamp - ev.timestamp) < 90000L)
                }
                if (!isDup) uniqueList.add(ev)
            }
            callback(uniqueList)
        }.start()
    }

    /**
     * Takes one raw line of an ntfy `/json` stream/poll and returns the decrypted inner
     * PhoneBridge JSON, or null for keepalives / non-message events / undecryptable payloads.
     */
    fun decodeMessageLine(pairCode: String, line: String?): JSONObject? {
        if (line.isNullOrBlank()) return null
        return try {
            val envelope = JSONObject(line)
            if (envelope.optString("event") != "message") return null
            val messageStr = envelope.optString("message", "")
            if (messageStr.isBlank()) return null
            val plain = Crypto.decrypt(pairCode, messageStr) ?: return null
            if (!plain.startsWith("{")) return null
            JSONObject(plain)
        } catch (_: Exception) {
            null
        }
    }

    private fun dispatchToFirebaseIfConfigured(
        context: Context,
        eventType: String,
        title: String,
        body: String,
        sender: String,
        otp: String,
        extra: String
    ) {
        val projectId = BridgePreferences.getFirebaseProjectId(context)
        val apiKey = BridgePreferences.getFirebaseApiKey(context)
        val pairCode = BridgePreferences.getPairCode(context)

        if (projectId.isBlank() || apiKey.isBlank()) return

        try {
            val safePair = java.net.URLEncoder.encode(pairCode, "UTF-8")
            val urlStr = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/vaults/$safePair/events?key=$apiKey"
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
            }

            val fields = JSONObject().apply {
                put("type", JSONObject().put("stringValue", eventType))
                put("title", JSONObject().put("stringValue", title))
                put("body", JSONObject().put("stringValue", body))
                put("sender", JSONObject().put("stringValue", sender))
                put("otp", JSONObject().put("stringValue", otp))
                put("extra", JSONObject().put("stringValue", extra))
                put("timestamp", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
            }
            val payload = JSONObject().apply { put("fields", fields) }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()); it.flush() }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
