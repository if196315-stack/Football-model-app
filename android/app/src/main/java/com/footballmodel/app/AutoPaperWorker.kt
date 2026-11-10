package com.footballmodel.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

internal object AutoPaperExecutionLock

class AutoPaperWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val prefs = context.getSharedPreferences("bet_history", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("bank_settings", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "IQMatchAutoPaper"
    }

    @Volatile private var lastDownloadFailure: String? = null

    override fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "Auto-paper worker started; attempt=$runAttemptCount")
        return try {
            val domesticText = download("https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/data.json")
            val domestic = JSONObject(domesticText)
            val uefaText = downloadOrNull("https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/uefa-data.json")
            if (!uefaText.isNullOrBlank()) mergeUefa(domestic, JSONObject(uefaText))
            var changed = false
            var totalBets = 0
            var pendingAuto = 0
            synchronized(AutoPaperExecutionLock) {
                val bets = JSONArray(prefs.getString("bets", "[]") ?: "[]")
                changed = process(domestic, bets)
                if (changed) prefs.edit().putString("bets", bets.toString()).commit()
                totalBets = bets.length()
                for (i in 0 until bets.length()) {
                    val bet = bets.optJSONObject(i) ?: continue
                    if (bet.optString("source") == "AI_AUTO" && bet.optString("status") == "PENDING") pendingAuto++
                }
            }
            settings.edit()
                .putLong("paper_worker_last_run_ms", System.currentTimeMillis())
                .putString("paper_worker_last_status", "SUCCESS")
                .putString("paper_worker_last_message", "Проверка выполнена; новых изменений: $changed")
                .putInt("paper_worker_total_bets", totalBets)
                .putInt("paper_worker_pending_auto", pendingAuto)
                .commit()
            Log.i(TAG, "Auto-paper worker succeeded; changed=$changed totalBets=$totalBets pendingAuto=$pendingAuto elapsedMs=${System.currentTimeMillis() - startedAt}")
            Result.success()
        } catch (error: Exception) {
            settings.edit()
                .putLong("paper_worker_last_run_ms", System.currentTimeMillis())
                .putString("paper_worker_last_status", "ERROR")
                .putString("paper_worker_last_message", error.javaClass.simpleName + ": " + (error.message ?: "unknown").take(180))
                .commit()
            Log.w(TAG, "Auto-paper worker failed; retrying", error)
            Result.retry()
        }
    }

    private fun download(url: String): String = downloadOrNull(url) ?: error(lastDownloadFailure ?: "Feed unavailable")

    private fun downloadOrNull(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 18000
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code from ${URL(url).host}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Exception) {
            lastDownloadFailure = "${error.javaClass.simpleName}: ${error.message ?: "network or feed error"}"
            Log.w(TAG, "Feed request failed: $url", error)
            null
        } finally { connection?.disconnect() }
    }

    private fun mergeUefa(target: JSONObject, uefa: JSONObject) {
        val dest = target.optJSONArray("competitions") ?: JSONArray().also { target.put("competitions", it) }
        val src = uefa.optJSONArray("competitions") ?: return
        for (i in 0 until src.length()) {
            val comp = src.optJSONObject(i) ?: continue
            val code = comp.optString("code")
            if (code != "UCL" && code != "UEL") continue
            for (j in dest.length() - 1 downTo 0) if (dest.optJSONObject(j)?.optString("code") == code) dest.remove(j)
            dest.put(comp)
        }
    }

    private fun process(feed: JSONObject, bets: JSONArray): Boolean {
        var changed = false
        // Settle every pending single from the latest official score feed.
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("status") != "PENDING" || bet.optString("source") != "AI_AUTO") continue
            val match = findMatch(feed, bet) ?: continue
            if (settle(bet, match)) changed = true
        }

        val competitions = feed.optJSONArray("competitions") ?: return changed
        val now = System.currentTimeMillis()
        val bankBeforeEntries = currentBank(bets)
        val storedHigh = settings.getString("paper_high_watermark", null)?.toDoubleOrNull()
        val highWater = max(storedHigh ?: bankBeforeEntries, bankBeforeEntries)
        if (storedHigh == null || highWater > storedHigh) settings.edit().putString("paper_high_watermark", highWater.toString()).commit()
        val drawdown = if (highWater > 0.0) (highWater - bankBeforeEntries) / highWater else 0.0
        if (drawdown >= 0.10) settings.edit().putBoolean("paper_auto_stopped", true).commit()
        val autoStopped = settings.getBoolean("paper_auto_stopped", false)
        val drawdownPaused = drawdown >= 0.05
        for (ci in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(ci) ?: continue
            val matches = competition.optJSONArray("matches") ?: continue
            for (mi in 0 until matches.length()) {
                val match = matches.optJSONObject(mi) ?: continue
                if (match.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                val kickoff = parseInstant(match.optString("utcDate")) ?: continue
                val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val kickoffDate = dayFormat.format(Date(kickoff))
                val today = dayFormat.format(Date(now))
                val hours = (kickoff - now) / 3600000.0
                if (kickoffDate != today || hours !in 0.0..12.0) continue

                val odds = match.optJSONObject("odds") ?: continue
                if (!odds.optString("source").contains("BetBoom", true)) continue
                val quoteTime = parseInstant(odds.optString("updatedAt")) ?: continue
                if (quoteTime > now || quoteTime < kickoff - 12L * 60L * 60L * 1000L) continue

                val signal = (match.optJSONObject("prediction")?.optJSONObject("strongestSignal")
                    ?: if (competition.optString("code") == "UCL" || competition.optString("code") == "UEL") uefaSignal(match) else null)
                    ?: continue
                if (signal.optBoolean("noPositiveSignal") || signal.optString("side") == "ОЗ НЕТ") continue
                val price = priceFor(signal, match) ?: continue
                val probability = signal.optDouble("modelProbability", Double.NaN)
                val edge = if (signal.optBoolean("paperOnly")) signal.optDouble("edgePp", Double.NaN)
                    else (probability - 1.0 / price) * 100.0
                val ev = probability * price - 1.0
                if (!probability.isFinite() || probability < 0.60 || !edge.isFinite() || edge < 5.0 || ev < 0.05 || price < 1.50) continue
                val key = marketKey(signal.optString("side"), match) ?: continue
                val matchId = match.optString("id").ifBlank { match.optString("utcDate") + match.optString("home") + match.optString("away") }

                var duplicate = false
                for (bi in 0 until bets.length()) {
                    val prior = bets.optJSONObject(bi) ?: continue
                    if (prior.optString("id") == "AI_AUTO:$matchId:$key" ||
                        (prior.optString("matchId") == matchId && prior.optString("status") in listOf("PENDING", "WON", "LOST"))) {
                        duplicate = true; break
                    }
                }
                if (duplicate || autoStopped || drawdownPaused) continue

                val bank = currentBank(bets)
                val stake = max(bank * 0.01, 25.0)
                if (stake > bank * 0.01 + 0.000001) continue
                val dayKey = kickoffDate
                var daily = 0.0
                for (bi in 0 until bets.length()) {
                    val prior = bets.optJSONObject(bi) ?: continue
                    if ((prior.optString("betDay").ifBlank { prior.optString("date").take(10) } == dayKey) &&
                        prior.optString("status") in listOf("PENDING", "WON", "LOST")) daily += prior.optDouble("stake")
                }
                if (daily + stake > bank * 0.05 + 0.000001) continue

                bets.put(JSONObject()
                    .put("id", "AI_AUTO:$matchId:$key")
                    .put("source", "AI_AUTO").put("status", "PENDING")
                    .put("matchId", matchId).put("league", competition.optString("name"))
                    .put("date", match.optString("utcDate")).put("betDay", dayKey)
                    .put("home", match.optString("home")).put("away", match.optString("away"))
                    .put("market", signal.optString("side")).put("marketKey", key)
                    .put("odds", price).put("stake", stake).put("probability", probability)
                    .put("edgePp", edge).put("expectedValue", ev)
                    .put("quoteUpdatedAt", odds.optString("updatedAt"))
                    .put("createdAt", now).put("matchStatus", "SCHEDULED").put("score", ""))
                changed = true
            }
        }
        return changed
    }

    private fun parseInstant(raw: String): Long? {
        return try {
            var normalized = raw.trim().replace(Regex("\\.\\d+"), "")
            normalized = normalized.replace("T", " ").replace("Z", "+0000")
                .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).apply { isLenient = false }
            format.parse(normalized)?.time
        } catch (_: Exception) { null }
    }

    private fun findMatch(feed: JSONObject, bet: JSONObject): JSONObject? {
        val comps = feed.optJSONArray("competitions") ?: return null
        for (i in 0 until comps.length()) {
            val matches = comps.optJSONObject(i)?.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                val id = match.optString("id").ifBlank { match.optString("utcDate") + match.optString("home") + match.optString("away") }
                if (id == bet.optString("matchId")) return match
            }
        }
        return null
    }

    private fun settle(bet: JSONObject, match: JSONObject): Boolean {
        if (match.optString("status") != "FINISHED" || match.isNull("homeScore") || match.isNull("awayScore")) return false
        val h = match.optInt("homeScore"); val a = match.optInt("awayScore")
        val key = bet.optString("marketKey")
        val line = key.takeLast(2).toDoubleOrNull()?.div(10.0)
        val won: Boolean? = when (key) {
            "1x" -> h >= a
            "x2" -> a >= h
            "bttsYes" -> h > 0 && a > 0
            "bttsNo" -> h == 0 || a == 0
            "homeOver15" -> h >= 2
            "awayOver15" -> a >= 2
            else -> when {
                key.startsWith("over") && line != null -> when { h + a > line -> true; h + a < line -> false; else -> null }
                key.startsWith("homeOver") && line != null -> when { h > line -> true; h < line -> false; else -> null }
                key.startsWith("awayOver") && line != null -> when { a > line -> true; a < line -> false; else -> null }
                else -> null
            }
        }
        bet.put("status", when (won) { true -> "WON"; false -> "LOST"; null -> "PUSH" })
        bet.put("score", "$h:$a")
        bet.put("matchStatus", "FINISHED")
        return true
    }

    private fun currentBank(bets: JSONArray): Double {
        val start = settings.getString("initial_bankroll", "2500")?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: 2500.0
        var bank = start
        for (i in 0 until bets.length()) {
            val b = bets.optJSONObject(i) ?: continue
            val stake = b.optDouble("stake"); val odds = b.optDouble("odds")
            when (b.optString("status")) {
                "WON" -> bank += stake * (odds - 1.0)
                "LOST", "PENDING" -> bank -= stake
            }
        }
        return bank.coerceAtLeast(0.0)
    }

    private fun marketKey(side: String, match: JSONObject): String? {
        if (side == "1X") return "1x"
        if (side == "X2") return "x2"
        if (side == "ОЗ ДА") return "bttsYes"
        if (side.startsWith("ТБ ")) {
            val line = side.removePrefix("ТБ ").trim().toDoubleOrNull() ?: return null
            if (abs(line * 10.0 - (line * 10.0).toInt()) > 0.001) return null
            return "over" + (line * 10).toInt().toString().padStart(2, '0')
        }
        if (side.startsWith("ИТБ ")) {
            val line = side.removePrefix("ИТБ ").substringBefore("·").trim().toDoubleOrNull() ?: return null
            if (abs(line * 10.0 - (line * 10.0).toInt()) > 0.001) return null
            val team = side.substringAfter("·", "").trim()
            val prefix = when (team) { match.optString("home") -> "homeOver"; match.optString("away") -> "awayOver"; else -> return null }
            return prefix + (line * 10).toInt().toString().padStart(2, '0')
        }
        return null
    }

    private fun priceFor(signal: JSONObject, match: JSONObject): Double? {
        val side = signal.optString("side")
        val best = match.optJSONObject("odds")?.optJSONObject("best") ?: return null
        fun valid(v: Double) = v.isFinite() && v >= 1.50
        if (side == "1X" || side == "X2") {
            val dc = best.optJSONObject("doubleChance") ?: return null
            return dc.optDouble(if (side == "1X") "1x" else "x2", Double.NaN).takeIf { valid(it) }
        }
        if (side == "ОЗ ДА") return best.optJSONObject("btts")?.optDouble("yes", Double.NaN)?.takeIf { valid(it) }
        if (side.startsWith("ТБ ")) {
            val point = side.removePrefix("ТБ ").trim().toDoubleOrNull() ?: return null
            val rows = best.optJSONArray("totals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("side") == "over" && abs(row.optDouble("point") - point) < 0.001)
                    return row.optDouble("price", Double.NaN).takeIf { valid(it) }
            }
        }
        if (side.startsWith("ИТБ ")) {
            val point = side.removePrefix("ИТБ ").substringBefore("·").trim().toDoubleOrNull() ?: return null
            val team = if (side.substringAfter("·", "").trim() == match.optString("home")) "home" else "away"
            val rows = best.optJSONArray("teamTotals") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("team") == team && row.optString("side") == "over" && abs(row.optDouble("point") - point) < 0.001)
                    return row.optDouble("price", Double.NaN).takeIf { valid(it) }
            }
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
                "team_total_over" -> "ИТБ " + row.optDouble("point").toString() + " · " + if (row.optString("team") == "home") match.optString("home") else match.optString("away")
                "btts" -> if (row.optString("outcome") == "yes") "ОЗ ДА" else ""
                else -> ""
            }
            if (side.isBlank()) continue
            val probability = row.optDouble("model_probability", Double.NaN)
            val edge = row.optDouble("edge", Double.NaN)
            val archivedPrice = row.optDouble("price", Double.NaN)
            val archivedEv = row.optDouble("expected_value", Double.NaN)
            val signal = JSONObject().put("side", side).put("modelProbability", probability)
                .put("edgePp", edge * 100.0).put("paperOnly", true)
            val price = priceFor(signal, match) ?: continue
            if (!archivedPrice.isFinite() || abs(price - archivedPrice) >= 0.005) continue
            if (!probability.isFinite() || probability < 0.60 || !edge.isFinite() || edge < 0.05 ||
                !archivedEv.isFinite() || archivedEv < 0.05) continue
            val ev = probability * price - 1.0
            if (ev > bestEv) { bestEv = ev; selected = signal }
        }
        return selected
    }
}
