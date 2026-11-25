package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bridges backend UEFA paperSignals into the app's persistent Bets history.
 * The feed/archive is authoritative for these paper-only entries; importing is
 * idempotent and updates a matching pending local row instead of duplicating it.
 */
internal object UefaPaperBetImporter {
    fun sync(bets: JSONArray, feed: JSONObject): Boolean {
        var changed = false
        val bySignalId = HashMap<String, JSONObject>()
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            val signalId = bet.optString("paperSignalId").ifBlank {
                bet.optString("id").removePrefix("UEFA_PAPER:")
                    .takeIf { bet.optString("id").startsWith("UEFA_PAPER:") }.orEmpty()
            }
            if (signalId.isNotBlank()) bySignalId[signalId] = bet
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
                    val existing = bySignalId[signalId] ?: findMatchingBet(bets, matchId, marketKey, odds)

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
                        .put("stake", 1.0)
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
        return changed
    }

    private fun findMatchingBet(bets: JSONArray, matchId: String, marketKey: String,
                                odds: Double): JSONObject? {
        for (i in 0 until bets.length()) {
            val bet = bets.optJSONObject(i) ?: continue
            if (bet.optString("matchId") != matchId ||
                bet.optString("marketKey") != marketKey) continue
            val existingOdds = bet.optDouble("odds", Double.NaN)
            if (existingOdds.isFinite() && abs(existingOdds - odds) < 0.005) return bet
        }
        return null
    }

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
