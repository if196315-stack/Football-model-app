package com.footballmodel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopSignalRankingTest {
    @Test fun selectsHighestKellyRatherThanHighestRawProbability() {
        val candidates = listOf(
            0.62 to 4.0,
            0.80 to 1.60
        )

        assertEquals(0, TopSignalRanking.bestIndex(candidates))
    }

    @Test fun rejectsInvalidOddsAndProbabilities() {
        assertEquals(Double.NEGATIVE_INFINITY, TopSignalRanking.fullKellyFraction(0.7, 1.0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, TopSignalRanking.fullKellyFraction(Double.NaN, 2.0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, TopSignalRanking.fullKellyFraction(1.2, 2.0), 0.0)
        assertNull(TopSignalRanking.bestIndex(listOf(0.7 to 1.0, Double.NaN to 2.0)))
    }

    @Test fun preservesInputOrderForEqualScores() {
        assertEquals(0, TopSignalRanking.bestIndex(listOf(0.7 to 2.0, 0.7 to 2.0)))
    }
}
