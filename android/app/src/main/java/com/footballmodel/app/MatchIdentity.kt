package com.footballmodel.app

import org.json.JSONObject

/** A fixture ID alone is never sufficient to settle or display a paper bet. */
internal object MatchIdentity {
    fun normalizedTeam(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

    fun matchDate(match: JSONObject): String =
        match.optString("utcDate").take(10).ifBlank {
            match.optString("dateEvent").take(10).ifBlank {
                match.optString("kickoff").take(10).ifBlank { match.optString("date").take(10) }
            }
        }

    fun betDate(bet: JSONObject): String =
        bet.optString("date").take(10).ifBlank {
            bet.optString("kickoff").take(10).ifBlank { bet.optString("utcDate").take(10) }
        }

    fun sameFixture(bet: JSONObject, match: JSONObject): Boolean {
        val home = normalizedTeam(bet.optString("home"))
        val away = normalizedTeam(bet.optString("away"))
        if (home.isBlank() || away.isBlank()) return false
        if (home != normalizedTeam(match.optString("home")) ||
            away != normalizedTeam(match.optString("away"))) return false
        val expectedDate = betDate(bet)
        val actualDate = matchDate(match)
        // When the bet has a date, require an explicit matching date from the feed.
        return expectedDate.isBlank() || (actualDate.isNotBlank() && expectedDate == actualDate)
    }
}
