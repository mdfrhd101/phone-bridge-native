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
                val rawList = mutableListOf<NativeEvent>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    rawList.add(
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
                // Deduplicate any legacy or duplicate events on load
                val uniqueList = mutableListOf<NativeEvent>()
                for (ev in rawList.sortedByDescending { it.timestamp }) {
                    if (!isDuplicate(ev, uniqueList)) {
                        uniqueList.add(ev)
                    }
                }
                uniqueList
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun saveEvents(context: Context, events: List<NativeEvent>) {
        synchronized(lock) {
            try {
                val jsonArr = JSONArray()
                // Limit persistent cache to latest 300 events
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

    private fun isDuplicate(incoming: NativeEvent, existing: List<NativeEvent>): Boolean {
        val inType = incoming.type
        val inTitle = incoming.title.trim()
        val inBody = incoming.body.trim()
        for (ev in existing) {
            if (ev.id.isNotBlank() && incoming.id.isNotBlank() && ev.id == incoming.id) {
                return true
            }
            if (ev.type == inType &&
                ev.title.trim().equals(inTitle, ignoreCase = true) &&
                ev.body.trim().equals(inBody, ignoreCase = true)) {
                // If content is identical and within 90 seconds, treat as duplicate
                if (Math.abs(ev.timestamp - incoming.timestamp) < 90000L) {
                    return true
                }
            }
        }
        return false
    }

    fun mergeEvents(context: Context, incoming: List<NativeEvent>): List<NativeEvent> {
        synchronized(lock) {
            val current = loadEvents(context).toMutableList()
            val deletedIds = getDeletedIds(context)

            var changed = false
            for (inEv in incoming) {
                if (inEv.id.isNotBlank() && deletedIds.contains(inEv.id)) {
                    continue
                }
                val contentKey = "${inEv.type}_${inEv.title.trim()}_${inEv.timestamp}"
                if (deletedIds.contains(contentKey)) {
                    continue
                }

                if (!isDuplicate(inEv, current)) {
                    current.add(inEv)
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
                deletedIds.add("${ev.type}_${ev.title.trim()}_${ev.timestamp}")
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
                deletedIds.add("${ev.type}_${ev.title.trim()}_${ev.timestamp}")
            }
            saveDeletedIds(context, deletedIds)
            saveEvents(context, emptyList())
            return emptyList()
        }
    }
}
