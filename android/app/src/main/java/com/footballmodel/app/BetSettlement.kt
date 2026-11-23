package com.footballmodel.app

/**
 * Single source of truth for paper-bet settlement, shared by the foreground UI
 * and background worker. Returns null for unsupported/ambiguous market keys so
 * the bet remains pending rather than being recorded as a false loss or push.
 */
internal object BetSettlement {
    /**
     * API-Sport scoreStatus.final is authoritative when the field is present.
     * Missing field keeps legacy feeds compatible; explicit false/null never settles.
     */
    fun isConfirmedFinal(status: String, scoreStatusFinalPresent: Boolean,
                         scoreStatusFinal: Boolean?): Boolean {
        val finished = status.trim().uppercase() in setOf(
            "FINISHED", "FT", "AET", "PEN", "AFTER EXTRA TIME",
            "AFTER PENALTIES", "MATCH FINISHED"
        )
        if (!finished) return false
        return if (scoreStatusFinalPresent) scoreStatusFinal == true else true
    }

    fun isSettledStatus(status: String): Boolean = status in setOf("WON", "LOST", "PUSH")

    fun settledResult(
        currentStatus: String,
        matchStatus: String,
        scoreStatusFinalPresent: Boolean,
        scoreStatusFinal: Boolean?,
        homeGoals: Int?,
        awayGoals: Int?,
        marketKey: String
    ): String? {
        if (currentStatus != "PENDING" || homeGoals == null || awayGoals == null ||
            !isConfirmedFinal(matchStatus, scoreStatusFinalPresent, scoreStatusFinal)) return null
        return result(marketKey, homeGoals, awayGoals)
    }

    fun result(marketKey: String, homeGoals: Int, awayGoals: Int): String? {
        if (homeGoals < 0 || awayGoals < 0) return null
        val total = homeGoals + awayGoals
        val direct = when (marketKey) {
            "1x" -> homeGoals >= awayGoals
            "x2" -> awayGoals >= homeGoals
            "12" -> homeGoals != awayGoals
            "1" -> homeGoals > awayGoals
            "x" -> homeGoals == awayGoals
            "2" -> awayGoals > homeGoals
            "bttsYes" -> homeGoals > 0 && awayGoals > 0
            "bttsNo" -> homeGoals == 0 || awayGoals == 0
            else -> null
        }
        if (direct != null) return if (direct) "WON" else "LOST"

        val totalMarket = Regex("^(over|under)(\\d{2,})$").matchEntire(marketKey)
        if (totalMarket != null) {
            val line = totalMarket.groupValues[2].toIntOrNull()?.div(10.0) ?: return null
            return compare(total.toDouble(), line, totalMarket.groupValues[1] == "over")
        }

        val teamMarket = Regex("^(home|away)(Over|Under)(\\d{2,})$").matchEntire(marketKey)
        if (teamMarket != null) {
            val line = teamMarket.groupValues[3].toIntOrNull()?.div(10.0) ?: return null
            val goals = if (teamMarket.groupValues[1] == "home") homeGoals else awayGoals
            return compare(goals.toDouble(), line, teamMarket.groupValues[2] == "Over")
        }

        val spread = Regex("^spread:(home|away):([+-]?\\d+(?:\\.\\d+)?)$").matchEntire(marketKey)
        if (spread != null) {
            val handicap = spread.groupValues[2].toDoubleOrNull() ?: return null
            // Quarter-goal handicaps require split-stake settlement, which this
            // history model cannot represent safely. Leave them pending.
            val quarterRemainder = kotlin.math.abs(handicap * 4.0 - kotlin.math.round(handicap * 4.0))
            val halfLineRemainder = kotlin.math.abs(handicap * 2.0 - kotlin.math.round(handicap * 2.0))
            if (quarterRemainder > 0.000001 || halfLineRemainder > 0.000001) return null
            val adjusted = if (spread.groupValues[1] == "home")
                homeGoals + handicap - awayGoals
            else awayGoals + handicap - homeGoals
            return when {
                adjusted > 0.000001 -> "WON"
                adjusted < -0.000001 -> "LOST"
                else -> "PUSH"
            }
        }
        return null
    }

    private fun compare(actual: Double, line: Double, over: Boolean): String =
        when {
            over && actual > line || !over && actual < line -> "WON"
            over && actual < line || !over && actual > line -> "LOST"
            else -> "PUSH"
        }
}
