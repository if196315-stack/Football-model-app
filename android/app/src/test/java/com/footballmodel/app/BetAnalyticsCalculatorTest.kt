package com.footballmodel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BetAnalyticsCalculatorTest {
    private fun bet(
        id: String,
        day: Long = 1_000L,
        source: String = "MANUAL",
        status: String = "WON",
        stake: Double = 10.0,
        odds: Double = 2.0,
        coupon: Boolean = false
    ) = AnalyticsBet(id, day, source, status, stake, odds, "Over 2.5", "League A", coupon)

    @Test fun combinesManualAiAndCouponOnceButKeepsCouponSeparate() {
        val rows = listOf(
            bet("m1", status = "WON"),
            bet("a1", source = "AI_MANUAL", status = "LOST"),
            bet("c1", status = "WON", stake = 5.0, odds = 3.0, coupon = true)
        )
        val report = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L)
        assertEquals(3, report.overall.settled)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(2, report.singles.settled)
        assertEquals(1, report.coupons.settled)
        assertEquals(10.0, report.manual.profit, 0.0001)
        assertEquals(-10.0, report.ai.profit, 0.0001)
    }

    @Test fun pendingDoesNotAffectProfitTurnoverOrWinRate() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("w", status = "WON"),
            bet("p", status = "PENDING", stake = 100.0, odds = 8.0)
        ), 0L, 10_000L)
        assertEquals(1, report.overall.pending)
        assertEquals(10.0, report.overall.turnover, 0.0001)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(100.0, report.overall.winRate, 0.0001)
    }

    @Test fun autoPaperRecordsAreIgnored() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("auto", source = "AI_AUTO", status = "WON"),
            bet("manual", status = "LOST")
        ), 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(-10.0, report.overall.profit, 0.0001)
        assertEquals(1, report.ignoredAuto)
        assertFalse(report.ai.settled > 0)
    }

    @Test fun duplicateIdsAreCountedOnlyOnce() {
        val same = bet("same", status = "WON")
        val report = BetAnalyticsCalculator.calculate(listOf(same, same), 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(1, report.duplicateRecords)
        assertEquals(1, report.recordsRead)
    }

    @Test fun pushIsTurnoverButNotWinOrLoss() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("push", status = "PUSH", stake = 25.0, odds = 1.9)
        ), 0L, 10_000L)
        assertEquals(1, report.overall.pushes)
        assertEquals(25.0, report.overall.turnover, 0.0001)
        assertEquals(0.0, report.overall.profit, 0.0001)
        assertEquals(0.0, report.overall.winRate, 0.0001)
    }

    @Test fun invalidStakeAndOddsAreExcluded() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("bad-stake", stake = 0.0),
            bet("bad-odds", odds = 1.0),
            bet("good")
        ), 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(2, report.invalidRecords)
    }
}
