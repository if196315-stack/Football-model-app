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

    /** Parses a fixture date (not a prediction/bet creation timestamp) as UTC. */
    fun matchDateMillis(rawValue: String): Long? {
        val raw = rawValue.trim()
        if (raw.isBlank()) return null
        return try {
            val pattern = if (raw.length >= 19) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd"
            val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            format.parse(raw.take(if (pattern.length > 10) 19 else 10))?.time
        } catch (_: Exception) { null }
    }

    fun matchDateInPeriod(rawMatchDate: String, fromInclusive: Long, toInclusive: Long): Boolean {
        val matchDay = matchDateMillis(rawMatchDate) ?: return false
        return matchDay in fromInclusive..toInclusive
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

    /**
     * Builds a complete, de-duplicated evaluation set. The dedicated archive remains
     * authoritative; completed fixtures with their own captured prediction are a
     * safe fallback when a result has reached the competition feed first.
     *
     * IDs are namespaced by competition because providers can reuse fixture IDs.
     */
    fun evaluationMatchesForStats(feed: org.json.JSONObject): org.json.JSONArray {
        val result = org.json.JSONArray()
        val indexes = mutableMapOf<String, Int>()
        val unscopedArchivedIds = mutableSetOf<String>()

        fun fixtureKey(league: String, id: String): String =
            if (league.isBlank()) ":$id" else "$league:$id"

        fun hasFinalScore(row: org.json.JSONObject): Boolean =
            row.optString("status").trim().uppercase(java.util.Locale.ROOT) in
                setOf("FINISHED", "FT", "AET", "PEN", "FULL_TIME", "AFTER_EXTRA_TIME", "AFTER_PENALTIES") &&
                row.has("homeScore") && !row.isNull("homeScore") &&
                row.has("awayScore") && !row.isNull("awayScore")

        // Start with the dedicated archive, but do not let an old scheduled
        // snapshot block a newer confirmed result from the competition feed.
        val archived = feed.optJSONArray("evaluationMatches")
        if (archived != null) {
            for (i in 0 until archived.length()) {
                val row = archived.optJSONObject(i) ?: continue
                val id = row.optString("id").trim()
                if (id.isBlank()) continue
                val league = row.optString("leagueCode").trim()
                val key = fixtureKey(league, id)
                if (league.isBlank()) unscopedArchivedIds.add(id)
                if (!indexes.containsKey(key)) {
                    indexes[key] = result.length()
                    result.put(org.json.JSONObject(row.toString()))
                }
            }
        }

        val competitions = feed.optJSONArray("competitions") ?: return result
        for (i in 0 until competitions.length()) {
            val competition = competitions.optJSONObject(i) ?: continue
            val league = competition.optString("code").trim()
            val matches = competition.optJSONArray("matches") ?: continue
            for (j in 0 until matches.length()) {
                val match = matches.optJSONObject(j) ?: continue
                val id = match.optString("id").trim()
                if (id.isBlank()) continue
                val key = fixtureKey(league, id)
                val existingIndex = indexes[key]
                if (existingIndex != null) {
                    val existing = result.optJSONObject(existingIndex) ?: continue
                    if (hasFinalScore(match) && !hasFinalScore(existing)) {
                        // Keep the frozen prediction from the archive; refresh only
                        // result fields from the authoritative current fixture.
                        val merged = org.json.JSONObject(existing.toString())
                        for (field in listOf("status", "homeScore", "awayScore", "utcDate", "dateEvent", "date")) {
                            if (match.has(field)) {
                                if (match.isNull(field)) merged.remove(field) else merged.put(field, match.get(field))
                            }
                        }
                        if (!merged.has("prediction") || merged.isNull("prediction")) {
                            match.optJSONObject("prediction")?.let { merged.put("prediction", org.json.JSONObject(it.toString())) }
                        }
                        result.put(existingIndex, merged)
                    }
                    continue
                }
                if (id in unscopedArchivedIds || !hasFinalScore(match)) continue
                val date = match.optString("utcDate").ifBlank {
                    match.optString("dateEvent").ifBlank { match.optString("date") }
                }
                if (matchDateMillis(date) == null) continue
                val prediction = match.optJSONObject("prediction") ?: continue
                val hasProbability = listOf(
                    "1x", "x2", "over15", "over25", "over35",
                    "homeOver05", "homeOver15", "homeOver25", "homeOver35",
                    "awayOver05", "awayOver15", "awayOver25", "awayOver35",
                    "bttsYes", "bttsNo"
                ).any { prediction.has(it) && !prediction.isNull(it) &&
                    prediction.optDouble(it, Double.NaN).isFinite() }
                if (!hasProbability) continue

                val fallback = org.json.JSONObject(match.toString())
                if (league.isNotBlank()) fallback.put("leagueCode", league)
                indexes[key] = result.length()
                result.put(fallback)
            }
        }
        return result
    }
}
