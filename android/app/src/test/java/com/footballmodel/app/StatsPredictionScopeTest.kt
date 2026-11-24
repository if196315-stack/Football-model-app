package com.footballmodel.app

import org.junit.Assert.assertEquals
import java.time.Instant
import org.junit.Test

class StatsPredictionScopeTest {
    @Test fun matchDateParserUsesFixtureDateInUtcAndRejectsMissingDates() {
        val day = StatsPredictionScope.matchDateMillis("2026-11-23T18:45:00Z")
        assertEquals(Instant.parse("2026-11-23T18:45:00Z").toEpochMilli(), day)
        assertEquals(Instant.parse("2026-11-23T00:00:00Z").toEpochMilli(),
            StatsPredictionScope.matchDateMillis("2026-11-23"))
        assertEquals(null, StatsPredictionScope.matchDateMillis(""))
        assertEquals(null, StatsPredictionScope.matchDateMillis("not-a-date"))
    }

    @Test fun predictionPeriodUsesInclusiveFixtureDateBounds() {
        val from = Instant.parse("2026-11-17T00:00:00Z").toEpochMilli()
        val to = Instant.parse("2026-11-23T23:59:59Z").toEpochMilli()
        assertEquals(true, StatsPredictionScope.matchDateInPeriod("2026-11-17T00:15:00Z", from, to))
        assertEquals(true, StatsPredictionScope.matchDateInPeriod("2026-11-23T22:00:00Z", from, to))
        assertEquals(false, StatsPredictionScope.matchDateInPeriod("2026-11-16T23:59:59Z", from, to))
        assertEquals(false, StatsPredictionScope.matchDateInPeriod("", from, to))
    }

    @Test fun topSummaryIncludesOnlyMarketsSavedFromTop() {
        val top = setOf(
            StatsPredictionScope.key("match-1", "1x"),
            StatsPredictionScope.key("match-2", "over25")
        )

        assertEquals(true, StatsPredictionScope.includes("TOP", "match-1", "1x", top))
        assertEquals(true, StatsPredictionScope.includes("TOP", "match-2", "over25", top))
        assertEquals(false, StatsPredictionScope.includes("TOP", "match-1", "x2", top))
        assertEquals(false, StatsPredictionScope.includes("TOP", "match-3", "1x", top))
    }

    @Test fun allSummaryIncludesEveryEvaluatedPredictionIncludingTop() {
        val top = setOf(StatsPredictionScope.key("match-1", "1x"))

        assertEquals(true, StatsPredictionScope.includes("ALL", "match-1", "1x", top))
        assertEquals(true, StatsPredictionScope.includes("ALL", "match-2", "over25", top))
    }

    @Test fun topSummaryRejectsMissingMatchOrMarketIdentifiers() {
        assertEquals(false, StatsPredictionScope.includes("TOP", "", "1x", emptySet()))
        assertEquals(false, StatsPredictionScope.includes("TOP", "match-1", "", emptySet()))
    }
    @Test fun topArchiveMapsEverySupportedMarketToItsEvaluationKey() {
        assertEquals("1x", StatsPredictionScope.marketKey("1X", "Home", "Away"))
        assertEquals("x2", StatsPredictionScope.marketKey("X2", "Home", "Away"))
        assertEquals("bttsYes", StatsPredictionScope.marketKey("ОЗ ДА", "Home", "Away"))
        assertEquals("over25", StatsPredictionScope.marketKey("ТБ 2.5", "Home", "Away"))
        assertEquals("homeOver05", StatsPredictionScope.marketKey("ИТБ 0.5 · Home", "Home", "Away"))
        assertEquals("awayOver15", StatsPredictionScope.marketKey("ИТБ 1.5 · Away", "Home", "Away"))
    }

    @Test fun topArchiveRejectsUnknownTeamsAndUnsupportedLines() {
        assertEquals(null, StatsPredictionScope.marketKey("ИТБ 0.5 · Other", "Home", "Away"))
        assertEquals(null, StatsPredictionScope.marketKey("ТБ 1.25", "Home", "Away"))
        assertEquals(null, StatsPredictionScope.marketKey("unknown", "Home", "Away"))
    }

    @Test fun topConfidenceBandsStartAtSixtyPercent() {
        assertEquals(null, StatsPredictionScope.confidenceBand("TOP", 0.55))
        assertEquals(0, StatsPredictionScope.confidenceBand("TOP", 0.60))
        assertEquals(1, StatsPredictionScope.confidenceBand("TOP", 0.70))
        assertEquals(2, StatsPredictionScope.confidenceBand("TOP", 0.80))
    }

    @Test fun allConfidenceBandsRetainFiftyPercentRange() {
        assertEquals(0, StatsPredictionScope.confidenceBand("ALL", 0.50))
        assertEquals(1, StatsPredictionScope.confidenceBand("ALL", 0.60))
        assertEquals(2, StatsPredictionScope.confidenceBand("ALL", 0.70))
        assertEquals(3, StatsPredictionScope.confidenceBand("ALL", 0.80))
    }


    @Test fun statsFallbackIncludesFinishedCompetitionFixturesMissingFromEvaluationArchive() {
        val galatasaray = org.json.JSONObject()
            .put("id", "m_4SYMzr").put("utcDate", "2026-11-24T17:45:00Z")
            .put("home", "Galatasaray").put("away", "Aston Villa")
            .put("status", "FINISHED").put("homeScore", 1).put("awayScore", 0)
            .put("prediction", org.json.JSONObject().put("1x", 0.47).put("x2", 0.74))
        val bodo = org.json.JSONObject()
            .put("id", "m_0yKeRt").put("utcDate", "2026-11-24T17:45:00Z")
            .put("home", "Bodø/Glimt").put("away", "LASK")
            .put("status", "FINISHED").put("homeScore", 1).put("awayScore", 0)
            .put("prediction", org.json.JSONObject().put("1x", 0.65))
        val feed = org.json.JSONObject()
            .put("evaluationMatches", org.json.JSONArray())
            .put("competitions", org.json.JSONArray().put(
                org.json.JSONObject().put("code", "UCL")
                    .put("matches", org.json.JSONArray().put(galatasaray).put(bodo))))

        val resolved = StatsPredictionScope.evaluationMatchesForStats(feed)

        assertEquals(2, resolved.length())
        assertEquals("m_4SYMzr", resolved.getJSONObject(0).getString("id"))
        assertEquals("Galatasaray", resolved.getJSONObject(0).getString("home"))
        assertEquals("UCL", resolved.getJSONObject(0).getString("leagueCode"))
        assertEquals("m_0yKeRt", resolved.getJSONObject(1).getString("id"))
    }

    @Test fun statsFallbackDoesNotDuplicateArchivedMatchesOrReplaceTheirEvaluation() {
        val archived = org.json.JSONObject().put("id", "same-id").put("leagueCode", "UCL")
            .put("status", "FINISHED").put("homeScore", 2).put("awayScore", 1)
            .put("utcDate", "2026-11-24").put("prediction", org.json.JSONObject().put("1x", 0.8))
        val competitionMatch = org.json.JSONObject().put("id", "same-id")
            .put("status", "FINISHED").put("homeScore", 0).put("awayScore", 4)
            .put("utcDate", "2026-11-24").put("prediction", org.json.JSONObject().put("1x", 0.1))
        val feed = org.json.JSONObject()
            .put("evaluationMatches", org.json.JSONArray().put(archived))
            .put("competitions", org.json.JSONArray().put(
                org.json.JSONObject().put("code", "UCL")
                    .put("matches", org.json.JSONArray().put(competitionMatch))))

        val resolved = StatsPredictionScope.evaluationMatchesForStats(feed)

        assertEquals(1, resolved.length())
        assertEquals(2, resolved.getJSONObject(0).getInt("homeScore"))
        assertEquals(0.8, resolved.getJSONObject(0).getJSONObject("prediction").getDouble("1x"), 0.0001)
    }

    @Test fun statsFallbackRejectsUnfinishedOrIncompleteFixtures() {
        val prediction = org.json.JSONObject().put("1x", 0.7)
        val validBase = org.json.JSONObject().put("utcDate", "2026-11-24")
            .put("homeScore", 1).put("awayScore", 0).put("prediction", prediction)
        val fixtures = org.json.JSONArray()
            .put(org.json.JSONObject(validBase.toString()).put("id", "scheduled").put("status", "SCHEDULED"))
            .put(org.json.JSONObject(validBase.toString()).put("id", "no-away-score").put("status", "FINISHED").remove("awayScore"))
            .put(org.json.JSONObject(validBase.toString()).put("id", "no-prediction").put("status", "FINISHED").remove("prediction"))
            .put(org.json.JSONObject(validBase.toString()).put("id", "bad-date").put("status", "FINISHED").put("utcDate", "invalid"))
        val feed = org.json.JSONObject().put("competitions", org.json.JSONArray().put(
            org.json.JSONObject().put("code", "UCL").put("matches", fixtures)))

        assertEquals(0, StatsPredictionScope.evaluationMatchesForStats(feed).length())
    }

    @Test fun statsFallbackNamespacesFixtureIdsByCompetition() {
        fun fixture(league: String) = org.json.JSONObject().put("id", "reused-id")
            .put("utcDate", "2026-11-24").put("status", "FINISHED")
            .put("homeScore", 1).put("awayScore", 0)
            .put("prediction", org.json.JSONObject().put("1x", 0.6))
            .let { org.json.JSONObject().put("code", league).put("matches", org.json.JSONArray().put(it)) }
        val feed = org.json.JSONObject().put("competitions",
            org.json.JSONArray().put(fixture("UCL")).put(fixture("UEL")))

        assertEquals(2, StatsPredictionScope.evaluationMatchesForStats(feed).length())
    }

}
