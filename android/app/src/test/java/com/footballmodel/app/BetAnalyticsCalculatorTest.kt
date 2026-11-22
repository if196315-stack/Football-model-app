package com.footballmodel.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BetAnalyticsCalculatorTest {
    private fun bet(
        id: String,
        day: Long = 1_000L,
        source: String = "MANUAL",
        status: String = "WON",
        stake: Double = 10.0,
        odds: Double = 2.0,
        coupon: Boolean = false,
        topSignal: Boolean = false
    ) = AnalyticsBet(id, day, source, status, stake, odds, "Over 2.5", "League A", coupon, topSignal)

    @Test fun overallAndMarketStatsIncludeManualAndAiManualSinglesOnce() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            AnalyticsBet("manual-market", 1_000L, "MANUAL", "WON", 20.0, 2.0,
                "1X", "League A", false, marketKey = "1x"),
            AnalyticsBet("ai-market", 1_000L, "AI_MANUAL", "LOST", 10.0, 3.0,
                "1X", "League A", false, marketKey = "1x"),
            AnalyticsBet("paper-auto", 1_000L, "AI_AUTO", "WON", 100.0, 5.0,
                "1X", "League A", false, marketKey = "1x")
        ), 0L, 10_000L)
        assertEquals(2, report.overall.settled)
        assertEquals(30.0, report.overall.turnover, 0.0001)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(8, report.byMarket.size)
        val oneX = report.byMarket.first { it.label == "1X" }
        assertEquals(2, oneX.count)
        assertEquals(10.0, oneX.profit, 0.0001)
        assertEquals(2.5, oneX.averageOdds, 0.0001)
    }

    @Test fun overallStatsIncludeAllManualAndAiManualSingles() {
        val rows = listOf(
            bet("legacy-top", source = "MANUAL", topSignal = true, status = "WON"),
            bet("new-top", source = "AI_MANUAL", status = "LOST"),
            bet("unrelated-manual", source = "MANUAL", status = "WON"),
            bet("coupon", source = "MANUAL", status = "WON", stake = 5.0, odds = 3.0, coupon = true)
        )
        val report = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L)
        assertEquals(3, report.overall.settled)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(30.0, report.overall.turnover, 0.0001)
    }

    @Test fun pendingTopBetDoesNotAffectProfitTurnoverOrWinRate() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("w", source = "AI_MANUAL", status = "WON"),
            bet("p", source = "AI_MANUAL", status = "PENDING", stake = 100.0, odds = 8.0)
        ), 0L, 10_000L)
        assertEquals(1, report.overall.pending)
        assertEquals(10.0, report.overall.turnover, 0.0001)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(100.0, report.overall.winRate, 0.0001)
    }

    @Test fun autoPaperRecordsAreIgnored() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("auto", source = "AI_AUTO", status = "WON"),
            bet("manual-top", source = "MANUAL", topSignal = true, status = "LOST")
        ), 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(-10.0, report.overall.profit, 0.0001)
        assertEquals(1, report.ignoredAuto)
    }

    @Test fun duplicateTopIdsAreCountedOnlyOnce() {
        val same = bet("same", source = "AI_MANUAL", status = "WON")
        val report = BetAnalyticsCalculator.calculate(listOf(same, same), 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(1, report.duplicateRecords)
        assertEquals(1, report.recordsRead)
    }

    @Test fun pushIsTurnoverButNotWinOrLoss() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("push", source = "AI_MANUAL", status = "PUSH", stake = 25.0, odds = 1.9)
        ), 0L, 10_000L)
        assertEquals(1, report.overall.pushes)
        assertEquals(25.0, report.overall.turnover, 0.0001)
        assertEquals(0.0, report.overall.profit, 0.0001)
        assertEquals(0.0, report.overall.winRate, 0.0001)
    }

    @Test fun invalidTopStakeAndOddsAreExcluded() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("bad-stake", source = "AI_MANUAL", stake = 0.0),
            bet("bad-odds", source = "AI_MANUAL", odds = 1.0),
            bet("good", source = "AI_MANUAL")
        ), 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(2, report.invalidRecords)
    }
    @Test fun topFilterIncludesOnlyBetsConfirmedFromTopSection() {
        val rows = listOf(
            bet("other-market", source = "MANUAL", status = "WON"),
            bet("top-confirmed", source = "AI_MANUAL", status = "LOST"),
            bet("automatic-paper", source = "AI_AUTO", status = "WON")
        )
        val report = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "TOP")
        assertEquals(1, report.overall.settled)
        assertEquals(-10.0, report.overall.profit, 0.0001)
        assertEquals(10.0, report.overall.turnover, 0.0001)
        assertEquals(1, report.ignoredAuto)
    }

    @Test fun allFilterIncludesTopAndOtherWorkingMarketBetsButExcludesAutomaticPaper() {
        val rows = listOf(
            bet("other-market-1", source = "MANUAL", status = "WON"),
            bet("top-confirmed", source = "AI_MANUAL", status = "LOST"),
            bet("other-market-2", source = "MANUAL", status = "WON", stake = 20.0),
            bet("automatic-paper", source = "AI_AUTO", status = "WON")
        )
        val all = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "ALL")
        val top = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "TOP")
        assertEquals(3, all.overall.settled)
        assertEquals(2, top.overall.settled)
        assertEquals(40.0, all.overall.turnover, 0.0001)
        assertEquals(20.0, top.overall.turnover, 0.0001)
        assertEquals(1, all.ignoredAuto)
    }
}
