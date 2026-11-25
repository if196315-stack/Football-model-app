package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UefaPaperBetImporterTest {
    private fun feed(status: String = "FINISHED", result: String = "WIN"): JSONObject =
        JSONObject(
            """
            {
              "competitions": [{
                "code": "UCL",
                "name": "Лига чемпионов УЕФА",
                "matches": [{
                  "id": "m_18IfVI",
                  "utcDate": "2026-11-25T17:45:00Z",
                  "home": "Sabah FK",
                  "away": "Barcelona",
                  "status": "$status",
                  "scoreStatusFinal": true,
                  "homeScore": 1,
                  "awayScore": 4,
                  "paperSignals": [{
                    "signal_id": "890b9b6096346575331f",
                    "market": "btts",
                    "outcome": "yes",
                    "price": 1.58,
                    "model_probability": 0.72350051,
                    "result": "$result",
                    "profit_units": 0.58,
                    "status": "SETTLED",
                    "settled_at": "2026-11-25T20:08:56Z"
                  }]
                }]
              }]
            }
            """.trimIndent()
        )

    @Test fun importsSettledUefaPaperSignalIntoCompletedHistory() {
        val bets = JSONArray()
        assertTrue(UefaPaperBetImporter.sync(bets, feed()))
        assertEquals(1, bets.length())
        val bet = bets.getJSONObject(0)
        assertEquals("UEFA_PAPER:890b9b6096346575331f", bet.getString("id"))
        assertEquals("UEFA_PAPER", bet.getString("source"))
        assertEquals("WON", bet.getString("status"))
        assertEquals("bttsYes", bet.getString("marketKey"))
        assertEquals("Обе забьют — Да", bet.getString("market"))
        assertEquals(1.58, bet.getDouble("odds"), 0.0001)
        assertEquals(25.0, bet.getDouble("stake"), 0.0001)
        assertEquals(14.5, bet.getDouble("stake") * (bet.getDouble("odds") - 1.0), 0.0001)
        assertEquals("1:4", bet.getString("score"))
        assertTrue(BetSettlement.isSettledStatus(bet.getString("status")))
    }

    @Test fun updatesMatchingPendingLocalBetInsteadOfCreatingDuplicate() {
        val bets = JSONArray().put(
            JSONObject()
                .put("id", "local-bet")
                .put("source", "AI_MANUAL")
                .put("status", "PENDING")
                .put("matchId", "m_18IfVI")
                .put("marketKey", "bttsYes")
                .put("odds", 1.58)
                .put("stake", 7.0)
        )
        assertTrue(UefaPaperBetImporter.sync(bets, feed()))
        assertEquals(1, bets.length())
        val bet = bets.getJSONObject(0)
        assertEquals(7.0, bet.getDouble("stake"), 0.0001)
        assertEquals("local-bet", bet.getString("id"))
        assertEquals("WON", bet.getString("status"))
        assertEquals("890b9b6096346575331f", bet.getString("paperSignalId"))
        assertEquals("1:4", bet.getString("score"))
    }

    @Test fun migratesPreviouslyImportedSettledPaperRowsToTwentyFiveRubles() {
        val bets = JSONArray().put(
            JSONObject()
                .put("id", "UEFA_PAPER:890b9b6096346575331f")
                .put("paperSignalId", "890b9b6096346575331f")
                .put("source", "UEFA_PAPER")
                .put("status", "WON")
                .put("stake", 1.0)
                .put("odds", 1.58)
        )
        assertTrue(UefaPaperBetImporter.sync(bets, feed()))
        assertEquals(25.0, bets.getJSONObject(0).getDouble("stake"), 0.0001)
        assertFalse(UefaPaperBetImporter.sync(bets, feed()))
    }

    @Test fun importIsIdempotentAndDoesNotReopenSettledRows() {
        val bets = JSONArray()
        assertTrue(UefaPaperBetImporter.sync(bets, feed()))
        assertFalse(UefaPaperBetImporter.sync(bets, feed()))
        assertEquals(1, bets.length())
        assertEquals("WON", bets.getJSONObject(0).getString("status"))
    }


    @Test fun collapsesDuplicateImportedRowsWhenOddsHaveChanged() {
        val bets = JSONArray()
            .put(JSONObject().put("id", "UEFA_PAPER:old-pending").put("source", "UEFA_PAPER")
                .put("status", "PENDING").put("matchId", "m_18IfVI").put("marketKey", "bttsYes")
                .put("odds", 1.56).put("stake", 1.0))
            .put(JSONObject().put("id", "UEFA_PAPER:old-settled").put("source", "UEFA_PAPER")
                .put("status", "WON").put("matchId", "m_18IfVI").put("marketKey", "bttsYes")
                .put("odds", 1.63).put("stake", 25.0))
        assertTrue(UefaPaperBetImporter.sync(bets, feed()))
        assertEquals(1, bets.length())
        assertEquals("WON", bets.getJSONObject(0).getString("status"))
        assertEquals(25.0, bets.getJSONObject(0).getDouble("stake"), 0.0001)
    }

    @Test fun refreshedOddsReuseExistingImportedRowInsteadOfAddingAnother() {
        val existing = JSONObject().put("id", "UEFA_PAPER:old-signal").put("source", "UEFA_PAPER")
            .put("status", "PENDING").put("matchId", "m_18IfVI").put("marketKey", "bttsYes")
            .put("odds", 1.56).put("stake", 1.0)
        val bets = JSONArray().put(existing)
        assertTrue(UefaPaperBetImporter.sync(bets, feed()))
        assertEquals(1, bets.length())
        assertEquals(1.58, bets.getJSONObject(0).getDouble("odds"), 0.0001)
    }

    @Test fun unfinishedSignalsRemainPending() {
        val bets = JSONArray()
        assertTrue(UefaPaperBetImporter.sync(bets, feed(status = "IN_PLAY", result = "")))
        assertEquals("PENDING", bets.getJSONObject(0).getString("status"))
        assertFalse(BetSettlement.isSettledStatus(bets.getJSONObject(0).getString("status")))
    }
}
