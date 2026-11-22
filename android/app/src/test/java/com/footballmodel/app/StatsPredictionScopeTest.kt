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
}
