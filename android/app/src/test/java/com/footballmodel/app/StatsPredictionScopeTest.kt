package com.footballmodel.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsPredictionScopeTest {
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

}
