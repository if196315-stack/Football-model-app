package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the foreground feed refresh and background worker cache structurally
 * identical: UEFA competitions and immutable finished-match evaluations are
 * merged together, with evaluation IDs namespaced by league.
 */
internal object FeedMerger {
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
}
