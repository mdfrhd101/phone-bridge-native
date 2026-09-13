package com.phonerelay.phonebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object EventCache {
    private const val CACHE_FILE = "events_cache.json"
    private const val DELETED_FILE = "deleted_events.json"
    private val lock = Any()

    fun loadEvents(context: Context): List<NativeEvent> {
        synchronized(lock) {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return emptyList()
            return try {
                val jsonStr = file.readText(Charsets.UTF_8)
                val jsonArr = JSONArray(jsonStr)
                val list = mutableListOf<NativeEvent>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    list.add(
                        NativeEvent(
                            id = obj.optString("id", ""),
                            type = obj.optString("type", "EVENT"),
                            title = obj.optString("title", ""),
                            body = obj.optString("body", ""),
                            sender = obj.optString("sender", ""),
                            otp = obj.optString("otp", ""),
                            extra = obj.optString("extra", ""),
                            timestamp = obj.optLong("timestamp", 0L),
                            deviceModel = obj.optString("deviceModel", "Realme")
                        )
                    )
                }
                list.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun saveEvents(context: Context, events: List<NativeEvent>) {
        synchronized(lock) {
            try {
                val jsonArr = JSONArray()
                // Limit persistent cache to latest 300 events to maintain lightning performance
                val limited = events.take(300)
                for (ev in limited) {
                    val obj = JSONObject().apply {
                        put("id", ev.id)
                        put("type", ev.type)
                        put("title", ev.title)
                        put("body", ev.body)
                        put("sender", ev.sender)
                        put("otp", ev.otp)
                        put("extra", ev.extra)
                        put("timestamp", ev.timestamp)
                        put("deviceModel", ev.deviceModel)
                    }
                    jsonArr.put(obj)
                }
                val file = File(context.filesDir, CACHE_FILE)
                file.writeText(jsonArr.toString(), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    fun saveEvent(context: Context, event: NativeEvent) {
        mergeEvents(context, listOf(event))
    }

    private fun getDeletedIds(context: Context): MutableSet<String> {
        val file = File(context.filesDir, DELETED_FILE)
        if (!file.exists()) return mutableSetOf()
        return try {
            val jsonArr = JSONArray(file.readText(Charsets.UTF_8))
            val set = mutableSetOf<String>()
            for (i in 0 until jsonArr.length()) {
                set.add(jsonArr.getString(i))
            }
            set
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    private fun saveDeletedIds(context: Context, ids: Set<String>) {
        try {
            val jsonArr = JSONArray()
            ids.toList().takeLast(1000).forEach { jsonArr.put(it) }
            val file = File(context.filesDir, DELETED_FILE)
            file.writeText(jsonArr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun mergeEvents(context: Context, incoming: List<NativeEvent>): List<NativeEvent> {
        synchronized(lock) {
            val current = loadEvents(context).toMutableList()
            val deletedIds = getDeletedIds(context)

            val existingKeys = HashSet<String>()
            for (ev in current) {
                val key = if (ev.id.isNotBlank()) ev.id else "${ev.type}_${ev.title}_${ev.timestamp}"
                existingKeys.add(key)
            }

            var changed = false
            for (inEv in incoming) {
                val key = if (inEv.id.isNotBlank()) inEv.id else "${inEv.type}_${inEv.title}_${inEv.timestamp}"
                if (inEv.id.isNotBlank() && deletedIds.contains(inEv.id)) {
                    continue
                }
                if (deletedIds.contains(key)) {
                    continue
                }
                if (!existingKeys.contains(key)) {
                    current.add(inEv)
                    existingKeys.add(key)
                    changed = true
                }
            }

            if (changed) {
                current.sortByDescending { it.timestamp }
                saveEvents(context, current)
            }
            return current
        }
    }

    fun deleteEvent(context: Context, event: NativeEvent): List<NativeEvent> {
        return deleteEvents(context, setOf(event))
    }

    fun deleteEvents(context: Context, toDelete: Set<NativeEvent>): List<NativeEvent> {
        synchronized(lock) {
            val deletedIds = getDeletedIds(context)
            for (ev in toDelete) {
                if (ev.id.isNotBlank()) deletedIds.add(ev.id)
                deletedIds.add("${ev.type}_${ev.title}_${ev.timestamp}")
            }
            saveDeletedIds(context, deletedIds)

            val current = loadEvents(context).filterNot { ev ->
                toDelete.any { target ->
                    (target.id.isNotBlank() && target.id == ev.id) ||
                    (target.type == ev.type && target.title == ev.title && target.timestamp == ev.timestamp)
                }
            }
            saveEvents(context, current)
            return current
        }
    }

    fun clearAll(context: Context): List<NativeEvent> {
        synchronized(lock) {
            val current = loadEvents(context)
            val deletedIds = getDeletedIds(context)
            for (ev in current) {
                if (ev.id.isNotBlank()) deletedIds.add(ev.id)
                deletedIds.add("${ev.type}_${ev.title}_${ev.timestamp}")
            }
            saveDeletedIds(context, deletedIds)
            saveEvents(context, emptyList())
            return emptyList()
        }
    }
}
