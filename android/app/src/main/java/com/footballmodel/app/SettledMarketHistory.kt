package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud snapshot for the settled ALL-market MelBet archive.
 * Manual TOP bets are deliberately stored elsewhere.
 */
internal object SettledMarketHistory {
    private val marketKeys = setOf(
        "1x", "x2", "over25", "homeOver15", "awayOver15",
        "homeOver05", "awayOver05", "bttsYes"
    )

    fun settledRows(records: JSONArray, sourceUpdatedAt: String): JSONArray {
        val rows = LinkedHashMap<String, JSONObject>()
        for (index in 0 until records.length()) {
            val source = records.optJSONObject(index) ?: continue
            if (!source.optString("status").equals("SETTLED", true)) continue
            if (!source.optString("bookmaker").equals("MELBET", true)) continue
            val market = source.optString("market_key")
            if (market !in marketKeys) continue
            val league = source.optString("league_code").trim()
            val fixture = source.optString("fixture_id").trim()
            val namespace = source.optString("fixture_namespace").trim()
            if (league.isBlank() || fixture.isBlank()) continue
            val price = source.optDouble("price", Double.NaN)
            val probability = source.optDouble("model_probability", Double.NaN)
            val profit = source.optDouble("profit_units", Double.NaN)
            if (!price.isFinite() || price <= 1.0 || !probability.isFinite() ||
                probability !in 0.0..1.0 || !profit.isFinite()) continue
            if (source.optString("result").uppercase() !in setOf("WIN", "LOSS", "PUSH")) continue
            val key = listOf(namespace, league, fixture, market).joinToString("|")
            rows[key] = JSONObject(source.toString()).put("sourceUpdatedAt", sourceUpdatedAt)
        }
        return JSONArray().apply { rows.values.forEach { put(it) } }
    }

    fun containsAll(remoteRows: JSONArray, currentRows: JSONArray): Boolean {
        val known = HashSet<String>()
        fun identity(row: JSONObject): String = listOf(
            row.optString("fixture_namespace"), row.optString("league_code"),
            row.optString("fixture_id"), row.optString("market_key")
        ).joinToString("|")
        for (index in 0 until remoteRows.length()) {
            remoteRows.optJSONObject(index)?.let { known.add(identity(it)) }
        }
        for (index in 0 until currentRows.length()) {
            val row = currentRows.optJSONObject(index) ?: continue
            if (identity(row) !in known) return false
        }
        return true
    }

    fun merge(
        remoteRows: JSONArray,
        currentRows: JSONArray,
        remoteSourceUpdatedAt: String,
        currentSourceUpdatedAt: String
    ): JSONArray {
        val merged = LinkedHashMap<String, JSONObject>()
        fun identity(row: JSONObject): String = listOf(
            row.optString("fixture_namespace"), row.optString("league_code"),
            row.optString("fixture_id"), row.optString("market_key")
        ).joinToString("|")

        for (index in 0 until remoteRows.length()) {
            val row = remoteRows.optJSONObject(index) ?: continue
            if (row.optString("status").equals("SETTLED", true) &&
                row.optString("bookmaker").equals("MELBET", true)) {
                merged[identity(row)] = JSONObject(row.toString())
            }
        }
        val currentIsNewer = currentSourceUpdatedAt.isNotBlank() &&
            (remoteSourceUpdatedAt.isBlank() || currentSourceUpdatedAt >= remoteSourceUpdatedAt)
        for (index in 0 until currentRows.length()) {
            val row = currentRows.optJSONObject(index) ?: continue
            if (!row.optString("status").equals("SETTLED", true) ||
                !row.optString("bookmaker").equals("MELBET", true)) continue
            val key = identity(row)
            if (key !in merged || currentIsNewer) merged[key] = JSONObject(row.toString())
        }
        return JSONArray().apply { merged.values.forEach { put(it) } }
    }
}
