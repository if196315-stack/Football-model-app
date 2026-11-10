package com.footballmodel.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

internal object AiMatchNotifications {
    private const val PREFS = "ai_match_notifications"
    private const val CHANNEL_ID = "ai_new_matches"
    private const val CHANNEL_NAME = "Новые матчи AI"
    private const val OPEN_TAB_EXTRA = "open_tab"

    @Synchronized
    fun notifyNewCandidates(context: Context, feed: JSONObject) {
        val competitions = feed.optJSONArray("competitions") ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen_match_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val initialized = prefs.getBoolean("initialized", false)
        val current = linkedMapOf<String, Triple<JSONObject, String, JSONObject>>()
        val now = System.currentTimeMillis()
        val utc = TimeZone.getTimeZone("UTC")
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val today = dayFormat.format(Date(now))
        val end = Calendar.getInstance(utc).apply { add(Calendar.DAY_OF_YEAR, 30) }
        val endDay = dayFormat.format(end.time)

        for (i in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(i) ?: continue
            val matches = competition.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                if (match.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                val matchDay = match.optString("utcDate").take(10)
                if (matchDay.isBlank() || matchDay < today || matchDay > endDay) continue
                val signal = match.optJSONObject("prediction")?.optJSONObject("strongestSignal")
                    ?: (if (competition.optString("code") in listOf("UCL", "UEL")) uefaSignal(match) else null)
                    ?: continue
                if (!qualifies(signal, match)) continue
                val id = match.optString("id").ifBlank {
                    match.optString("utcDate") + "|" + match.optString("home") + "|" + match.optString("away")
                }
                current[id] = Triple(match, competition.optString("name"), signal)
            }
        }

        if (!initialized) {
            seen.addAll(current.keys)
            prefs.edit().putBoolean("initialized", true).putStringSet("seen_match_ids", seen).commit()
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return

        var changed = false
        current.forEach { (id, item) ->
            if (id in seen) return@forEach
            val (match, league, signal) = item
            show(context, manager, id, match, league, signal)
            seen.add(id)
            changed = true
        }
        if (changed) {
            // Keep the local de-duplication set bounded.
            while (seen.size > 500) seen.remove(seen.first())
            prefs.edit().putStringSet("seen_match_ids", seen).commit()
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Уведомления о новых матчах, прошедших фильтры AI IQ Match"
            enableVibration(true)
        })
    }

    private fun show(
        context: Context,
        manager: NotificationManager,
        id: String,
        match: JSONObject,
        league: String,
        signal: JSONObject
    ) {
        val home = match.optString("home", "Команда 1")
        val away = match.optString("away", "Команда 2")
        val side = signal.optString("side", "AI-сигнал")
        val price = topOdds(signal, match) ?: return
        val body = "$home — $away · $league · $side · BetBoom ${String.format(Locale.US, "%.2f", price)}"
        val openAi = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OPEN_TAB_EXTRA, "desk")
        }
        val requestCode = id.hashCode() and 0x7fffffff
        val pending = PendingIntent.getActivity(
            context, requestCode, openAi,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
            else Notification.Builder(context)
        val notification = builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Новый матч в AI")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build()
        manager.notify(requestCode, notification)
    }

    private fun qualifies(signal: JSONObject, match: JSONObject): Boolean {
        if (signal.optBoolean("noPositiveSignal") || signal.optString("side").isBlank() ||
            signal.optString("side").equals("ОЗ НЕТ", true)) return false
        val price = topOdds(signal, match) ?: return false
        val probability = signal.optDouble("modelProbability", Double.NaN)
        val edge = if (signal.optBoolean("paperOnly")) signal.optDouble("edgePp", Double.NaN)
            else (probability - 1.0 / price) * 100.0
        val ev = probability * price - 1.0
        return probability.isFinite() && probability >= 0.60 &&
            edge.isFinite() && edge >= 5.0 && ev.isFinite() && ev >= 0.05 && price >= 1.50
    }

    private fun topOdds(signal: JSONObject, match: JSONObject): Double? {
        val side = signal.optString("side")
        val best = match.optJSONObject("odds")?.optJSONObject("best") ?: return null
        fun acceptable(value: Double) = value.isFinite() && value >= 1.50
        if (side == "1X" || side == "X2") {
            val dc = best.optJSONObject("doubleChance") ?: return null
            return dc.optDouble(if (side == "1X") "1x" else "x2", Double.NaN).takeIf { acceptable(it) }
        }
        if (side.startsWith("ИТБ ")) {
            val point = Regex("ИТБ\\s+([0-9]+(?:\\.[0-9]+)?)").find(side)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val teamName = side.substringAfter("·", "").trim()
            val team = when (teamName) {
                match.optString("home") -> "home"
                match.optString("away") -> "away"
                else -> return null
            }
            val rows = best.optJSONArray("teamTotals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val line = row.optDouble("point", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                if (row.optString("team") == team && row.optString("side") == "over" &&
                    line.isFinite() && abs(line - point) < 0.001 && acceptable(price)) return price
            }
        }
        if (side.startsWith("ТБ ")) {
            val point = side.removePrefix("ТБ ").trim().toDoubleOrNull() ?: return null
            val rows = best.optJSONArray("totals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val line = row.optDouble("point", Double.NaN)
                val price = row.optDouble("price", Double.NaN)
                if (row.optString("side") == "over" && line.isFinite() &&
                    abs(line - point) < 0.001 && acceptable(price)) return price
            }
        }
        if (side == "ОЗ ДА" || side == "ОЗ НЕТ") {
            val btts = best.optJSONObject("btts") ?: return null
            return btts.optDouble(if (side == "ОЗ ДА") "yes" else "no", Double.NaN).takeIf { acceptable(it) }
        }
        return null
    }

    private fun uefaSignal(match: JSONObject): JSONObject? {
        val rows = match.optJSONArray("paperSignals") ?: return null
        var selected: JSONObject? = null
        var bestEv = Double.NEGATIVE_INFINITY
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val side = when (row.optString("market")) {
                "double_chance" -> when (row.optString("outcome")) { "1x" -> "1X"; "x2" -> "X2"; else -> "" }
                "match_total_over" -> "ТБ " + row.optDouble("point").toString()
                "team_total_over" -> "ИТБ " + row.optDouble("point").toString() + " · " +
                    if (row.optString("team") == "home") match.optString("home") else match.optString("away")
                "btts" -> if (row.optString("outcome") == "yes") "ОЗ ДА" else ""
                else -> ""
            }
            if (side.isBlank()) continue
            val probability = row.optDouble("model_probability", Double.NaN)
            val signal = JSONObject().put("side", side).put("modelProbability", probability)
                .put("edgePp", row.optDouble("edge", Double.NaN) * 100.0).put("paperOnly", true)
            val price = topOdds(signal, match) ?: continue
            val archivedPrice = row.optDouble("price", Double.NaN)
            val edge = row.optDouble("edge", Double.NaN)
            val archivedEv = row.optDouble("expected_value", Double.NaN)
            if (!archivedPrice.isFinite() || abs(price - archivedPrice) >= 0.005 ||
                !probability.isFinite() || probability < 0.60 || !edge.isFinite() || edge < 0.05 ||
                !archivedEv.isFinite() || archivedEv < 0.05) continue
            val ev = probability * price - 1.0
            if (ev > bestEv) { bestEv = ev; selected = signal }
        }
        return selected
    }
}
