import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diagnose_uefa_betboom_missing_boards import run_diagnostic


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeSession:
    def __init__(self):
        self.calls = []

    def get(self, url, params=None, headers=None, timeout=None):
        self.calls.append((url, params))
        if url.endswith("/matches"):
            return FakeResponse([{
                "id": 9876,
                "startTimestamp": 1795521600000,
                "homeTeam": {"name": "Arsenal"},
                "awayTeam": {"name": "Borussia Dortmund"},
            }])
        return FakeResponse({"match": {
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Borussia Dortmund"},
            "hasBkOdds": {"betboom": True},
            "oddsBk": {"betboom": {"markets": {"total": {"stakes": {}}}}},
        }})


class DiagnosticTests(unittest.TestCase):
    def test_matches_pitchapi_fixture_to_exact_api_sport_id(self):
        cache = {"matches": {
            "pitch-1": {
                "leagueCode": "UCL", "status": "scheduled",
                "date": "2026-11-24", "home": "Arsenal",
                "away": "Borussia Dortmund",
            }
        }}
        feed = {"competitions": [{
            "matches": [{
                "id": "pitch-1", "utcDate": "2026-11-24",
                "home": "Arsenal", "away": "Borussia Dortmund",
                "odds": {"best": {}},
            }]
        }]}
        session = FakeSession()
        result = run_diagnostic(cache, feed, "test-key", session=session)

        self.assertEqual(result["candidate_missing_board_fixtures"], 1)
        self.assertEqual(result["date_lookup_calls"], 1)
        self.assertEqual(result["exact_fixture_calls"], 1)
        self.assertEqual(result["api_calls"], 2)
        self.assertEqual(result["provider_matches_found"], 1)
        self.assertEqual(result["with_betboom_board"], 1)
        self.assertEqual(result["results"][0]["provider_event_id"], "9876")


if __name__ == "__main__":
    unittest.main()
