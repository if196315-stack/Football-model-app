package com.footballmodel.app

import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    fun uniqueMatch(matches: List<JSONObject>?): JSONObject? = matches?.singleOrNull()

    private fun dateMillis(value: String): Long? {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = parser.parse(value, position) ?: return null
        return parsed.time.takeIf { position.index == value.length }
    }

    fun sameFixture(bet: JSONObject, match: JSONObject): Boolean {
        val home = normalizedTeam(bet.optString("home"))
        val away = normalizedTeam(bet.optString("away"))
        if (home.isBlank() || away.isBlank()) return false
        if (home != normalizedTeam(match.optString("home")) ||
            away != normalizedTeam(match.optString("away"))) return false
        val expectedDate = betDate(bet)
        val actualDate = matchDate(match)
        // Bet timestamps can be stored in local time while the feed uses UTC.
        // Permit a one-calendar-day boundary difference, but never a distant fixture.
        if (expectedDate.isBlank()) return true
        if (actualDate.isBlank()) return false
        if (expectedDate == actualDate) return true
        val expectedMillis = dateMillis(expectedDate) ?: return false
        val actualMillis = dateMillis(actualDate) ?: return false
        return kotlin.math.abs(expectedMillis - actualMillis) <= 86_400_000L
    }
}
