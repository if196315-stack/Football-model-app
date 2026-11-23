package com.footballmodel.app

import java.util.Calendar
import java.util.Locale

internal data class AnalyticsBet(
    val id: String,
    val dayMillis: Long,
    val source: String,
    val status: String,
    val stake: Double,
    val odds: Double,
    val market: String,
    val league: String,
    val isCoupon: Boolean,
    val isTopSignal: Boolean = false,
    val marketKey: String = ""
)

internal data class AnalyticsPoint(val dayMillis: Long, val profit: Double, val turnover: Double)
internal data class PendingTopSummary(val count: Int, val stake: Double, val payout: Double) {
    val netProfit: Double get() = payout - stake
}
internal data class AnalyticsSlice(
    val count: Int,
    val wins: Int,
    val losses: Int,
    val pushes: Int,
    val pending: Int,
    val turnover: Double,
    val profit: Double,
    val averageOdds: Double,
    val averageStake: Double,
    val maxWin: Double,
    val maxLoss: Double,
    val points: List<AnalyticsPoint>
) {
    val settled: Int get() = wins + losses + pushes
    val decided: Int get() = wins + losses
    val roi: Double get() = if (turnover > 0.0) profit / turnover * 100.0 else 0.0
    val winRate: Double get() = if (decided > 0) wins * 100.0 / decided else 0.0
}

internal data class AnalyticsBreakdown(
    val label: String,
    val count: Int,
    val wins: Int,
    val losses: Int,
    val profit: Double,
    val turnover: Double,
    val averageOdds: Double = 0.0
) {
    val roi: Double get() = if (turnover > 0.0) profit / turnover * 100.0 else 0.0
}

internal data class BetAnalyticsReport(
    val overall: AnalyticsSlice,
    val singles: AnalyticsSlice,
    val manual: AnalyticsSlice,
    val ai: AnalyticsSlice,
    val byMarket: List<AnalyticsBreakdown>,
    val byLeague: List<AnalyticsBreakdown>,
    val byOdds: List<AnalyticsBreakdown>,
    val byWeekday: List<AnalyticsBreakdown>,
    val maxDrawdown: Double,
    val maxDrawdownPercent: Double,
    val currentStreak: String,
    val longestWinStreak: Int,
    val longestLossStreak: Int,
    val recordsRead: Int,
    val ignoredAuto: Int,
    val invalidRecords: Int,
    val duplicateRecords: Int
)

internal object ActiveMarketRules {
    private val activeKeys = setOf(
        "1x", "x2", "bttsYes", "over25",
        "homeOver05", "homeOver15", "awayOver05", "awayOver15"
    )

    fun contains(marketKey: String): Boolean = marketKey.trim() in activeKeys
}

internal object BetAnalyticsCalculator {
    fun calculate(records: List<AnalyticsBet>, from: Long, to: Long, source: String = "ALL"): BetAnalyticsReport {
        val ignoredAuto = records.count { it.source.equals("AI_AUTO", true) }
        // Statistics contain only real single bets from active markets. Exclude coupon records
        // before deduplication and validation so they cannot affect any metric or data-quality count.
        val inPeriod = records.filter {
            !it.isCoupon && !it.source.equals("AI_AUTO", true) &&
                it.dayMillis in from..to && sourceMatches(it, source)
        }
        val unique = LinkedHashMap<String, AnalyticsBet>()
        inPeriod.forEachIndexed { index, bet ->
            val key = bet.id.ifBlank { "${bet.dayMillis}|${bet.source}|${bet.status}|${bet.stake}|${bet.odds}|${bet.market}|${bet.league}|${bet.isCoupon}" }
            if (!unique.containsKey(key)) unique[key] = bet
        }
        val scoped = unique.values.toList()
        val duplicates = inPeriod.size - scoped.size
        val valid = scoped.filter { it.status in setOf("WON", "LOST", "PUSH", "PENDING") &&
            it.stake.isFinite() && it.stake > 0.0 &&
            it.odds.isFinite() && it.odds > 1.0 }
        val invalid = scoped.size - valid.size
        // The report covers only the eight currently active working markets.
        val singlesRecords = valid.filter { isWorkingMarket(it.marketKey) }
        val manualRecords = singlesRecords.filter { !it.source.equals("AI_MANUAL", true) }
        val aiRecords = singlesRecords.filter { it.source.equals("AI_MANUAL", true) }
        val overall = summarize(singlesRecords)
        val workingMarketRecords = singlesRecords.filter { it.status != "PENDING" }
        val marketLabels = linkedMapOf(
            "1x" to "1X",
            "x2" to "X2",
            "bttsYes" to "ОЗ — да",
            "over25" to "ТБ 2,5",
            "homeOver05" to "ИТБ хозяев 0,5",
            "homeOver15" to "ИТБ хозяев 1,5",
            "awayOver05" to "ИТБ гостей 0,5",
            "awayOver15" to "ИТБ гостей 1,5"
        )
        val market = marketLabels.map { (key, label) ->
            val result = summarize(workingMarketRecords.filter { it.marketKey == key })
            AnalyticsBreakdown(label, result.settled, result.wins, result.losses,
                result.profit, result.turnover, result.averageOdds)
        }
        val league = breakdown(singlesRecords.filter { it.status != "PENDING" }) {
            it.league.ifBlank { "Не указан" }
        }
        val odds = breakdown(singlesRecords.filter { it.status != "PENDING" }) {
            when {
                it.odds < 1.50 -> "<1.50"
                it.odds < 2.00 -> "1.50–1.99"
                it.odds < 3.00 -> "2.00–2.99"
                it.odds < 5.00 -> "3.00–4.99"
                else -> "5.00+"
            }
        }.sortedBy { oddsOrder(it.label) }
        val weekdays = breakdown(singlesRecords.filter { it.status != "PENDING" }) {
            val day = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
                .apply { timeInMillis = it.dayMillis }
                .get(Calendar.DAY_OF_WEEK)
            listOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")[day - 1]
        }.sortedBy { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").indexOf(it.label) }
        val streaks = streaks(singlesRecords)
        val drawdown = drawdown(overall.points)
        return BetAnalyticsReport(
            overall = overall,
            singles = overall,
            manual = summarize(manualRecords),
            ai = summarize(aiRecords),
            byMarket = market,
            byLeague = league,
            byOdds = odds,
            byWeekday = weekdays,
            maxDrawdown = drawdown.first,
            maxDrawdownPercent = drawdown.second,
            currentStreak = streaks.first,
            longestWinStreak = streaks.second,
            longestLossStreak = streaks.third,
            recordsRead = scoped.size,
            ignoredAuto = ignoredAuto,
            invalidRecords = invalid,
            duplicateRecords = duplicates
        )
    }

    private fun sourceMatches(bet: AnalyticsBet, source: String): Boolean = when (source) {
        // TOP contains only bets explicitly confirmed from the TOP signals section.
        // ALL contains every real saved market bet; AI_AUTO paper observations are
        // excluded before this filter is applied.
        "TOP" -> bet.source.equals("AI_MANUAL", true)
        else -> true
    }

    /**
     * Summarize only valid, unique, open TOP singles from active markets.
     * Count, gross payout and net profit deliberately share one eligibility rule.
     */
    fun summarizePendingTop(records: List<AnalyticsBet>, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): PendingTopSummary {
        val unique = LinkedHashMap<String, AnalyticsBet>()
        records.forEach { bet ->
            if (!bet.source.equals("AI_MANUAL", true) ||
                !bet.status.equals("PENDING", true) ||
                bet.isCoupon ||
                !ActiveMarketRules.contains(bet.marketKey) ||
                !bet.stake.isFinite() || bet.stake <= 0.0 ||
                !bet.odds.isFinite() || bet.odds <= 1.0 ||
                bet.dayMillis !in from..to) return@forEach
            val key = bet.id.ifBlank {
                "${bet.dayMillis}|${bet.source}|${bet.status}|${bet.stake}|${bet.odds}|${bet.market}|${bet.league}|${bet.isCoupon}"
            }
            if (!unique.containsKey(key)) unique[key] = bet
        }
        val valid = unique.values
        return PendingTopSummary(
            count = unique.size,
            stake = valid.sumOf { it.stake },
            payout = valid.sumOf { it.stake * it.odds }
        )
    }

    private fun summarize(records: List<AnalyticsBet>): AnalyticsSlice {
        var wins = 0; var losses = 0; var pushes = 0; var pending = 0
        var turnover = 0.0; var profit = 0.0; var oddsTotal = 0.0; var stakeTotal = 0.0
        var maxWin = 0.0; var maxLoss = 0.0
        val daily = sortedMapOf<Long, Pair<Double, Double>>()
        records.forEach { bet ->
            if (bet.status == "PENDING") { pending++; return@forEach }
            val delta = when (bet.status) {
                "WON" -> { wins++; bet.stake * (bet.odds - 1.0) }
                "LOST" -> { losses++; -bet.stake }
                "PUSH" -> { pushes++; 0.0 }
                else -> return@forEach
            }
            turnover += bet.stake
            profit += delta
            stakeTotal += bet.stake
            oddsTotal += bet.odds
            maxWin = maxOf(maxWin, delta)
            maxLoss = minOf(maxLoss, delta)
            val old = daily[bet.dayMillis] ?: (0.0 to 0.0)
            daily[bet.dayMillis] = (old.first + delta) to (old.second + bet.stake)
        }
        val settled = wins + losses + pushes
        return AnalyticsSlice(
            records.size, wins, losses, pushes, pending, turnover, profit,
            if (settled > 0) oddsTotal / settled else 0.0,
            if (settled > 0) stakeTotal / settled else 0.0,
            maxWin, maxLoss,
            daily.map { AnalyticsPoint(it.key, it.value.first, it.value.second) }
        )
    }

    private fun breakdown(records: List<AnalyticsBet>, key: (AnalyticsBet) -> String): List<AnalyticsBreakdown> {
        return records.groupBy(key).map { (label, group) ->
            val result = summarize(group)
            AnalyticsBreakdown(label, result.settled, result.wins, result.losses, result.profit, result.turnover, result.averageOdds)
        }.sortedWith(compareByDescending<AnalyticsBreakdown> { it.profit }.thenBy { it.label })
    }

    private fun drawdown(points: List<AnalyticsPoint>): Pair<Double, Double> {
        var equity = 0.0; var peak = 0.0; var maxDrop = 0.0; var maxPct = 0.0
        points.forEach {
            equity += it.profit
            peak = maxOf(peak, equity)
            val drop = peak - equity
            maxDrop = maxOf(maxDrop, drop)
            if (peak > 0.0) maxPct = maxOf(maxPct, drop / peak * 100.0)
        }
        return maxDrop to maxPct
    }

    private fun streaks(records: List<AnalyticsBet>): Triple<String, Int, Int> {
        val settled = records.filter { it.status == "WON" || it.status == "LOST" }
            .sortedBy { it.dayMillis }
        var type = ""; var run = 0; var bestWin = 0; var bestLoss = 0
        settled.forEach {
            val next = it.status
            if (next == type) run++ else { type = next; run = 1 }
            if (type == "WON") bestWin = maxOf(bestWin, run)
            if (type == "LOST") bestLoss = maxOf(bestLoss, run)
        }
        val current = if (run == 0) "—" else (if (type == "WON") "W" else "L") + run
        return Triple(current, bestWin, bestLoss)
    }

    private fun oddsOrder(label: String): Int = when (label) {
        "<1.50" -> 0; "1.50–1.99" -> 1; "2.00–2.99" -> 2; "3.00–4.99" -> 3; else -> 4
    }

    private fun isWorkingMarket(rawKey: String): Boolean = ActiveMarketRules.contains(rawKey)

    private fun AnalyticsBet.marketKeyFallback(): String = "Рынок не указан"
}
