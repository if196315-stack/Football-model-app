package com.footballmodel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Tracks AI candidate fixtures independently from the report/observations bell.
 * The first successful feed is a baseline; only later newly-qualified fixtures notify.
 */
internal object AiMatchNotifications {
    private const val PREFS = "ai_match_notifications"
    private const val SEEN_IDS_KEY = "seen_candidate_match_ids"
    private const val CHANNEL_ID = "iq_top_new_signal"
    private const val CHANNEL_NAME = "Новые сигналы в ТОП"
    private const val NOTIFICATION_ID = 7304

    @Synchronized
    fun observe(context: Context, candidates: List<Pair<String, String>>): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(SEEN_IDS_KEY, null)
        val seen = previous?.toMutableSet() ?: mutableSetOf()
        val newlyAdded = candidates.filter { it.first.isNotBlank() && it.first !in seen }

        // Initial synchronization establishes a baseline without notifying about the whole feed.
        if (previous == null) {
            candidates.forEach { (id, _) -> if (id.isNotBlank()) seen.add(id) }
            prefs.edit().putStringSet(SEEN_IDS_KEY, seen).apply()
            return false
        }
        if (newlyAdded.isEmpty()) return false
        // If Android notification permission is missing, leave these IDs unseen and retry
        // on the next feed refresh instead of silently losing the first alert.
        newlyAdded.forEach { (id, title) ->
            NotificationCenter.add(context, "ai_match:$id", "ai_match", "🔥 Есть новинка! Сигнал уже в ТОП!", title, "desk", id)
        }
        if (show(context, newlyAdded.map { it.second })) {
            candidates.forEach { (id, _) -> if (id.isNotBlank()) seen.add(id) }
            prefs.edit().putStringSet(SEEN_IDS_KEY, seen).apply()
            return false
        }
        return true
    }

    fun requestPermissionIfNeeded(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7304)
    }

    private fun show(context: Context, newMatches: List<String>): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Новые матчи, прошедшие фильтры раздела ТОП"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val openAi = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_tab", "desk")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openAi,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val firstMatch = newMatches.firstOrNull().orEmpty()
        val body = if (newMatches.size == 1) firstMatch
            else "Добавлено матчей: ${newMatches.size}. Например: $firstMatch"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("🔥 Есть новинка! Сигнал уже в ТОП!")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }
}
