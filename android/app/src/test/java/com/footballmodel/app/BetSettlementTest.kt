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
}
