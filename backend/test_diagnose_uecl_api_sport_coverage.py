import sys
import unittest
from datetime import date
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from diagnose_uecl_api_sport_coverage import audit_coverage, fetch_uecl_fixtures, select_uecl_fixtures

class Response:
    def __init__(self, payload): self.payload = payload
    def raise_for_status(self): pass
    def json(self): return self.payload

class FakeSession:
    def __init__(self): self.calls = []
    def get(self, url, params=None, **kwargs):
        self.calls.append(params)
        return Response({"matches": [], "totalPages": 1})

class UeclCoverageTests(unittest.TestCase):
    def test_selects_upcoming_uecl_only_within_window(self):
        cache = {"matches": {
            "a": {"leagueCode": "UECL", "status": "scheduled", "date": "2026-11-26T20:00:00Z", "home": "A", "away": "B"},
            "b": {"leagueCode": "UECL", "status": "finished", "date": "2026-11-20", "home": "C", "away": "D"},
            "c": {"leagueCode": "UCL", "status": "scheduled", "date": "2026-11-26", "home": "E", "away": "F"},
            "d": {"leagueCode": "UECL", "status": "scheduled", "date": "2027-06-01", "home": "G", "away": "H"},
        }}
        rows = select_uecl_fixtures(cache, date(2026, 11, 11), 180)
        self.assertEqual([row["fixture_id"] for row in rows], ["a"])

    def test_queries_tournament_in_chunks_without_bookmaker_filter(self):
        session = FakeSession()
        rows, calls = fetch_uecl_fixtures(session, "key", date(2026, 11, 11), 65)
        self.assertEqual(rows, [])
        self.assertEqual(calls, 3)
        self.assertTrue(all(p["tournament_id"] == "17015" for p in session.calls))
        self.assertTrue(all("bookmaker_ids" not in p for p in session.calls))
        self.assertTrue(all((date.fromisoformat(p["date_to"]) - date.fromisoformat(p["date_from"])).days < 31 for p in session.calls))

    def test_separates_fixture_and_bookmaker_coverage(self):
        cache = {"matches": {
            "a": {"leagueCode": "UECL", "status": "scheduled", "date": "2026-11-26", "home": "Arsenal", "away": "Chelsea"},
            "b": {"leagueCode": "UECL", "status": "scheduled", "date": "2026-11-27", "home": "Roma", "away": "Ajax"},
        }}
        provider = [{
            "id": 1, "startTimestamp": 1795723200000,
            "homeTeam": {"name": "Arsenal"}, "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"pinnacle": {"markets": {"total": {}}}},
        }]
        result = audit_coverage(cache, provider, date(2026, 11, 11), 30)
        self.assertEqual(result["pitchapi_upcoming_fixtures"], 2)
        self.assertEqual(result["matched_fixtures"], 1)
        self.assertEqual(result["unmatched_fixtures"], 1)
        self.assertEqual(result["matched_with_any_bookmaker_board"], 1)
        self.assertEqual(result["matched_with_betboom_board"], 0)
        self.assertEqual(result["results"][0]["available_bookmakers"], ["pinnacle"])

if __name__ == "__main__":
    unittest.main()
