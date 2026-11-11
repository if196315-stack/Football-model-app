import unittest

from probe_pitchapi_unl_date_index import probe


class Response:
    def __init__(self, matches):
        self.matches = matches
    def raise_for_status(self):
        pass
    def json(self):
        return {"data": {"matches": self.matches}}


class FakeSession:
    def __init__(self):
        self.calls = []
    def get(self, url, **kwargs):
        self.calls.append(url)
        return Response([{
            "id": "m_ABC123",
            "home_team": {"id": "t_HOME12", "name": "Czechia"},
            "away_team": {"id": "t_AWAY12", "name": "Spain"},
        }])


class UnlDateIndexProbeTests(unittest.TestCase):
    def test_matches_fixture_and_requires_paired_shot_xg(self):
        session = FakeSession()
        report = probe(
            {"matches": {"a": {
                "provider": "API-Sport", "leagueCode": "UNL",
                "providerEventId": "100", "date": "2026-11-12T19:45:00Z",
                "home": "Czechia", "away": "Spain",
            }}},
            "test-key", session=session,
            fetch_shots_fn=lambda match_id: {"shots": [
                {"team_id": "t_HOME12", "expected_goals": 0.2},
                {"team_id": "t_AWAY12", "expected_goals": 0.3},
            ]},
            pause=0,
        )
        self.assertEqual(report["distinctDatesQueried"], 1)
        self.assertEqual(report["dateIndexMatches"], 1)
        self.assertEqual(report["matchesWithPairedXg"], 1)
        self.assertEqual(report["results"][0]["pitchApiMatchId"], "m_ABC123")
        self.assertEqual(session.calls, ["https://api.pitchapi.dev/v1/date/2026-11-12"])

    def test_nonmatching_fixture_is_not_sent_to_shots_endpoint(self):
        report = probe(
            {"matches": {"a": {
                "provider": "API-Sport", "leagueCode": "UNL",
                "date": "2026-11-12", "home": "Czechia", "away": "Spain",
            }}},
            "test-key", session=FakeSession(),
            fetch_shots_fn=lambda _: self.fail("shots must not be requested"),
            pause=0,
        )
        self.assertEqual(report["dateIndexMatches"], 0)
        self.assertEqual(report["matchesWithPairedXg"], 0)

    def test_missing_key_fails_before_requests(self):
        with self.assertRaises(ValueError):
            probe({"matches": {}}, "")


if __name__ == "__main__":
    unittest.main()
