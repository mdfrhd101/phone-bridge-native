package com.phonerelay.phonebridge

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Single source of truth for turning events/telemetry into the JSON that travels (encrypted)
 * over the LAN and cloud relay, and for OTP extraction. Host and viewer MUST agree on this,
 * so all serialization/parsing/OTP logic lives here instead of being copied per file.
 */
object EventCodec {

    const val KIND_EVENT = "EVENT"
    const val KIND_TELEMETRY = "TELEMETRY"

    // ---- Events ----

    fun serializeEvent(event: NativeEvent): String {
        return JSONObject().apply {
            put("v", 2)
            put("kind", KIND_EVENT)
            put("id", event.id)
            put("type", event.type)
            put("title", event.title)
            put("body", event.body)
            put("sender", event.sender)
            put("otp", event.otp)
            put("extra", event.extra)
            put("timestamp", event.timestamp)
            put("deviceModel", event.deviceModel)
        }.toString()
    }

    fun parseEvent(json: JSONObject): NativeEvent {
        return NativeEvent(
            id = json.optString("id", ""),
            type = json.optString("type", "EVENT"),
            title = json.optString("title", "Alert"),
            body = json.optString("body", ""),
            sender = json.optString("sender", ""),
            otp = json.optString("otp", ""),
            extra = json.optString("extra", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            deviceModel = json.optString("deviceModel", "Realme")
        )
    }

    // ---- Telemetry ----

    fun serializeTelemetry(t: NativeTelemetry): String {
        return JSONObject().apply {
            put("v", 2)
            put("kind", KIND_TELEMETRY)
            put("battery", t.battery)
            put("isCharging", t.isCharging)
            put("network", t.network)
            put("lastSeen", t.lastSeen)
            put("deviceModel", t.deviceModel)
        }.toString()
    }

    fun parseTelemetry(json: JSONObject): NativeTelemetry {
        return NativeTelemetry(
            battery = json.optInt("battery", -1),
            isCharging = json.optBoolean("isCharging", false),
            network = json.optString("network", "Connected"),
            lastSeen = json.optLong("lastSeen", System.currentTimeMillis()),
            deviceModel = json.optString("deviceModel", "Realme Phone")
        )
    }

    fun kindOf(json: JSONObject): String = json.optString("kind", KIND_EVENT)

    // ---- OTP extraction (accuracy-first) ----

    private val OTP_KEYWORDS = listOf(
        "otp", "code", "pin", "verification", "verify", "password", "passcode",
        "secret", "one-time", "one time", "2fa", "auth", "login", "log in",
        "confirm", "activation", "security code", "bkash", "nagad", "rocket", "upay"
    )

    // Digit run of length 4–8 not glued to other digits.
    private val DIGIT_RUN: Pattern = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)")

    /**
     * Returns an OTP only when the message actually looks like a verification message
     * (contains an OTP keyword). This avoids treating phone numbers, amounts, dates, or
     * TrxIDs in ordinary SMS as OTPs. Returns null otherwise.
     */
    fun extractOtp(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()
        val looksLikeOtp = OTP_KEYWORDS.any { lower.contains(it) }
        if (!looksLikeOtp) return null
        val m = DIGIT_RUN.matcher(text)
        return if (m.find()) m.group(1) else null
    }
}
