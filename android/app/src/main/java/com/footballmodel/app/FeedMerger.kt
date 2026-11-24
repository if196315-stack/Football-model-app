package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the foreground feed refresh and background worker cache structurally
 * identical: UEFA competitions and immutable finished-match evaluations are
 * merged together, with evaluation IDs namespaced by league.
 */
internal object FeedMerger {
    /**
     * Scheduled matches in every competition use the same publication rule:
     * a prediction and at least one working market with a current MelBet quote. Live matches are
     * handled separately by the screen and never pass through this predicate.
     */
    fun shouldShowScheduledMatch(
        leagueCode: String,
        hasPrediction: Boolean,
        hasAtLeastOneActiveMelBetMarket: Boolean
    ): Boolean = hasPrediction && hasAtLeastOneActiveMelBetMarket

    private val liveStatuses = setOf(
        "IN_PLAY", "LIVE", "IN_PROGRESS", "INPROGRESS", "IN-PLAY",
        "FIRST_HALF", "SECOND_HALF", "IN_PLAY_FIRST_HALF", "IN_PLAY_SECOND_HALF",
        "1H", "2H", "PAUSED", "HALFTIME", "HALF_TIME", "BREAK"
    )
    private val terminalStatuses = setOf(
        "FINISHED", "FT", "FULL_TIME", "AET", "PEN", "AFTER EXTRA TIME",
        "AFTER PENALTIES", "MATCH FINISHED", "CANCELED", "CANCELLED",
        "AWARDED", "ABANDONED"
    )

    fun isLiveStatus(status: String): Boolean =
        status.trim().uppercase(java.util.Locale.ROOT) in liveStatuses

    fun isTerminalStatus(status: String): Boolean =
        status.trim().uppercase(java.util.Locale.ROOT) in terminalStatuses

    fun isScheduledStatus(status: String): Boolean =
        status.trim().uppercase(java.util.Locale.ROOT) in setOf("SCHEDULED", "TIMED")

    /** One shared publication/retention rule for domestic and UEFA competitions. */
    fun shouldKeepInLine(
        status: String,
        kickoffDayHasPassed: Boolean,
        hasAtLeastOneActiveMelBetMarket: Boolean
    ): Boolean {
        if (isLiveStatus(status)) return true
        if (isTerminalStatus(status)) return false
        if (kickoffDayHasPassed) return true
        return isScheduledStatus(status) &&
            shouldShowScheduledMatch("", true, hasAtLeastOneActiveMelBetMarket)
    }

    fun mergeUefa(target: JSONObject, uefa: JSONObject) {
        val targetCompetitions = target.optJSONArray("competitions")
            ?: JSONArray().also { target.put("competitions", it) }
        val uefaCompetitions = uefa.optJSONArray("competitions")
        if (uefaCompetitions != null) {
            for (i in 0 until uefaCompetitions.length()) {
                val competition = uefaCompetitions.optJSONObject(i) ?: continue
                val code = competition.optString("code")
                if (code != "UCL" && code != "UEL" && code != "UECL") continue
                for (j in targetCompetitions.length() - 1 downTo 0) {
                    if (targetCompetitions.optJSONObject(j)?.optString("code") == code) {
                        targetCompetitions.remove(j)
                    }
                }
                targetCompetitions.put(competition)
            }
        }

        val combinedEvaluations = target.optJSONArray("evaluationMatches") ?: JSONArray()
        val knownEvaluationIds = mutableSetOf<String>()
        for (i in 0 until combinedEvaluations.length()) {
            val row = combinedEvaluations.optJSONObject(i) ?: continue
            knownEvaluationIds.add(row.optString("leagueCode") + ":" + row.optString("id"))
        }
        val uefaEvaluations = uefa.optJSONArray("evaluationMatches")
        if (uefaEvaluations != null) {
            for (i in 0 until uefaEvaluations.length()) {
                val row = uefaEvaluations.optJSONObject(i) ?: continue
                val key = row.optString("leagueCode") + ":" + row.optString("id")
                if (key !in knownEvaluationIds) {
                    combinedEvaluations.put(row)
                    knownEvaluationIds.add(key)
                }
            }
        }
        target.put("evaluationMatches", combinedEvaluations)
        target.put("uefaEvaluationUpdatedAt", uefa.optString("updatedAt"))
    }

    /**
     * Resolve the UEFA payload for the background worker. A transient download
     * failure must not replace a previously complete merged cache with domestic
     * data only. Prefer a valid fresh response, then the dedicated UEFA cache,
     * then the UEFA subset of the previous merged cache.
     */
    fun parseUefaFeed(payload: String?): JSONObject? {
        if (payload.isNullOrBlank()) return null
        return try {
            JSONObject(payload).takeIf {
                it.optJSONArray("competitions") != null &&
                    it.optJSONArray("evaluationMatches") != null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveUefaFeed(
        downloaded: String?,
        cachedUefa: String?,
        cachedMerged: String?
    ): JSONObject? {
        parseUefaFeed(downloaded)?.let { return it }
        parseUefaFeed(cachedUefa)?.let { return it }

        val previous = try {
            cachedMerged?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        } ?: return null

        val competitions = JSONArray()
        val previousCompetitions = previous.optJSONArray("competitions")
        if (previousCompetitions != null) {
            for (i in 0 until previousCompetitions.length()) {
                val competition = previousCompetitions.optJSONObject(i) ?: continue
                if (competition.optString("code") in setOf("UCL", "UEL", "UECL")) {
                    competitions.put(competition)
                }
            }
        }

        val evaluations = JSONArray()
        val previousEvaluations = previous.optJSONArray("evaluationMatches")
        if (previousEvaluations != null) {
            for (i in 0 until previousEvaluations.length()) {
                val row = previousEvaluations.optJSONObject(i) ?: continue
                if (row.optString("leagueCode") in setOf("UCL", "UEL", "UECL")) {
                    evaluations.put(row)
                }
            }
        }

        if (competitions.length() == 0 && evaluations.length() == 0) return null
        return JSONObject()
            .put("updatedAt", previous.optString("uefaEvaluationUpdatedAt")
                .ifBlank { previous.optString("updatedAt") })
            .put("competitions", competitions)
            .put("evaluationMatches", evaluations)
    }
}
