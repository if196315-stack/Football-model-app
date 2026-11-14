package com.footballmodel.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent in-app event feed shared by foreground and background workers. */
internal object NotificationCenter {
    private const val PREFS = "notification_center"
    private const val EVENTS_KEY = "events"
    private const val MAX_EVENTS = 100

    @Synchronized
    fun add(context: Context, id: String, type: String, title: String, message: String,
            targetTab: String = "notifications", targetId: String = "") {
        if (id.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = read(prefs.getString(EVENTS_KEY, "[]"))
        for (i in 0 until events.length()) if (events.optJSONObject(i)?.optString("id") == id) return
        val event = JSONObject().put("id", id).put("type", type).put("title", title)
            .put("message", message).put("targetTab", targetTab).put("targetId", targetId)
            .put("createdAt", System.currentTimeMillis()).put("read", false)
        val updated = JSONArray().put(event)
        for (i in 0 until events.length()) {
            if (updated.length() >= MAX_EVENTS) break
            updated.put(events.getJSONObject(i))
        }
        prefs.edit().putString(EVENTS_KEY, updated.toString()).apply()
    }

    @Synchronized
    fun events(context: Context): JSONArray {
        val stored = read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EVENTS_KEY, "[]"))
        val rows = mutableListOf<JSONObject>()
        for (i in 0 until stored.length()) stored.optJSONObject(i)?.let { rows.add(JSONObject(it.toString())) }
        rows.sortByDescending { it.optLong("createdAt") }
        return JSONArray().also { result -> rows.forEach { result.put(it) } }
    }

    @Synchronized
    fun unreadCount(context: Context): Int {
        val all = events(context)
        var count = 0
        for (i in 0 until all.length()) if (!all.optJSONObject(i).optBoolean("read", false)) count++
        return count
    }

    fun hasUnread(context: Context): Boolean = unreadCount(context) > 0

    @Synchronized
    fun markAllRead(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = read(prefs.getString(EVENTS_KEY, "[]"))
        for (i in 0 until all.length()) all.optJSONObject(i)?.put("read", true)
        prefs.edit().putString(EVENTS_KEY, all.toString()).apply()
    }

    private fun read(raw: String?): JSONArray = try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }
}
