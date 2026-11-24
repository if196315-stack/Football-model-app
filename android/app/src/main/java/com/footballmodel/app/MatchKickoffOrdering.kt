package com.footballmodel.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Shared chronological ordering for fixture and paper-bet lists.
 *
 * Prefer a full ISO kickoff datetime over startTimestamp because some feeds
 * provide a date-only epoch value alongside an exact utcDate.
 */
internal object MatchKickoffOrdering {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd"
    )

    fun timestamp(match: JSONObject): Long? {
        val rawValues = listOf(
            match.optString("utcDate"),
            match.optString("dateEvent"),
            match.optString("kickoff"),
            match.optString("date")
        ).filter { it.isNotBlank() }

        // Exact date-time strings are the most reliable source for kickoff time.
        rawValues.firstOrNull { it.contains('T') }?.let { raw ->
            parse(raw)?.let { return it }
        }

        val epoch = match.optLong("startTimestamp", 0L)
        if (epoch > 0L) return if (epoch < 10_000_000_000L) epoch * 1000L else epoch

        return rawValues.firstNotNullOfOrNull(::parse)
    }

    fun comparator(): Comparator<JSONObject> =
        compareBy<JSONObject> { timestamp(it) ?: Long.MAX_VALUE }

    private fun parse(raw: String): Long? {
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = utc
                }
                parser.parse(raw)?.let { return it.time }
            } catch (_: Exception) {
                // Try the next supported ISO format.
            }
        }
        return null
    }
}
