package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.Normalizer

/**
 * Bridges backend UEFA paperSignals into the app's persistent Bets history.
 * The feed/archive is authoritative for these paper-only entries; importing is
 * idempotent and updates a matching pending local row instead of duplicating it.
 */
internal object UefaPaperBetImporter {
    private const val SETTLED_PAPER_STAKE_RUB = 25.0
    fun sync(bets: JSONArray, feed: JSONObject): Boolean {
        var changed = deduplicateImportedRows(bets)
        val bySignalId = HashMap<String, JSONObject>()
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val signalId = bet.optString("paperSignalId").ifBlank {
                bet.optString("id").removePrefix("UEFA_PAPER:")
                    .takeIf { bet.optString("id").startsWith("UEFA_PAPER:") }.orEmpty()
            }
            if (signalId.isNotBlank() && isImportedPaperRow(bet)) bySignalId[signalId] = bet
        }

        val competitions = feed.optJSONArray("competitions") ?: return false
        for (competitionIndex in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(competitionIndex) ?: continue
            val matches = competition.optJSONArray("matches") ?: continue
            for (matchIndex in 0 until matches.length()) {
                val match = matches.optJSONObject(matchIndex) ?: continue
                val matchId = match.optString("id").trim()
                if (matchId.isBlank()) continue
                val signals = match.optJSONArray("paperSignals") ?: continue
                for (signalIndex in 0 until signals.length()) {
                    val signal = signals.optJSONObject(signalIndex) ?: continue
                    val signalId = signal.optString("signal_id").trim()
                    if (signalId.isBlank()) continue
                    val marketKey = marketKey(signal) ?: continue
                    val odds = signal.optDouble("price", Double.NaN)
                    if (!odds.isFinite() || odds <= 1.0) continue

                    val matchStatus = match.optString("status").trim().uppercase()
                    val homeScore = if (match.isNull("homeScore")) null else match.optInt("homeScore")
                    val awayScore = if (match.isNull("awayScore")) null else match.optInt("awayScore")
                    val result = when (signal.optString("result").trim().uppercase()) {
                        "WIN", "WON" -> "WON"
                        "LOSS", "LOST" -> "LOST"
                        "PUSH" -> "PUSH"
                        else -> if (BetSettlement.isConfirmedFinal(
                                matchStatus, match.has("scoreStatusFinal"),
                                if (match.has("scoreStatusFinal") && !match.isNull("scoreStatusFinal"))
                                    match.optBoolean("scoreStatusFinal") else null
                            ) && homeScore != null && awayScore != null
                        ) BetSettlement.result(marketKey, homeScore, awayScore) else null
                    }
                    val desiredStatus = result ?: "PENDING"
                    val score = if (homeScore != null && awayScore != null) "$homeScore:$awayScore" else ""
                    val existing = bySignalId[signalId] ?: findMatchingBet(bets, match, marketKey, odds)

                    if (existing != null) {
                        var rowChanged = false
                        if (existing.optString("paperSignalId") != signalId) {
                            existing.put("paperSignalId", signalId)
                            rowChanged = true
                        }
                        if (existing.optString("matchId") != matchId) {
                            existing.put("matchId", matchId)
                            rowChanged = true
                        }
                        if (existing.optString("status") == "PENDING" &&
                            desiredStatus != "PENDING") {
                            existing.put("status", desiredStatus)
                                .put("matchStatus", matchStatus)
                                .put("score", score)
                                .put("paperProfitUnits", signal.optDouble("profit_units", 0.0))
                                .put("settledAt", signal.optString("settled_at"))
                            rowChanged = true
                        }
                        // Migrate settled imported UEFA paper rows to 25 RUB.
                        // Do not overwrite a user's own/manual stake.
                        if (isImportedPaperRow(existing)) {
                            if (existing.optString("source") != "UEFA_PAPER") {
                                existing.put("source", "UEFA_PAPER")
                                rowChanged = true
                            }
                            if (existing.optDouble("stake", Double.NaN) != SETTLED_PAPER_STAKE_RUB) {
                                existing.put("stake", SETTLED_PAPER_STAKE_RUB)
                                rowChanged = true
                            }
                        }
                        if (rowChanged) changed = true
                        bySignalId[signalId] = existing
                        continue
                    }

                    val side = marketLabel(signal, match)
                    val imported = JSONObject()
                        .put("id", "UEFA_PAPER:$signalId")
                        .put("paperSignalId", signalId)
                        .put("source", "UEFA_PAPER")
                        .put("paperOnly", true)
                        .put("status", desiredStatus)
                        .put("matchId", matchId)
                        .put("league", competition.optString("name").ifBlank { competition.optString("code") })
                        .put("date", match.optString("utcDate"))
                        .put("kickoff", match.optString("utcDate"))
                        .put("home", match.optString("home"))
                        .put("away", match.optString("away"))
                        .put("market", side)
                        .put("marketKey", marketKey)
                        .put("odds", odds)
                        .put("stake", SETTLED_PAPER_STAKE_RUB)
                        .put("probability", signal.optDouble("model_probability", 0.0))
                        .put("matchStatus", matchStatus)
                        .put("score", score)
                        .put("paperProfitUnits", signal.optDouble("profit_units", 0.0))
                        .put("createdAt", System.currentTimeMillis())
                    if (desiredStatus != "PENDING") {
                        imported.put("settledAt", signal.optString("settled_at"))
                    }
                    bets.put(imported)
                    bySignalId[signalId] = imported
                    changed = true
                }
            }
        }
        changed = deduplicateImportedRows(bets) || changed
        changed = removeLegacyPaperSignalDuplicates(bets, feed) || changed
        return changed
    }

    private fun findMatchingBet(bets: JSONArray, match: JSONObject, marketKey: String,
                                odds: Double): JSONObject? {
        val fixture = fixtureKey(match)
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (!isImportedPaperRow(bet) ||
                fixtureKey(bet) != fixture ||
                bet.optString("marketKey") != marketKey) continue
            val existingOdds = bet.optDouble("odds", Double.NaN)
            if (existingOdds.isFinite() && abs(existingOdds - odds) < 0.005) return bet
        }
        // Re-issued paper signals may carry refreshed odds. Reuse the imported
        // row for the same fixture + market rather than creating a duplicate.
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (isImportedPaperRow(bet) &&
                fixtureKey(bet) == fixture &&
                bet.optString("marketKey") == marketKey) return bet
        }
        return null
    }

    private fun isImportedPaperRow(bet: JSONObject): Boolean =
        bet.optString("source") == "UEFA_PAPER" ||
            bet.optString("id").startsWith("UEFA_PAPER:") ||
            bet.optBoolean("paperOnly") ||
            bet.optString("paperSignalId").isNotBlank()

    private fun deduplicateImportedRows(bets: JSONArray): Boolean {
        val keeperByKey = LinkedHashMap<String, JSONObject>()
        val removeIndices = ArrayList<Int>()
        var changed = false
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (!isImportedPaperRow(bet)) continue
            if (bet.optString("source") != "UEFA_PAPER") {
                bet.put("source", "UEFA_PAPER")
                changed = true
            }
            val marketKey = bet.optString("marketKey").ifBlank { normalizeText(bet.optString("market")) }
            if (marketKey.isBlank()) continue
            val key = "${fixtureKey(bet)}|$marketKey"
            val keeper = keeperByKey[key]
            if (keeper == null) {
                keeperByKey[key] = bet
                continue
            }
            val currentSettled = BetSettlement.isSettledStatus(bet.optString("status"))
            val keeperSettled = BetSettlement.isSettledStatus(keeper.optString("status"))
            if (currentSettled && !keeperSettled) {
                val keeperIndex = (0 until bets.length()).firstOrNull {
                    bets.optJSONObject(it) === keeper
                }
                if (keeperIndex != null) removeIndices.add(keeperIndex)
                keeperByKey[key] = bet
            } else {
                removeIndices.add(i)
            }
        }
        for (index in removeIndices.sortedDescending()) bets.remove(index)
        for (index in removeIndices.sortedDescending()) bets.remove(index)
        return changed || removeIndices.isNotEmpty()
    }

    private fun removeLegacyPaperSignalDuplicates(bets: JSONArray, feed: JSONObject): Boolean {
        val paperKeys = HashSet<String>()
        val competitions = feed.optJSONArray("competitions") ?: return false
        for (ci in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(ci) ?: continue
            val matches = competition.optJSONArray("matches") ?: continue
            for (mi in 0 until matches.length()) {
                val match = matches.optJSONObject(mi) ?: continue
                val matchKey = fixtureKey(match)
                val signals = match.optJSONArray("paperSignals") ?: continue
                for (si in 0 until signals.length()) {
                    val signal = signals.optJSONObject(si) ?: continue
                    val market = marketKey(signal) ?: continue
                    paperKeys.add("$matchKey|$market")
                }
            }
        }
        if (paperKeys.isEmpty()) return false
        val remove = ArrayList<Int>()
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (!bet.optString("source").equals("AI_MANUAL", true)) continue
            val market = bet.optString("marketKey")
            if (market.isBlank() || "${fixtureKey(bet)}|$market" !in paperKeys) continue
            val matchId = bet.optString("matchId")
            val legacyPaperId = matchId.isNotBlank() &&
                bet.optString("id").startsWith("AI_MANUAL:$matchId:$market:")
            val taggedPaper = bet.optBoolean("paperOnly") || bet.optString("paperSignalId").isNotBlank()
            // Older builds recorded a paper-only recommendation as AI_MANUAL and omitted its marker.
            if (taggedPaper || (legacyPaperId && bet.optDouble("stake", Double.NaN) <= 1.0001)) {
                remove.add(i)
            }
        }
        for (index in remove.sortedDescending()) bets.remove(index)
        return remove.isNotEmpty()
    }

    private fun fixtureKey(row: JSONObject): String {
        val home = normalizeText(row.optString("home"))
        val away = normalizeText(row.optString("away"))
        val date = row.optString("date").ifBlank {
            row.optString("kickoff").ifBlank { row.optString("utcDate") }
        }.take(10)
        if (home.isNotBlank() && away.isNotBlank() && date.isNotBlank()) {
            return "$home|$away|$date"
        }
        return row.optString("matchId").trim().ifBlank { row.optString("id").trim() }
    }

    private fun normalizeText(value: String): String = Normalizer.normalize(
        value.trim().lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun marketKey(signal: JSONObject): String? {
        val point = signal.optDouble("point", Double.NaN)
        val line = if (point.isFinite()) (point * 10.0).roundToInt() else return when (signal.optString("market")) {
            "btts" -> if (signal.optString("outcome") == "yes") "bttsYes" else "bttsNo"
            "double_chance" -> when (signal.optString("outcome")) {
                "1x" -> "1x"
                "x2" -> "x2"
                else -> null
            }
            else -> null
        }
        return when (signal.optString("market")) {
            "btts" -> if (signal.optString("outcome") == "yes") "bttsYes" else "bttsNo"
            "double_chance" -> when (signal.optString("outcome")) {
                "1x" -> "1x"
                "x2" -> "x2"
                else -> null
            }
            "match_total_over" -> "over$line"
            "team_total_over" -> when (signal.optString("team")) {
                "home" -> "homeOver$line"
                "away" -> "awayOver$line"
                else -> null
            }
            else -> null
        }
    }

    private fun marketLabel(signal: JSONObject, match: JSONObject): String = when (signal.optString("market")) {
        "btts" -> if (signal.optString("outcome") == "yes") "Обе забьют — Да" else "Обе забьют — Нет"
        "double_chance" -> if (signal.optString("outcome") == "1x") "1X" else "X2"
        "match_total_over" -> "ТБ ${signal.optDouble("point")}"
        "team_total_over" -> {
            val team = if (signal.optString("team") == "home") match.optString("home") else match.optString("away")
            "ИТБ ${signal.optDouble("point")} · $team"
        }
        else -> signal.optString("market")
    }
}
