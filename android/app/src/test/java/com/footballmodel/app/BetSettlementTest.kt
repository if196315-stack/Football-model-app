package com.footballmodel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BetSettlementTest {
    @Test fun apiSportFinalFlagControlsSettlement() {
        assertEquals(true, BetSettlement.isConfirmedFinal("FINISHED", true, true))
        assertEquals(false, BetSettlement.isConfirmedFinal("FINISHED", true, false))
        assertEquals(false, BetSettlement.isConfirmedFinal("FINISHED", true, null))
        assertEquals(false, BetSettlement.isConfirmedFinal("IN_PLAY", true, true))
        // Older feeds without the new field retain the previous finished-status behavior.
        assertEquals(true, BetSettlement.isConfirmedFinal("FT", false, null))
    }

    @Test fun doubleChanceAndMatchResultMarkets() {
        assertEquals("LOST", BetSettlement.result("x2", 3, 1))
        assertEquals("WON", BetSettlement.result("x2", 1, 1))
        assertEquals("WON", BetSettlement.result("1x", 0, 0))
        assertEquals("LOST", BetSettlement.result("1x", 0, 1))
        assertEquals("LOST", BetSettlement.result("12", 2, 2))
        assertEquals("WON", BetSettlement.result("1", 2, 0))
        assertEquals("WON", BetSettlement.result("x", 1, 1))
        assertEquals("WON", BetSettlement.result("2", 0, 1))
    }

    @Test fun bothTeamsToScore() {
        assertEquals("WON", BetSettlement.result("bttsYes", 1, 1))
        assertEquals("LOST", BetSettlement.result("bttsYes", 1, 0))
        assertEquals("WON", BetSettlement.result("bttsNo", 0, 2))
        assertEquals("LOST", BetSettlement.result("bttsNo", 1, 1))
    }

    @Test fun matchAndTeamTotalsIncludingHalfGoalLines() {
        assertEquals("WON", BetSettlement.result("over05", 0, 1))
        assertEquals("LOST", BetSettlement.result("over15", 1, 0))
        assertEquals("WON", BetSettlement.result("over25", 2, 1))
        assertEquals("WON", BetSettlement.result("under25", 1, 1))
        assertEquals("PUSH", BetSettlement.result("under20", 2, 0))
        assertEquals("WON", BetSettlement.result("homeOver05", 1, 0))
        assertEquals("LOST", BetSettlement.result("homeOver15", 1, 0))
        assertEquals("WON", BetSettlement.result("awayOver05", 1, 1))
        assertEquals("PUSH", BetSettlement.result("awayUnder10", 0, 1))
    }

    @Test fun handicapsAndUnknownMarketsAreSafe() {
        assertEquals("WON", BetSettlement.result("spread:home:-0.5", 1, 0))
        assertEquals("PUSH", BetSettlement.result("spread:home:-1.0", 1, 0))
        assertEquals("LOST", BetSettlement.result("spread:away:+1.0", 2, 0))
        assertNull(BetSettlement.result("spread:home:-0.25", 1, 0))
        assertNull(BetSettlement.result("some-unmapped-market", 3, 0))
        assertNull(BetSettlement.result("over15", -1, 2))
    }


    @Test fun sharedSettlementGateMovesOnlyConfirmedFinalPendingBets() {
        assertNull(BetSettlement.settledResult("PENDING", "FINISHED", true, false, 2, 1, "1x"))
        assertNull(BetSettlement.settledResult("PENDING", "IN_PLAY", true, true, 2, 1, "1x"))
        assertNull(BetSettlement.settledResult("WON", "FINISHED", true, true, 2, 1, "1x"))
        assertEquals("WON", BetSettlement.settledResult("PENDING", "FINISHED", true, true, 2, 1, "1x"))
        assertEquals("PUSH", BetSettlement.settledResult("PENDING", "FINISHED", true, true, 2, 0, "under20"))
        assertNull(BetSettlement.settledResult("PENDING", "FINISHED", true, true, null, 1, "1x"))
    }

    @Test fun staleSettledBetReopensOnlyForCurrentNonFinalFixture() {
        assertEquals(true, BetSettlement.shouldReopenSettledBet("LOST", "SCHEDULED", true))
        assertEquals(true, BetSettlement.shouldReopenSettledBet("WON", "IN_PLAY", false))
        assertEquals(true, BetSettlement.shouldReopenSettledBet("PUSH", "PAUSED", false))
        assertEquals(false, BetSettlement.shouldReopenSettledBet("LOST", "SCHEDULED", false))
        assertEquals(false, BetSettlement.shouldReopenSettledBet("LOST", "FINISHED", false))
        assertEquals(false, BetSettlement.shouldReopenSettledBet("PENDING", "SCHEDULED", true))
    }

    @Test fun onlyKnownFinalStatusesBelongToCompletedHistory() {
        assertEquals(true, BetSettlement.isSettledStatus("WON"))
        assertEquals(true, BetSettlement.isSettledStatus("LOST"))
        assertEquals(true, BetSettlement.isSettledStatus("PUSH"))
        assertEquals(false, BetSettlement.isSettledStatus("PENDING"))
        assertEquals(false, BetSettlement.isSettledStatus("UNKNOWN"))
    }
    @Test fun scheduledFixtureReopensSettlementDuringKickoffGraceWindow() {
        val now = 1_795_550_000_000L
        val kickoff = now - 4 * 60 * 1000L
        assertEquals(true, BetSettlement.isKickoffWithinReopenWindow(kickoff, now))
        assertEquals(true, BetSettlement.shouldReopenSettledBet(
            "WON", "SCHEDULED", false, true
        ))
        assertEquals(false, BetSettlement.isKickoffWithinReopenWindow(
            now - 7 * 60 * 60 * 1000L, now
        ))
        assertEquals(false, BetSettlement.isKickoffWithinReopenWindow(null, now))
        assertEquals(false, BetSettlement.shouldReopenSettledBet(
            "WON", "SCHEDULED", false, false
        ))
    }

}
