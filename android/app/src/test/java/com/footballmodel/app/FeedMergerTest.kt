package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FeedMergerTest {
    @Test fun everyLeagueRequiresOnlyOneWorkingMarketForScheduledMatches() {
        for (code in listOf("PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL", "UECL")) {
            assertEquals(false, FeedMerger.shouldShowScheduledMatch(code, true, false))
            assertEquals(true, FeedMerger.shouldShowScheduledMatch(code, true, true))
            assertEquals(false, FeedMerger.shouldShowScheduledMatch(code, false, true))
        }
    }

    @Test fun domesticMatchesRequirePredictionAndAtLeastOneActiveMelBetLine() {
        assertEquals(false, FeedMerger.shouldShowScheduledMatch("PL", true, false))
        assertEquals(false, FeedMerger.shouldShowScheduledMatch("PL", false, true))
        assertEquals(true, FeedMerger.shouldShowScheduledMatch("PL", true, true))
        assertEquals(false, FeedMerger.shouldShowScheduledMatch("PL", false, false))
    }

    @Test fun domesticAndUefaUseTheSameLineRetentionRules() {
        for (code in listOf("PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL", "UECL")) {
            assertEquals(false, FeedMerger.shouldKeepInLine("SCHEDULED", false, false))
            assertEquals(true, FeedMerger.shouldKeepInLine("SCHEDULED", false, true))
            assertEquals(true, FeedMerger.shouldKeepInLine("IN_PLAY_SECOND_HALF", false, false))
            assertEquals(true, FeedMerger.shouldKeepInLine("PAUSED", false, false))
            assertEquals(true, FeedMerger.shouldKeepInLine("SCHEDULED", true, false))
            assertEquals(false, FeedMerger.shouldKeepInLine("FINISHED", true, true))
        }
    }

    @Test fun recognizesProviderLiveAliasesAndTerminalStatusesConsistently() {
        for (status in listOf("IN_PLAY", "LIVE", "IN_PROGRESS", "1H", "2H",
            "FIRST_HALF", "SECOND_HALF", "IN_PLAY_SECOND_HALF", "PAUSED", "HALFTIME")) {
            assertEquals(true, FeedMerger.isLiveStatus(status))
        }
        for (status in listOf("FINISHED", "FT", "FULL_TIME", "AET", "PEN",
            "CANCELED", "CANCELLED", "AWARDED", "ABANDONED")) {
            assertEquals(true, FeedMerger.isTerminalStatus(status))
            assertEquals(false, FeedMerger.shouldKeepInLine(status, true, true))
        }
    }

    @Test fun resolvesLiveAndHalfTimeAliasesAcrossProviderFields() {
        for (status in listOf("HT", "HALF-TIME", "HALFTIME", "BREAK", "1H", "2H",
            "1ST_HALF", "2ND_HALF", "IN_PLAY_FIRST_HALF", "IN_PLAY_SECOND_HALF",
            "IN PROGRESS", "IN-PLAY", "EXTRA_TIME")) {
            assertEquals(true, FeedMerger.isLiveStatus(status))
        }
        assertEquals("HT", FeedMerger.resolveStatus("SCHEDULED", "HT", "Half Time"))
        assertEquals("IN_PLAY", FeedMerger.resolveStatus("SCHEDULED", "IN_PLAY", "Live"))
        assertEquals("FINISHED", FeedMerger.resolveStatus("LIVE", "FT", "Full Time"))
        assertEquals("Ⅱ ПЕРЕРЫВ", FeedMerger.displayStatusLabel("HT"))
        assertEquals("● LIVE", FeedMerger.displayStatusLabel("2H"))
        assertEquals("ЕЩЁ НЕ НАЧАЛСЯ", FeedMerger.displayStatusLabel("NOT_STARTED"))
    }

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

    @Test fun resolvesDownloadedUefaBeforeCachedSnapshots() {
        val downloaded = JSONObject().put("updatedAt", "fresh")
            .put("competitions", JSONArray().put(JSONObject().put("code", "UCL").put("name", "fresh")))
            .put("evaluationMatches", JSONArray())
        val cached = JSONObject().put("updatedAt", "old")
            .put("competitions", JSONArray().put(JSONObject().put("code", "UCL").put("name", "old")))
            .put("evaluationMatches", JSONArray())
        val resolved = FeedMerger.resolveUefaFeed(downloaded.toString(), cached.toString(), null)
        assertEquals("fresh", resolved?.getJSONArray("competitions")?.getJSONObject(0)?.getString("name"))
    }

    @Test fun fallsBackToDedicatedUefaCacheWhenDownloadIsUnavailableOrMalformed() {
        val cached = JSONObject().put("updatedAt", "cached")
            .put("competitions", JSONArray().put(JSONObject().put("code", "UECL")))
            .put("evaluationMatches", JSONArray())
        val unavailable = FeedMerger.resolveUefaFeed(null, cached.toString(), null)
        val malformed = FeedMerger.resolveUefaFeed("{not-json", cached.toString(), null)
        assertEquals("UECL", unavailable?.getJSONArray("competitions")?.getJSONObject(0)?.getString("code"))
        assertEquals("UECL", malformed?.getJSONArray("competitions")?.getJSONObject(0)?.getString("code"))
    }

    @Test fun fallsBackToOnlyUefaRowsFromPreviousMergedCache() {
        val previous = JSONObject()
            .put("updatedAt", "domestic-time")
            .put("uefaEvaluationUpdatedAt", "uefa-time")
            .put("competitions", JSONArray()
                .put(JSONObject().put("code", "PL"))
                .put(JSONObject().put("code", "UCL"))
                .put(JSONObject().put("code", "UECL")))
            .put("evaluationMatches", JSONArray()
                .put(JSONObject().put("id", "1").put("leagueCode", "ENG"))
                .put(JSONObject().put("id", "2").put("leagueCode", "UCL")))
        val resolved = FeedMerger.resolveUefaFeed(null, null, previous.toString())
        assertEquals("uefa-time", resolved?.getString("updatedAt"))
        assertEquals(2, resolved?.getJSONArray("competitions")?.length())
        assertEquals("UCL", resolved?.getJSONArray("evaluationMatches")?.getJSONObject(0)?.getString("leagueCode"))
        assertEquals(1, resolved?.getJSONArray("evaluationMatches")?.length())
    }

    @Test fun rejectsPayloadWithoutValidUefaFeedArrays() {
        assertEquals(null, FeedMerger.resolveUefaFeed("{\"error\":\"temporary\"}", null, null))
        assertEquals(null, FeedMerger.resolveUefaFeed("{\"competitions\":null}", null, null))
        assertEquals(null, FeedMerger.resolveUefaFeed("{\"competitions\":[]}", null, null))
    }
}
