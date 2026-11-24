package com.footballmodel.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchIdentityTest {
    @Test fun matchingIdDoesNotOverrideDifferentTeams() {
        val bet = JSONObject().put("matchId", "same-id").put("home", "Brest")
            .put("away", "Paris").put("date", "2026-11-27")
        val other = JSONObject().put("id", "same-id").put("home", "Hamburg")
            .put("away", "Bayern").put("utcDate", "2026-11-27T18:00:00Z")
        assertFalse(MatchIdentity.sameFixture(bet, other))
    }

    @Test fun matchingTeamsOnWrongDateAreRejected() {
        val bet = JSONObject().put("home", "Hamburg").put("away", "Bayern")
            .put("date", "2026-11-28")
        val oldFixture = JSONObject().put("home", "Hamburg").put("away", "Bayern")
            .put("utcDate", "2026-01-31T17:00:00Z")
        assertFalse(MatchIdentity.sameFixture(bet, oldFixture))
    }

    @Test fun exactTeamsAndDateAreAccepted() {
        val bet = JSONObject().put("home", "Sunderland").put("away", "Tottenham")
            .put("date", "2026-11-29")
        val fixture = JSONObject().put("home", "Sunderland").put("away", "Tottenham")
            .put("utcDate", "2026-11-29T16:00:00Z")
        assertTrue(MatchIdentity.sameFixture(bet, fixture))
    }

    @Test fun sameFixtureMatchesAcrossUtcLocalMidnightBoundary() {
        val bet = JSONObject().put("home", "Real Madrid").put("away", "PSV Eindhoven")
            .put("date", "2026-11-25")
        val fixture = JSONObject().put("home", "Real Madrid").put("away", "PSV Eindhoven")
            .put("utcDate", "2026-11-24T23:30:00Z")
        assertTrue(MatchIdentity.sameFixture(bet, fixture))
    }

    @Test fun sameTeamsMoreThanOneDayApartAreRejected() {
        val bet = JSONObject().put("home", "Real Madrid").put("away", "PSV Eindhoven")
            .put("date", "2026-11-27")
        val fixture = JSONObject().put("home", "Real Madrid").put("away", "PSV Eindhoven")
            .put("utcDate", "2026-11-24T23:30:00Z")
        assertFalse(MatchIdentity.sameFixture(bet, fixture))
    }

    @Test fun dateLessBetCanMatchOnlyWhenTeamPairIsUnique() {
        val bet = JSONObject().put("home", "Brest").put("away", "Paris")
        val fixture = JSONObject().put("home", "Brest").put("away", "Paris")
            .put("utcDate", "2026-11-27T18:00:00Z")
        assertTrue(MatchIdentity.sameFixture(bet, fixture))
        assertTrue(MatchIdentity.uniqueMatch(listOf(fixture)) === fixture)
        assertTrue(MatchIdentity.uniqueMatch(listOf(fixture, fixture)) == null)
    }
}
