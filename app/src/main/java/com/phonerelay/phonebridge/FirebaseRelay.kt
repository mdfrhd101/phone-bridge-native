package com.phonerelay.phonebridge

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    // Pre-configured private channel so the user never has to configure or type anything!
    private const val DEFAULT_RELAY_TOPIC = "pb_vault_farhad_realme_xperia_8829"

    private fun getTopic(context: Context): String {
        val code = BridgePreferences.getPairCode(context)
        return if (code.isBlank() || code == "realme-xperia") {
            DEFAULT_RELAY_TOPIC
        } else {
            "pb_vault_" + code.replace(Regex("[^a-zA-Z0-9_]"), "_")
        }
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
        // 1. Store locally in event logs
        BridgePreferences.addLog(context, eventType, title, if (otp.isNotBlank()) "OTP: $otp | $body" else body)

        val topic = getTopic(context)

        // 2. Dispatch to Built-in Instant Cloud Relay (Zero Config)
        Thread {
            try {
                val url = URL("https://ntfy.sh/$topic")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                    setRequestProperty("Title", title)
                    setRequestProperty("Priority", "high")
                    setRequestProperty("X-Type", eventType)
                    setRequestProperty("X-Sender", URLEncoder.encode(sender, "UTF-8"))
                    setRequestProperty("X-OTP", otp)
                    setRequestProperty("X-Extra", URLEncoder.encode(extra, "UTF-8"))
                    setRequestProperty("X-Model", URLEncoder.encode(android.os.Build.MODEL ?: "Realme", "UTF-8"))
                    if (otp.isNotBlank()) {
                        setRequestProperty("Tags", "key,incoming_envelope")
                    } else if (eventType.startsWith("CALL")) {
                        setRequestProperty("Tags", "telephone_receiver,phone")
                    } else {
                        setRequestProperty("Tags", "bell")
                    }
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(body)
                    writer.flush()
                }

                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Also dispatch to Firebase if user configured it
            dispatchToFirebaseIfConfigured(context, eventType, title, body, sender, otp, extra)
        }.start()
    }

    fun updateTelemetry(
        context: Context,
        batteryPercent: Int,
        isCharging: Boolean,
        networkType: String
    ) {
        val topic = getTopic(context) + "_telemetry"

        Thread {
            try {
                val url = URL("https://ntfy.sh/$topic")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }

                val json = JSONObject().apply {
                    put("battery", batteryPercent)
                    put("isCharging", isCharging)
                    put("network", networkType)
                    put("lastSeen", System.currentTimeMillis())
                    put("deviceModel", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(json.toString())
                    writer.flush()
                }

                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun fetchTelemetry(context: Context, callback: (NativeTelemetry?) -> Unit) {
        val topic = getTopic(context) + "_telemetry"

        Thread {
            try {
                val url = URL("https://ntfy.sh/$topic/json?poll=1&limit=1")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    var line: String?
                    var lastJson: JSONObject? = null
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            val obj = JSONObject(line!!)
                            if (obj.optString("event") == "message") {
                                val messageStr = obj.optString("message", "")
                                if (messageStr.startsWith("{")) {
                                    lastJson = JSONObject(messageStr)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    reader.close()

                    if (lastJson != null) {
                        val battery = lastJson.optInt("battery", -1)
                        val isCharging = lastJson.optBoolean("isCharging", false)
                        val network = lastJson.optString("network", "Connected")
                        val lastSeen = lastJson.optLong("lastSeen", 0L)
                        val model = lastJson.optString("deviceModel", "Realme Phone")

                        val telemetry = NativeTelemetry(battery, isCharging, network, lastSeen, model)
                        callback(telemetry)
                        conn.disconnect()
                        return@Thread
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            callback(null)
        }.start()
    }

    fun fetchEvents(context: Context, callback: (List<NativeEvent>) -> Unit) {
        val topic = getTopic(context)

        Thread {
            val list = mutableListOf<NativeEvent>()
            try {
                val url = URL("https://ntfy.sh/$topic/json?poll=1&since=12h")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            val obj = JSONObject(line!!)
                            if (obj.optString("event") == "message") {
                                val id = obj.optString("id", "")
                                val title = obj.optString("title", "Event")
                                val body = obj.optString("message", "")
                                val timeSec = obj.optLong("time", 0L)
                                val timestamp = if (timeSec > 0) timeSec * 1000L else System.currentTimeMillis()

                                val tags = obj.optJSONArray("tags")
                                var type = "EVENT"
                                var isCall = false
                                var isSms = false

                                if (tags != null) {
                                    for (i in 0 until tags.length()) {
                                        val t = tags.optString(i)
                                        if (t == "phone" || t == "telephone_receiver") isCall = true
                                        if (t == "key" || t == "incoming_envelope") isSms = true
                                    }
                                }

                                if (isCall || title.contains("Call", true)) {
                                    type = if (title.contains("Missed", true)) "CALL_MISSED" else "CALL_RINGING"
                                } else if (isSms || title.contains("SMS", true) || body.contains("OTP", true)) {
                                    type = "SMS"
                                } else {
                                    type = "NOTIFICATION"
                                }

                                val otp = extractOtp(body)

                                list.add(
                                    NativeEvent(
                                        id = id,
                                        type = type,
                                        title = title,
                                        body = body,
                                        sender = title,
                                        otp = otp ?: "",
                                        extra = "",
                                        timestamp = timestamp,
                                        deviceModel = "Realme"
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                    reader.close()
                    list.sortByDescending { it.timestamp }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            callback(list)
        }.start()
    }

    private fun extractOtp(text: String): String? {
        val pattern = java.util.regex.Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
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
            val urlStr = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/vaults/$pairCode/events?key=$apiKey"
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 6000
                readTimeout = 6000
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

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
