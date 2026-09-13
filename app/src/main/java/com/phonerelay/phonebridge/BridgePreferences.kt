package com.phonerelay.phonebridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BridgePreferences {
    private const val PREFS_NAME = "phone_bridge_prefs"
    private const val KEY_FIREBASE_PROJECT_ID = "firebase_project_id"
    private const val KEY_FIREBASE_API_KEY = "firebase_api_key"
    private const val KEY_PAIR_CODE = "pair_code"
    private const val KEY_DEVICE_ROLE = "device_role" // "HOST" or "VIEWER"

    private const val KEY_FORWARD_SMS = "forward_sms"
    private const val KEY_FORWARD_CALLS = "forward_calls"
    private const val KEY_FORWARD_NOTIFICATIONS = "forward_notifications"
    private const val KEY_BATTERY_ALERTS = "battery_alerts"
    private const val KEY_SERVICE_RUNNING = "service_running"
    private const val KEY_LOGS = "event_logs"
    private const val MAX_LOGS = 60

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getFirebaseProjectId(context: Context): String =
        getPrefs(context).getString(KEY_FIREBASE_PROJECT_ID, "") ?: ""

    fun setFirebaseProjectId(context: Context, value: String) =
        getPrefs(context).edit().putString(KEY_FIREBASE_PROJECT_ID, value.trim()).apply()

    fun getFirebaseApiKey(context: Context): String =
        getPrefs(context).getString(KEY_FIREBASE_API_KEY, "") ?: ""

    fun setFirebaseApiKey(context: Context, value: String) =
        getPrefs(context).edit().putString(KEY_FIREBASE_API_KEY, value.trim()).apply()

    fun getPairCode(context: Context): String =
        getPrefs(context).getString(KEY_PAIR_CODE, "realme-xperia") ?: "realme-xperia"

    fun setPairCode(context: Context, value: String) =
        getPrefs(context).edit().putString(KEY_PAIR_CODE, value.trim()).apply()

    fun getDeviceRole(context: Context): String =
        getPrefs(context).getString(KEY_DEVICE_ROLE, "HOST") ?: "HOST"

    fun setDeviceRole(context: Context, role: String) =
        getPrefs(context).edit().putString(KEY_DEVICE_ROLE, role).apply()

    fun isForwardSms(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FORWARD_SMS, true)

    fun setForwardSms(context: Context, value: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_FORWARD_SMS, value).apply()

    fun isForwardCalls(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FORWARD_CALLS, true)

    fun setForwardCalls(context: Context, value: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_FORWARD_CALLS, value).apply()

    fun isForwardNotifications(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FORWARD_NOTIFICATIONS, true)

    fun setForwardNotifications(context: Context, value: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_FORWARD_NOTIFICATIONS, value).apply()

    fun isBatteryAlerts(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BATTERY_ALERTS, true)

    fun setBatteryAlerts(context: Context, value: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_BATTERY_ALERTS, value).apply()

    fun isServiceRunning(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SERVICE_RUNNING, false)

    fun setServiceRunning(context: Context, running: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()

    fun addLog(context: Context, type: String, title: String, detail: String) {
        try {
            val prefs = getPrefs(context)
            val logsJson = prefs.getString(KEY_LOGS, "[]")
            val jsonArray = JSONArray(logsJson)

            val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val entry = JSONObject().apply {
                put("type", type)
                put("title", title)
                put("detail", detail)
                put("time", time)
            }

            val newArray = JSONArray()
            newArray.put(entry)
            val limit = minOf(jsonArray.length(), MAX_LOGS - 1)
            for (i in 0 until limit) {
                newArray.put(jsonArray.get(i))
            }

            prefs.edit().putString(KEY_LOGS, newArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogs(context: Context): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        try {
            val json = getPrefs(context).getString(KEY_LOGS, "[]") ?: "[]"
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    mapOf(
                        "type" to obj.optString("type", ""),
                        "title" to obj.optString("title", ""),
                        "detail" to obj.optString("detail", ""),
                        "time" to obj.optString("time", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearLogs(context: Context) =
        getPrefs(context).edit().putString(KEY_LOGS, "[]").apply()
}
