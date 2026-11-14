import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from refresh_api_sport_unl_fixtures import (
    fetch_matches, normalize_match, refresh_unl_fixtures,
)


class Response:
    def __init__(self, data):
        self.data = data
    def raise_for_status(self):
        pass
    def json(self):
        return {"matches": self.data}


class FakeSession:
    def __init__(self):
        self.calls = []
    def get(self, url, params=None, **kwargs):
        self.calls.append((url, params))
        return Response([{
            "id": 100 + len(self.calls),
            "status": "notstarted",
            "dateEvent": "2027-01-01",
            "startTimestamp": 1798800000000,
            "tournament": {"id": 10783, "name": "UEFA Nations League"},
            "season": {"name": "2026/2027"},
            "homeTeam": {"id": 1, "name": "Czechia"},
            "awayTeam": {"id": 2, "name": "Spain"},
            "homeScore": {"current": None},
            "awayScore": {"current": None},
        }])


class ApiSportNationsLeagueFixtureTests(unittest.TestCase):
    def test_normalize_match_uses_stable_provider_namespace(self):
        row = normalize_match({
            "id": 15534086, "status": "finished", "dateEvent": "2026-11-12",
            "startTimestamp": 1794512700000,
            "tournament": {"id": 10783, "name": "UEFA Nations League"},
            "season": {"name": "2026/2027"},
            "homeTeam": {"id": 1, "name": "Czechia"},
            "awayTeam": {"id": 2, "name": "Spain"},
            "homeScore": {"current": 2}, "awayScore": {"current": 1},
        })
        self.assertEqual(row["id"], "UNL:15534086")
        self.assertEqual(row["providerEventId"], "15534086")
        self.assertEqual(row["leagueCode"], "UNL")
        self.assertEqual(row["status"], "finished")
        self.assertEqual(row["homeScore"], 2)
        self.assertEqual(row["awayScore"], 1)

    def test_fetch_uses_bounded_tournament_queries(self):
        session = FakeSession()
        rows = fetch_matches("test-key", session=session, today=date(2026, 11, 11))
        self.assertTrue(rows)
        self.assertGreater(len(session.calls), 1)
        for _url, params in session.calls:
            self.assertEqual(params["tournament_id"], "10783")
            self.assertLessEqual(
                (date.fromisoformat(params["date_to"]) -
                 date.fromisoformat(params["date_from"])).days, 30
            )
            self.assertNotIn("with_bk_odds", params)

    def test_refresh_preserves_pitchapi_cache_and_replaces_unl_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cache.json"
            path.write_text(
                '{"version":1,"provider":"PitchAPI","competitions":{"UCL":{}},'
                '"matches":{"ucl-1":{"id":"ucl-1","leagueCode":"UCL","provider":"PitchAPI"},'
                '"UNL:old":{"id":"UNL:old","leagueCode":"UNL","provider":"API-Sport"}}}',
                encoding="utf-8",
            )
            session = FakeSession()
            result = refresh_unl_fixtures(
                "test-key", session=session, cache_path=path, today=date(2026, 11, 11)
            )
            cache = result["cache"]
            self.assertIn("ucl-1", cache["matches"])
            self.assertNotIn("UNL:old", cache["matches"])
            self.assertTrue(any(key.startswith("UNL:") for key in cache["matches"]))
            self.assertEqual(cache["competitions"]["UNL"]["provider"], "API-Sport")

    def test_missing_key_fails_before_network(self):
        with self.assertRaises(ValueError):
            fetch_matches("")


if __name__ == "__main__":
    unittest.main()
