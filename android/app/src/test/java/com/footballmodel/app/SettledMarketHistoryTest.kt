package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettledMarketHistoryTest {
    private fun row(status: String, price: Double = 1.8, profit: Double = 0.8) = JSONObject()
        .put("status", status).put("bookmaker", "MELBET")
        .put("league_code", "ENG").put("fixture_namespace", "DOM")
        .put("fixture_id", "101").put("market_key", "1x")
        .put("price", price).put("model_probability", 0.7)
        .put("result", if (profit >= 0) "WIN" else "LOSS")
        .put("profit_units", profit)

    @Test fun onlySettledSupportedMelbetRowsAreArchived() {
        val records = JSONArray().put(row("SETTLED")).put(row("PENDING"))
            .put(row("SETTLED").put("bookmaker", "OTHER"))
            .put(row("SETTLED").put("market_key", "unsupported"))
        val settled = SettledMarketHistory.settledRows(records, "2026-11-24T12:00:00Z")
        assertEquals(1, settled.length())
        assertEquals("SETTLED", settled.getJSONObject(0).getString("status"))
        assertEquals("2026-11-24T12:00:00Z", settled.getJSONObject(0).getString("sourceUpdatedAt"))
    }

    @Test fun newerFeedSnapshotUpdatesExistingResultWithoutDuplicating() {
        val old = row("SETTLED", 1.8, 0.8)
        val corrected = row("SETTLED", 1.8, -1.0).put("result", "LOSS")
        val merged = SettledMarketHistory.merge(
            JSONArray().put(old), JSONArray().put(corrected),
            "2026-11-23T10:00:00Z", "2026-11-24T10:00:00Z"
        )
        assertEquals(1, merged.length())
        assertEquals("LOSS", merged.getJSONObject(0).getString("result"))
        assertEquals(-1.0, merged.getJSONObject(0).getDouble("profit_units"), 0.00001)
    }

    @Test fun identicalSourceSnapshotAlreadyCoveredByCloudIsNotRewritten() {
        val remote = JSONArray().put(row("SETTLED")).put(row("SETTLED").put("fixture_id", "202"))
        val current = JSONArray().put(row("SETTLED"))
        assertTrue(SettledMarketHistory.containsAll(remote, current))
        assertTrue(!SettledMarketHistory.containsAll(current, remote))
    }

    @Test fun olderFeedCannotOverwriteNewerCloudResultAndRemoteHistoryIsRetained() {
        val newer = row("SETTLED", 1.8, -1.0).put("result", "LOSS")
        val unrelated = row("SETTLED").put("fixture_id", "202")
        val stale = row("SETTLED", 1.8, 0.8)
        val merged = SettledMarketHistory.merge(
            JSONArray().put(newer).put(unrelated), JSONArray().put(stale),
            "2026-11-24T10:00:00Z", "2026-11-23T10:00:00Z"
        )
        assertEquals(2, merged.length())
        assertEquals("LOSS", merged.getJSONObject(0).getString("result"))
        assertTrue((0 until merged.length()).any { merged.getJSONObject(it).optString("fixture_id") == "202" })
    }
}
