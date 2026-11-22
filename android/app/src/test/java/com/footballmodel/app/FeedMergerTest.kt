package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FeedMergerTest {
    @Test fun replacesUefaCompetitionsAndPreservesDomesticCompetitions() {
        val target = JSONObject().put("competitions", JSONArray()
            .put(JSONObject().put("code", "PL").put("name", "Premier League"))
            .put(JSONObject().put("code", "UCL").put("name", "old UCL")))
        val uefa = JSONObject().put("competitions", JSONArray()
            .put(JSONObject().put("code", "UCL").put("name", "new UCL"))
            .put(JSONObject().put("code", "UEL").put("name", "Europa League"))
            .put(JSONObject().put("code", "OTHER").put("name", "ignored")))

        FeedMerger.mergeUefa(target, uefa)

        val competitions = target.getJSONArray("competitions")
        assertEquals(3, competitions.length())
        assertEquals("PL", competitions.getJSONObject(0).getString("code"))
        assertEquals("new UCL", competitions.getJSONObject(1).getString("name"))
        assertEquals("UEL", competitions.getJSONObject(2).getString("code"))
    }

    @Test fun mergesUefaEvaluationsWithoutDroppingDomesticAndDeduplicatesByLeagueAndId() {
        val domesticEvaluation = JSONObject().put("id", "42").put("leagueCode", "ENG")
        val oldUefaEvaluation = JSONObject().put("id", "42").put("leagueCode", "UCL")
        val newUefaEvaluation = JSONObject().put("id", "42").put("leagueCode", "UCL").put("status", "FINISHED")
        val target = JSONObject().put("evaluationMatches", JSONArray()
            .put(domesticEvaluation).put(oldUefaEvaluation))
        val uefa = JSONObject()
            .put("updatedAt", "2026-11-23T10:00:00Z")
            .put("evaluationMatches", JSONArray().put(newUefaEvaluation))

        FeedMerger.mergeUefa(target, uefa)

        val evaluations = target.getJSONArray("evaluationMatches")
        assertEquals(2, evaluations.length())
        assertEquals("ENG", evaluations.getJSONObject(0).getString("leagueCode"))
        assertEquals("UCL", evaluations.getJSONObject(1).getString("leagueCode"))
        assertEquals("2026-11-23T10:00:00Z", target.getString("uefaEvaluationUpdatedAt"))
    }

    @Test fun stillMergesEvaluationsWhenUefaCompetitionArrayIsMissing() {
        val target = JSONObject().put("competitions", JSONArray())
        val uefa = JSONObject().put("evaluationMatches", JSONArray()
            .put(JSONObject().put("id", "9").put("leagueCode", "UECL")))

        FeedMerger.mergeUefa(target, uefa)

        assertNotNull(target.optJSONArray("evaluationMatches"))
        assertEquals(1, target.getJSONArray("evaluationMatches").length())
    }
}
