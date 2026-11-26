package com.footballmodel.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SabahDuplicateBetCleanupTest {
    @Test
    fun keepsOneSabahBarcelonaItb05AndPermanentlyTombstonesDuplicates() {
        val canonicalId = "AI_MANUAL:m_18IfVI:homeOver05:1795604905046"
        val duplicateId1 = "AI_MANUAL:m_18IfVI:homeOver05:1795604906001"
        val duplicateId2 = "AI_MANUAL:m_18IfVI:homeOver05:1795604907002"
        val bets = JSONArray()
            .put(JSONObject().put("id", canonicalId).put("matchId", "m_18IfVI")
                .put("marketKey", "homeOver05").put("status", "WON"))
            .put(JSONObject().put("id", duplicateId1).put("matchId", "m_18IfVI")
                .put("marketKey", "homeOver05").put("status", "WON"))
            .put(JSONObject().put("id", duplicateId2).put("matchId", "m_18IfVI")
                .put("marketKey", "homeOver05").put("status", "WON"))
            .put(JSONObject().put("id", "UEFA_PAPER:890b9b6096346575331f")
                .put("matchId", "m_18IfVI").put("marketKey", "bttsYes").put("status", "WON"))
        val tombstones = linkedSetOf<String>()

        assertTrue(PrivateHistorySync.removeDuplicateSabahHomeOver05(bets, tombstones))
        assertEquals(2, bets.length())
        assertEquals(canonicalId, bets.getJSONObject(0).getString("id"))
        assertEquals("bttsYes", bets.getJSONObject(1).getString("marketKey"))
        assertTrue(duplicateId1 in tombstones)
        assertTrue(duplicateId2 in tombstones)
        assertFalse(PrivateHistorySync.removeDuplicateSabahHomeOver05(bets, tombstones))
    }
}
