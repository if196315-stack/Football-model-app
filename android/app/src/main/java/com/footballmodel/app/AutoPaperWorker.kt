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
    private val feedCache = context.getSharedPreferences("feed_cache_v1", Context.MODE_PRIVATE)

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
            // Notify only for newly qualified TOP matches that are not already recorded by the user.
            val newTopCandidates = synchronized(AutoPaperExecutionLock) {
                collectAiNotificationCandidates(domestic)
            }
            AiMatchNotifications.observe(applicationContext, newTopCandidates)
            // Persist the combined feed so MainActivity can restore background updates on launch.
            val mergedPayload = domestic.toString()
            check(feedCache.edit()
                .putString("merged_json", mergedPayload)
                .putLong("last_successful_check_ms", System.currentTimeMillis())
                .commit()) { "Unable to persist refreshed match feed" }
            var changed = false
            var totalBets = 0
            var pendingAuto = 0
            val newAutoBets = mutableListOf<JSONObject>()
            synchronized(AutoPaperExecutionLock) {
                val bets = JSONArray(prefs.getString("bets", "[]") ?: "[]")
                val existingIds = (0 until bets.length()).mapNotNull { bets.optJSONObject(it)?.optString("id") }.toSet()
                val previousStatuses = mutableMapOf<String, String>()
                for (i in 0 until bets.length()) {
                    val before = bets.optJSONObject(i) ?: continue
                    val key = before.optString("id").ifBlank { before.optString("matchId") }
                    previousStatuses[key] = before.optString("status")
                }
                changed = process(domestic, bets)
                if (changed) {
                    for (i in 0 until bets.length()) {
                        val settled = bets.optJSONObject(i) ?: continue
                        val key = settled.optString("id").ifBlank { settled.optString("matchId") }
                        val oldStatus = previousStatuses[key]
                        val newStatus = settled.optString("status")
                        if (oldStatus == "PENDING" && newStatus in listOf("WON", "LOST", "PUSH")) {
                            val matchName = settled.optString("matchName").ifBlank {
                                settled.optString("home") + " — " + settled.optString("away")
                            }.trim(' ', '—')
                            val outcome = when (newStatus) { "WON" -> "WIN"; "LOST" -> "LOSS"; else -> "ВОЗВРАТ" }
                            val stake = settled.optDouble("stake", 0.0)
                            val amount = when (newStatus) {
                                "WON" -> stake * (settled.optDouble("odds", 0.0) - 1.0)
                                "LOST" -> -stake
                                else -> 0.0
                            }
                        }
                    }
                }
                val saved = !changed || prefs.edit().putString("bets", bets.toString()).commit()
                if (saved) {
                    for (i in 0 until bets.length()) {
                        val bet = bets.optJSONObject(i) ?: continue
                        if (bet.optString("source") == "AI_AUTO" && bet.optString("id") !in existingIds) {
                            newAutoBets.add(JSONObject(bet.toString()))
                        }
                    }
                }
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

    private fun collectAiNotificationCandidates(feed: JSONObject): List<Pair<String, String>> {
        val matchesFound = mutableListOf<Pair<String, String>>()
        val savedBets = JSONArray(prefs.getString("bets", "[]") ?: "[]")
        val competitions = feed.optJSONArray("competitions") ?: return matchesFound
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        for (i in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(i) ?: continue
            val code = competition.optString("code")
            val matches = competition.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                if (match.optString("status") !in listOf("SCHEDULED", "TIMED")) continue
                val odds = match.optJSONObject("odds") ?: continue
                val bookmaker = odds.optString("bookmaker")
                val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true)
                    else odds.optString("source").contains("MelBet", ignoreCase = true)
                if (!isMelBet) continue
                val matchDay = match.optString("utcDate").take(10).ifBlank {
                    match.optString("dateEvent").take(10).ifBlank {
                        val timestamp = match.optLong("startTimestamp", 0L)
                        if (timestamp > 0L) {
                            val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }.format(Date(millis))
                        } else ""
                    }
                }
                if (matchDay.isBlank() || matchDay < today) continue
                val prediction = match.optJSONObject("prediction") ?: continue
                val signal = strongestTopSignal(prediction, match)
                    ?: (if (code == "UCL" || code == "UEL") uefaSignal(match) else null)
                    ?: continue
                if (signal.optBoolean("noPositiveSignal") || signal.optDouble("edgePp") <= 0.0 ||
                    signal.optString("side").equals("ОЗ НЕТ", ignoreCase = true)) continue
                val price = priceFor(signal, match) ?: continue
                if (price < 1.50) continue
                val probability = signal.optDouble("modelProbability", Double.NaN)
                val ev = probability * price - 1.0
                val qualifies = if (signal.optBoolean("paperOnly")) {
                    probability >= 0.60 && signal.optDouble("edgePp") >= 5.0 && ev >= 0.05
                } else {
                    val impliedEdge = probability - 1.0 / price +
                        signal.optDouble("formAdjustmentPp", 0.0) / 100.0
                    probability >= 0.60 && impliedEdge >= 0.05 && ev >= 0.05
                }
                if (!qualifies) continue
                val id = match.optString("id").ifBlank {
                    listOf(match.optString("utcDate"), match.optString("home"), match.optString("away")).joinToString("|")
                }
                val signalMarketKey = marketKey(signal.optString("side"), match) ?: continue
                val alreadyRecorded = (0 until savedBets.length()).any { index ->
                    val bet = savedBets.optJSONObject(index) ?: return@any false
                    bet.optString("matchId") == id &&
                        bet.optString("marketKey") == signalMarketKey &&
                        bet.optString("source") in listOf("AI_MANUAL", "AI_AUTO")
                }
                if (alreadyRecorded) continue
                val title = "${match.optString("home")} — ${match.optString("away")}"
                matchesFound.add(id to title)
            }
        }
        return matchesFound
    }

    // Mirrors the TOP card primary-signal selection for background notifications.
    private fun strongestTopSignal(prediction: JSONObject, match: JSONObject): JSONObject? {
        val candidates = mutableListOf<JSONObject>()
        prediction.optJSONObject("strongestSignal")?.let { candidates.add(it) }
        val signals = prediction.optJSONArray("signals")
        if (signals != null) {
            for (index in 0 until signals.length()) {
                val candidate = signals.optJSONObject(index) ?: continue
                val key = candidate.optString("side").trim().lowercase(Locale.ROOT)
                if (candidates.none { it.optString("side").trim().lowercase(Locale.ROOT) == key }) candidates.add(candidate)
            }
        }
        val rawMarkets = listOf(
            "1x" to "1X", "x2" to "X2", "bttsYes" to "ОЗ ДА",
            "over15" to "ТБ 1.5", "over25" to "ТБ 2.5",
            "homeOver05" to ("ИТБ 0.5 · " + match.optString("home")),
            "homeOver15" to ("ИТБ 1.5 · " + match.optString("home")),
            "awayOver05" to ("ИТБ 0.5 · " + match.optString("away")),
            "awayOver15" to ("ИТБ 1.5 · " + match.optString("away"))
        )
        rawMarkets.forEach { (field, side) ->
            val probability = prediction.optDouble(field, Double.NaN)
            if (!probability.isFinite() || probability !in 0.0..1.0) return@forEach
            if (candidates.none { it.optString("side").trim().equals(side.trim(), ignoreCase = true) }) {
                candidates.add(JSONObject().put("side", side).put("modelProbability", probability))
            }
        }
        val available = candidates.mapNotNull { candidate ->
            if (marketKey(candidate.optString("side"), match) == null) return@mapNotNull null
            val probability = candidate.optDouble("modelProbability", Double.NaN)
            val odds = priceFor(candidate, match)
            if (!probability.isFinite() || probability !in 0.0..1.0 || odds == null) return@mapNotNull null
            val expectedValue = probability * odds - 1.0
            Triple(candidate, expectedValue, expectedValue / (odds - 1.0))
        }
        val positiveValue = available.filter { it.second > 0.0 }
        return if (positiveValue.isNotEmpty()) positiveValue.maxByOrNull { it.third }?.first
        else available.maxByOrNull { it.first.optDouble("modelProbability", Double.NEGATIVE_INFINITY) }?.first
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

        // Automatic creation is disabled; existing pending bets are still settled above.
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
        val start = settings.getString("initial_bankroll", null)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
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
