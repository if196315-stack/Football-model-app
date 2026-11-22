package com.footballmodel.app

/**
 * Keeps the model-accuracy summary aligned with the selected statistics category.
 * A TOP forecast is a market prediction explicitly saved from the TOP section.
 */
internal object StatsPredictionScope {
    fun key(matchId: String, marketKey: String): String =
        matchId.trim() + "|" + marketKey.trim()

    fun marketKey(side: String, home: String, away: String): String? {
        val value = side.trim()
        if (value == "1X") return "1x"
        if (value == "X2") return "x2"
        if (value.equals("ОЗ ДА", ignoreCase = true)) return "bttsYes"
        if (value.startsWith("ТБ ")) {
            val line = value.removePrefix("ТБ ").trim().toDoubleOrNull() ?: return null
            val tenths = (line * 10.0).toInt()
            if (kotlin.math.abs(line * 10.0 - tenths) > 0.001) return null
            return "over" + tenths.toString().padStart(2, '0')
        }
        if (value.startsWith("ИТБ ")) {
            val lineText = value.removePrefix("ИТБ ").substringBefore("·").trim()
            val line = lineText.toDoubleOrNull() ?: return null
            val tenths = (line * 10.0).toInt()
            if (kotlin.math.abs(line * 10.0 - tenths) > 0.001) return null
            val team = value.substringAfter("·", "").trim()
            val prefix = when {
                team.equals(home.trim(), ignoreCase = true) -> "homeOver"
                team.equals(away.trim(), ignoreCase = true) -> "awayOver"
                else -> return null
            }
            return prefix + tenths.toString().padStart(2, '0')
        }
        return null
    }

    fun confidenceBand(source: String, probability: Double): Int? {
        if (!probability.isFinite() || probability < 0.0 || probability > 1.0) return null
        val topOnly = source == "TOP"
        val confidence = maxOf(probability, 1.0 - probability)
        if (topOnly && confidence < 0.6) return null
        return when {
            confidence < 0.6 -> 0
            confidence < 0.7 -> if (topOnly) 0 else 1
            confidence < 0.8 -> if (topOnly) 1 else 2
            else -> if (topOnly) 2 else 3
        }
    }

    fun includes(source: String, matchId: String, marketKey: String, topSelections: Set<String>): Boolean {
        if (source != "TOP") return true
        if (matchId.isBlank() || marketKey.isBlank()) return false
        return key(matchId, marketKey) in topSelections
    }
}
