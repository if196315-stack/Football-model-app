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

}
