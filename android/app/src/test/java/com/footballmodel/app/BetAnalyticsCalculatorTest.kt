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
        topSignal: Boolean = false,
        marketKey: String = if (coupon) "COUPON" else "over25"
    ) = AnalyticsBet(id, day, source, status, stake, odds, "Over 2.5", "League A", coupon, topSignal, marketKey)

    @Test fun reportPeriodIncludesOnlyRecordsWhoseFixtureDateIsWithinBounds() {
        val report = BetAnalyticsCalculator.calculate(listOf(
            bet("before", day = 999L, status = "WON"),
            bet("start", day = 1_000L, status = "WON"),
            bet("inside", day = 5_000L, status = "LOST"),
            bet("end", day = 10_000L, status = "WON"),
            bet("after", day = 10_001L, status = "WON")
        ), 1_000L, 10_000L, source = "ALL")
        assertEquals(3, report.overall.settled)
        assertEquals(30.0, report.overall.turnover, 0.0001)
        assertEquals(10.0, report.overall.profit, 0.0001)
    }


    @Test fun topSourceIncludesAiManualAndImportedUefaPaperButNotOrdinaryManualBets() {
        val rows = listOf(
            bet("top-one", source = "AI_MANUAL", marketKey = "1x"),
            bet("paper-one", source = "UEFA_PAPER", marketKey = "1x"),
            bet("paper-two", source = "UEFA_PAPER", marketKey = "x2"),
            bet("ordinary-manual", source = "MANUAL", marketKey = "1x")
        )
        val all = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "ALL")
        val top = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "TOP")
        assertEquals(4, all.overall.settled)
        assertEquals(3, top.overall.settled)
    }

    @Test fun activeMarketRulesMatchTheEightMarketsShownInTheApp() {
        listOf("1x", "x2", "bttsYes", "over25", "homeOver05", "homeOver15", "awayOver05", "awayOver15")
            .forEach { assertEquals(true, ActiveMarketRules.contains(it)) }
        listOf("over15", "bttsNo", "over35", "spread:home:-0.5", "COUPON")
            .forEach { assertEquals(false, ActiveMarketRules.contains(it)) }
    }

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
            bet("unrelated-manual", source = "MANUAL", status = "WON")
        )
        val report = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L)
        assertEquals(3, report.overall.settled)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(30.0, report.overall.turnover, 0.0001)
    }

    @Test fun couponRecordsAreCompletelyExcludedFromStatistics() {
        val rows = listOf(
            bet("single", source = "MANUAL", status = "WON", stake = 10.0, odds = 2.0),
            bet("coupon", source = "MANUAL", status = "WON", stake = 500.0, odds = 20.0, coupon = true)
        )
        val report = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L)
        assertEquals(1, report.overall.settled)
        assertEquals(10.0, report.overall.turnover, 0.0001)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(1, report.recordsRead)
        assertEquals(0, report.invalidRecords)
        assertEquals(0, report.duplicateRecords)
    }

    @Test fun pendingTopSummaryUsesOneEligibilityRuleForCountAndPayout() {
        val rows = listOf(
            bet("valid-1", source = "AI_MANUAL", status = "PENDING", stake = 25.0, odds = 2.0),
            bet("valid-2", source = "AI_MANUAL", status = "PENDING", stake = 10.0, odds = 1.5),
            bet("valid-1", source = "AI_MANUAL", status = "PENDING", stake = 25.0, odds = 2.0),
            bet("bad-stake", source = "AI_MANUAL", status = "PENDING", stake = 0.0, odds = 2.0),
            bet("bad-odds", source = "AI_MANUAL", status = "PENDING", stake = 10.0, odds = 1.0),
            bet("coupon", source = "AI_MANUAL", status = "PENDING", stake = 100.0, odds = 5.0, coupon = true),
            bet("not-top", source = "MANUAL", status = "PENDING", stake = 100.0, odds = 5.0),
            bet("retired-market", source = "AI_MANUAL", status = "PENDING", stake = 100.0, odds = 5.0, marketKey = "over15")
        )
        val summary = BetAnalyticsCalculator.summarizePendingTop(rows)
        assertEquals(2, summary.count)
        assertEquals(35.0, summary.stake, 0.0001)
        assertEquals(65.0, summary.payout, 0.0001)
        assertEquals(30.0, summary.netProfit, 0.0001)
        val periodSummary = BetAnalyticsCalculator.summarizePendingTop(
            rows + bet("outside-period", day = 20_000L, source = "AI_MANUAL", status = "PENDING", stake = 100.0, odds = 4.0),
            from = 0L, to = 10_000L
        )
        assertEquals(2, periodSummary.count)
        assertEquals(65.0, periodSummary.payout, 0.0001)
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
        assertEquals(1, top.overall.settled)
        assertEquals(40.0, all.overall.turnover, 0.0001)
        assertEquals(10.0, top.overall.turnover, 0.0001)
        assertEquals(1, all.ignoredAuto)
    } 
    @Test fun allAndTopReportsCalculateAverageOddsAndRoiForEachWorkingMarket() {
        val rows = listOf(
            AnalyticsBet("all-1x-win", 1_000L, "MANUAL", "WON", 20.0, 2.0,
                "1X", "League A", false, marketKey = "1x"),
            AnalyticsBet("top-1x-loss", 1_000L, "AI_MANUAL", "LOST", 10.0, 3.0,
                "1X", "League A", false, marketKey = "1x"),
            AnalyticsBet("all-x2-win", 1_000L, "MANUAL", "WON", 10.0, 1.5,
                "X2", "League A", false, marketKey = "x2")
        )
        val all = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "ALL")
        val top = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "TOP")
        assertEquals(3, all.overall.settled)
        assertEquals(15.0, all.overall.profit, 0.0001)
        assertEquals(37.5, all.overall.roi, 0.0001)
        assertEquals(2.1666667, all.overall.averageOdds, 0.0001)
        val allOneX = all.byMarket.first { it.label == "1X" }
        assertEquals(2.5, allOneX.averageOdds, 0.0001)
        assertEquals(33.333333, allOneX.roi, 0.0001)
        val topOneX = top.byMarket.first { it.label == "1X" }
        assertEquals(3.0, topOneX.averageOdds, 0.0001)
        assertEquals(-100.0, topOneX.roi, 0.0001)
        assertEquals(0, top.byMarket.first { it.label == "X2" }.count)
    }
 
    @Test fun topStatsIncludeAllFourteenSettledTopMatchesAcrossMarkets() {
        val rows = (1..10).map { bet("top-active-$it", source = "UEFA_PAPER", marketKey = "awayOver05") } +
            (1..4).map { bet("top-legacy-market-$it", source = "UEFA_PAPER", marketKey = "over15") }
        val top = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "TOP")
        val all = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "ALL")
        assertEquals(14, top.overall.settled)
        assertEquals(14, top.overall.count)
        assertEquals(140.0, top.overall.turnover, 0.0001)
        // ALL intentionally retains the current eight-market working filter.
        assertEquals(10, all.overall.settled)
    }

    @Test fun reportsExcludeRetiredMarketsFromOverallWorkingMarketStatistics() {
        val rows = listOf(
            bet("active-market", source = "MANUAL", status = "WON", marketKey = "over25"),
            bet("retired-market", source = "MANUAL", status = "WON", stake = 100.0, marketKey = "over15")
        )
        val report = BetAnalyticsCalculator.calculate(rows, 0L, 10_000L, source = "ALL")
        assertEquals(1, report.overall.settled)
        assertEquals(10.0, report.overall.profit, 0.0001)
        assertEquals(10.0, report.overall.turnover, 0.0001)
    }

}
