package com.footballmodel.app

/**
 * Keeps the model-accuracy summary aligned with the selected statistics category.
 * A TOP forecast is a market prediction explicitly saved from the TOP section.
 */
internal object StatsPredictionScope {
    fun key(matchId: String, marketKey: String): String =
        matchId.trim() + "|" + marketKey.trim()

    fun includes(source: String, matchId: String, marketKey: String, topSelections: Set<String>): Boolean {
        if (source != "TOP") return true
        if (matchId.isBlank() || marketKey.isBlank()) return false
        return key(matchId, marketKey) in topSelections
    }
}
