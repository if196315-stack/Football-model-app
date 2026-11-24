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
            // Keep the previous UEFA snapshot if its independent endpoint is temporarily unavailable.
            // Otherwise a successful domestic refresh could overwrite the merged cache without UCL/UEL/UECL.
            val cachedMerged = FeedCacheStore.read(applicationContext.filesDir, "merged_json")
            val cachedUefa = FeedCacheStore.read(applicationContext.filesDir, "uefa_json")
            val uefaText = downloadOrNull("https://raw.githubusercontent.com/if196315-stack/Football-model-app/main/docs/uefa-data.json")
            val uefa = FeedMerger.resolveUefaFeed(uefaText, cachedUefa, cachedMerged)
            if (uefa != null) {
                if (FeedMerger.parseUefaFeed(uefaText) != null && !uefaText.isNullOrBlank()) {
                    if (!FeedCacheStore.write(applicationContext.filesDir, "uefa_json", uefaText)) {
                        Log.w(TAG, "Unable to persist UEFA feed cache; using downloaded payload for this run")
                    }
                }
                mergeUefa(domestic, uefa)
            }
            // Notify only for newly qualified TOP matches that are not already recorded by the user.
            val newTopCandidates = synchronized(AutoPaperExecutionLock) {
                collectAiNotificationCandidates(domestic)
            }
            AiMatchNotifications.observe(applicationContext, newTopCandidates)
            // Persist the combined feed so MainActivity can restore background updates on launch.
            val mergedPayload = domestic.toString()
            if (cachedMerged != mergedPayload) {
                check(FeedCacheStore.write(applicationContext.filesDir, "merged_json", mergedPayload)) {
                    "Unable to persist refreshed match feed"
                }
            }
            check(feedCache.edit()
                .remove("merged_json")
                .putLong("last_successful_check_ms", System.currentTimeMillis())
                .commit()) { "Unable to persist refreshed match metadata" }
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
        FeedMerger.mergeUefa(target, uefa)
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
                if (!isMelBet || !hasFreshMelBetQuote(match)) continue
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
                val matchId = match.optString("id").ifBlank {
                    listOf(match.optString("utcDate"), match.optString("home"), match.optString("away")).joinToString("|")
                }
                // Notify for the same single market that the foreground TOP screen
                // ranks first. Select before checking saved bets; otherwise a lower-ranked
                // alternative could trigger a notification while the UI hides that match.
                val ranked = allTopSignals(prediction, match, code).mapNotNull { signal ->
                    val price = priceFor(signal, match) ?: return@mapNotNull null
                    val probability = signal.optDouble("modelProbability", Double.NaN)
                    Triple(signal, price, probability)
                }
                val selectedIndex = TopSignalRanking.bestIndex(ranked.map { it.third to it.second }) ?: continue
                val signal = ranked[selectedIndex].first
                val price = ranked[selectedIndex].second
                if (signal.optBoolean("noPositiveSignal") ||
                    signal.optString("side").equals("ОЗ НЕТ", ignoreCase = true) || price < 1.50) continue
                val signalMarketKey = marketKey(signal.optString("side"), match) ?: continue
                val alreadyRecorded = (0 until savedBets.length()).any { index ->
                    val bet = savedBets.optJSONObject(index) ?: return@any false
                    bet.optString("matchId") == matchId &&
                        bet.optString("marketKey") == signalMarketKey &&
                        bet.optString("source") in listOf("AI_MANUAL", "AI_AUTO")
                }
                if (alreadyRecorded) continue
                val title = "${match.optString("home")} — ${match.optString("away")} · ${signal.optString("side")}"
                matchesFound.add("${matchId}::${signalMarketKey}" to title)
            }
        }
        return matchesFound
    }

    // Return all independently qualified markets, while preserving strict AI thresholds.
    private fun allTopSignals(prediction: JSONObject, match: JSONObject, code: String): List<JSONObject> {
        val candidates = mutableListOf<JSONObject>()
        prediction.optJSONObject("strongestSignal")?.let { candidates.add(it) }
        val signals = prediction.optJSONArray("signals")
        if (signals != null) for (index in 0 until signals.length()) {
            val candidate = signals.optJSONObject(index) ?: continue
            candidates.add(candidate)
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
        // Match the foreground selector: legacy paper signals are a fallback
        // only when the model feed supplied no market candidates at all.
        if (candidates.isEmpty() && (code == "UCL" || code == "UEL" || code == "UECL")) {
            uefaSignal(match)?.let { candidates.add(it) }
        }
        return candidates.distinctBy { it.optString("side").trim().lowercase(Locale.ROOT) }
            .filter { candidate ->
                val side = candidate.optString("side")
                val probability = candidate.optDouble("modelProbability", Double.NaN)
                val price = priceFor(candidate, match)
                val edge = if (candidate.optBoolean("paperOnly")) candidate.optDouble("edgePp", Double.NaN)
                    else if (price != null) (probability - 1.0 / price) * 100.0 +
                        candidate.optDouble("formAdjustmentPp", 0.0)
                    else Double.NaN
                if (edge.isFinite()) candidate.put("edgePp", edge)
                marketKey(side, match)?.let(ActiveMarketRules::contains) == true &&
                    probability.isFinite() && probability in 0.0..1.0 &&
                    price != null && edge.isFinite() && edge >= 5.0 &&
                    probability >= 0.60 && probability * price - 1.0 >= 0.05
            }
    }

    private fun process(feed: JSONObject, bets: JSONArray): Boolean {
        var changed = false
        // Settle every pending manual/AI single and coupon in the background.
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("status") != "PENDING") continue
            if (bet.optString("marketKey") == "COUPON") {
                if (settleCoupon(bet, feed)) changed = true
                continue
            }
            val match = findMatch(feed, bet) ?: continue
            if (settle(bet, match)) changed = true
        }
        // Automatic creation is disabled; existing pending bets are still settled above.
        return changed
    }

    private fun settleCoupon(bet: JSONObject, feed: JSONObject): Boolean {
        if (bet.optString("status") != "PENDING") return false
        val selections = bet.optJSONArray("selections") ?: return false
        val beforeStatus = bet.optString("status")
        val beforeScores = bet.optString("couponScores")
        val beforeMatchStatus = bet.optString("matchStatus")
        val beforeOdds = bet.optDouble("odds", 1.0)
        var allSettled = true
        var lost = false
        var effectiveOdds = 1.0
        val scores = mutableListOf<String>()
        for (i in 0 until selections.length()) {
            val pick = selections.optJSONObject(i) ?: continue
            val match = findMatch(feed, pick)
            if (match == null || match.isNull("homeScore") || match.isNull("awayScore")) {
                allSettled = false
                continue
            }
            val matchStatus = match.optString("status")
            val scoreLabel = pick.optString("home") + " " + match.optInt("homeScore") +
                ":" + match.optInt("awayScore") + " " + pick.optString("away")
            if (isFinishedMatch(match)) {
                val leg = JSONObject().put("status", "PENDING")
                    .put("marketKey", pick.optString("marketKey"))
                if (!settle(leg, match)) {
                    allSettled = false
                    continue
                }
                val result = leg.optString("status")
                scores.add(scoreLabel + " · ФИНАЛ")
                if (result == "LOST") lost = true
                if (result == "WON") effectiveOdds *= pick.optDouble("odds", 1.0)
            } else {
                allSettled = false
                if (matchStatus in listOf("IN_PLAY", "LIVE", "PAUSED")) {
                    scores.add(scoreLabel + " · LIVE")
                }
            }
        }
        bet.put("couponScores", scores.joinToString(" · "))
        val anyLive = scores.any { it.contains("LIVE") }
        bet.put("matchStatus", if (anyLive) "IN_PLAY" else if (allSettled) "FINISHED" else "SCHEDULED")
        if (lost) bet.put("status", "LOST")
        else if (allSettled) {
            bet.put("status", if (effectiveOdds > 1.0) "WON" else "PUSH")
            bet.put("odds", effectiveOdds)
        }
        return beforeStatus != bet.optString("status") ||
            beforeScores != bet.optString("couponScores") ||
            beforeMatchStatus != bet.optString("matchStatus") ||
            kotlin.math.abs(beforeOdds - bet.optDouble("odds", 1.0)) > 0.000001
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

    private fun isFinishedMatch(match: JSONObject): Boolean {
        return listOf(match.optString("status"), match.optString("statusShort"), match.optString("statusLong"))
            .map { it.trim().uppercase(Locale.ROOT) }
            .any { it in setOf("FINISHED", "FT", "AET", "PEN", "AFTER EXTRA TIME", "AFTER PENALTIES", "MATCH FINISHED") }
    }

    private fun normalizedTeamName(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun findMatch(feed: JSONObject, bet: JSONObject): JSONObject? {
        val competitions = feed.optJSONArray("competitions") ?: return null
        val byId = HashMap<String, MutableList<JSONObject>>()
        val byTeamsAndDate = HashMap<String, MutableList<JSONObject>>()
        val byTeams = HashMap<String, MutableList<JSONObject>>()
        for (i in 0 until competitions.length()) {
            val matches = competitions.optJSONObject(i)?.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                val id = match.optString("id").trim().ifBlank {
                    match.optString("utcDate") + match.optString("home") + match.optString("away")
                }
                if (id.isNotBlank()) byId[id] = match
                val home = MatchIdentity.normalizedTeam(match.optString("home"))
                val away = MatchIdentity.normalizedTeam(match.optString("away"))
                if (home.isBlank() || away.isBlank()) continue
                val key = "$home|$away"
                byTeams.getOrPut(key) { mutableListOf() }.add(match)
                val date = MatchIdentity.matchDate(match)
                if (date.isNotBlank()) MatchIdentity.uniqueMatch(byTeamsAndDate["$key|$date"]) = match
            }
        }
        val targetId = bet.optString("matchId").trim()
        val exact = if (targetId.isNotBlank()) MatchIdentity.uniqueMatch(byId[targetId]) else null
        if (exact != null && MatchIdentity.sameFixture(bet, exact)) return exact
        val home = MatchIdentity.normalizedTeam(bet.optString("home"))
        val away = MatchIdentity.normalizedTeam(bet.optString("away"))
        if (home.isBlank() || away.isBlank()) return null
        val key = "$home|$away"
        val date = MatchIdentity.betDate(bet)
        return if (date.isNotBlank()) byTeamsAndDate["$key|$date"] else MatchIdentity.uniqueMatch(byTeams[key])
    }

    private fun settle(bet: JSONObject, match: JSONObject): Boolean {
        val hasFinalFlag = match.has("scoreStatusFinal")
        val finalFlag = if (hasFinalFlag && !match.isNull("scoreStatusFinal"))
            match.optBoolean("scoreStatusFinal") else null
        val homeScore = if (match.isNull("homeScore")) null else match.optInt("homeScore")
        val awayScore = if (match.isNull("awayScore")) null else match.optInt("awayScore")
        val result = BetSettlement.settledResult(
            bet.optString("status"), match.optString("status"),
            hasFinalFlag, finalFlag, homeScore, awayScore, bet.optString("marketKey")
        ) ?: return false
        bet.put("status", result)
            .put("score", "$homeScore:$awayScore")
            .put("matchStatus", "FINISHED")
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

    private fun hasFreshMelBetQuote(match: JSONObject): Boolean {
        val odds = match.optJSONObject("odds") ?: return false
        val bookmaker = odds.optString("bookmaker")
        val isMelBet = if (bookmaker.isNotBlank()) bookmaker.equals("MELBET", ignoreCase = true)
            else odds.optString("source").contains("MelBet", ignoreCase = true)
        if (!isMelBet || !odds.optBoolean("prematchActive", false) ||
            !odds.optString("freshnessStatus").equals("fresh", ignoreCase = true)) return false
        val updatedAt = parseInstant(odds.optString("updatedAt"))
            ?: return false
        val age = System.currentTimeMillis() - updatedAt
        return age >= -5 * 60 * 1000L && age <= 12 * 60 * 60 * 1000L
    }

    private fun priceFor(signal: JSONObject, match: JSONObject): Double? {
        val side = signal.optString("side")
        if (!hasFreshMelBetQuote(match)) return null
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
