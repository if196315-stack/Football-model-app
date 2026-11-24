package com.footballmodel.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchKickoffOrderingTest {
    @Test
    fun prefersExactUtcDateTimeOverDateOnlyEpoch() {
        val match = JSONObject()
            .put("startTimestamp", 1795545600L)
            .put("utcDate", "2026-11-24T18:45:00Z")

        assertEquals(1795545900000L, MatchKickoffOrdering.timestamp(match))
    }

    @Test
    fun convertsSecondBasedEpochToMilliseconds() {
        val match = JSONObject().put("startTimestamp", 1795545900L)

        assertEquals(1795545900000L, MatchKickoffOrdering.timestamp(match))
    }

    @Test
    fun sortsEarlierKickoffFirstWithinSameDate() {
        val later = JSONObject().put("utcDate", "2026-11-24T20:30:00Z")
        val earlier = JSONObject().put("utcDate", "2026-11-24T17:15:00Z")

        val sorted = listOf(later, earlier).sortedWith(MatchKickoffOrdering.comparator())

        assertTrue(sorted.first() === earlier)
        assertTrue(sorted.last() === later)
    }

    @Test
    fun supportsPaperBetKickoffField() {
        val bet = JSONObject().put("kickoff", "2026-11-24T17:15:00Z")

        assertEquals(1795540500000L, MatchKickoffOrdering.timestamp(bet))
    }
}
