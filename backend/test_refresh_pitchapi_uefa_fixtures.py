import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import requests
from refresh_pitchapi_uefa_fixtures import refresh_uefa_fixtures, load_cache, fetch


class Response:
    status_code = 200
    headers = {}

    def __init__(self, data):
        self.data = data

    def raise_for_status(self):
        pass

    def json(self):
        return {"data": self.data}


class FakeSession:
    def __init__(self, include_unl=False):
        self.calls = []
        self.include_unl = include_unl

    def get(self, url, params=None, **kwargs):
        self.calls.append((url, params or {}))
        if url.endswith("/leagues"):
            return Response({"leagues": [
                {"id": "ucl-id", "name": "Champions League", "country_code": "INT-2",
                 "seasons": ["2025/2026", "2026/2027"]},
                {"id": "uel-id", "name": "Europa League", "country_code": "INT-2",
                 "seasons": ["2025/2026", "2026/2027"]},
                {"id": "uecl-id", "name": "UEFA Conference League", "country_code": "INT-2",
                 "seasons": ["2025/2026", "2026/2027"]},
                *([{"id": "unl-id", "name": "UEFA Nations League", "country_code": "INT-2",
                    "seasons": ["2024/2025", "2026/2027"]}] if self.include_unl else []),
                {"id": "women-id", "name": "UEFA Women's Champions League",
                 "country_code": "INT-2", "seasons": ["2026/2027"]},
                {"id": "youth-id", "name": "UEFA Youth League",
                 "country_code": "INT-2", "seasons": ["2026/2027"]},
            ]})
        league_id = url.split("/leagues/")[1].split("/")[0]
        code = {"ucl-id": "UCL", "uel-id": "UEL", "uecl-id": "UECL", "unl-id": "UNL"}[league_id]
        season = (params or {}).get("season")
        return Response({"matches": [{
            "id": f"{code}-{season}", "date": "2026-11-10",
            "status": "not_started", "score_home": None, "score_away": None,
            "home_team": {"id": f"h-{code}", "name": "Home"},
            "away_team": {"id": f"a-{code}", "name": "Away"},
        }]})


class UefaFixtureHistoryTests(unittest.TestCase):
    def test_fixture_only_refresh_caches_both_competitions_without_shot_calls(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session = FakeSession()
            result = refresh_uefa_fixtures(
                "test-key", session=session, cache_path=root / "cache.json",
                report_path=root / "report.json", pause_seconds=0,
            )
            cache = load_cache(root / "cache.json")
            self.assertEqual(set(cache["competitions"]), {"UCL", "UEL", "UECL"})
            self.assertEqual(len(cache["matches"]), 6)
            self.assertEqual(result["report"]["listedMatchesThisRun"], 6)
            self.assertTrue(all(row["provider"] == "PitchAPI" for row in cache["matches"].values()))
            self.assertTrue(all("homeXg" not in row and "awayXg" not in row
                                for row in cache["matches"].values()))
            self.assertFalse(any("/shots" in url for url, _ in session.calls))
            self.assertEqual(len([1 for url, _ in session.calls if "/matches" in url]), 6)


    def test_nations_league_is_ingested_when_pitchapi_catalogue_has_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session = FakeSession(include_unl=True)
            result = refresh_uefa_fixtures(
                "test-key", session=session, cache_path=root / "cache.json",
                report_path=root / "report.json", pause_seconds=0,
            )
            cache = load_cache(root / "cache.json")
            self.assertEqual(set(cache["competitions"]), {"UCL", "UEL", "UECL", "UNL"})
            unl_rows = [row for row in cache["matches"].values()
                        if row["leagueCode"] == "UNL"]
            self.assertEqual(len(unl_rows), 2)
            self.assertTrue(all(row["provider"] == "PitchAPI" for row in unl_rows))
            self.assertEqual(result["report"]["optionalCompetitionsMissing"], [])

    def test_missing_optional_nations_league_does_not_break_existing_refresh(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = refresh_uefa_fixtures(
                "test-key", session=FakeSession(), cache_path=Path(tmp) / "cache.json",
                report_path=Path(tmp) / "report.json", pause_seconds=0,
            )
            self.assertEqual(result["report"]["optionalCompetitionsMissing"], ["UNL"])
            self.assertEqual(set(result["cache"]["competitions"]), {"UCL", "UEL", "UECL"})


    def test_normalizes_documented_pitchapi_time_utc_field(self):
        from refresh_pitchapi_uefa_fixtures import normalize_match
        row = normalize_match({
            "id": "m_ABC123", "time_utc": "2026-11-12T19:45:00Z",
            "status": "finished", "score_home": 2, "score_away": 1,
            "home_team": {"id": "t_HOME12", "name": "Home"},
            "away_team": {"id": "t_AWAY12", "name": "Away"},
        }, "UNL", {"id": "unl-id", "name": "Nations League"}, "2026/2027")
        self.assertEqual(row["date"], "2026-11-12T19:45:00Z")
        self.assertEqual(row["leagueCode"], "UNL")
        self.assertEqual(row["homeScore"], 2)

    def test_season_requests_are_status_all(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession()
            refresh_uefa_fixtures(
                "test-key", session=session, cache_path=Path(tmp) / "cache.json",
                report_path=Path(tmp) / "report.json", pause_seconds=0,
            )
            requests = [params for url, params in session.calls if "/matches" in url]
            self.assertEqual(len(requests), 6)
            self.assertEqual(
                {(params["season"], params["status"]) for params in requests},
                {("2025/2026", "all"), ("2026/2027", "all")},
            )

    def test_upcoming_only_refresh_requests_only_latest_season(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession()
            result = refresh_uefa_fixtures(
                "test-key", session=session, cache_path=Path(tmp) / "cache.json",
                report_path=Path(tmp) / "report.json", pause_seconds=0, upcoming_only=True,
            )
            requests_made = [params for url, params in session.calls if "/matches" in url]
            self.assertEqual(len(requests_made), 3)
            self.assertEqual({params["season"] for params in requests_made}, {"2026/2027"})
            self.assertTrue(result["report"]["upcomingSeasonOnly"])

    def test_fetch_retries_transient_521_response(self):
        class FlakySession:
            calls = 0

            def get(self, url, params=None, **kwargs):
                self.calls += 1
                if self.calls == 1:
                    class BadResponse:
                        status_code = 521
                        headers = {}
                        def raise_for_status(self):
                            raise requests.HTTPError("521 origin unavailable")
                    return BadResponse()
                return Response({"leagues": []})

        session = FlakySession()
        with patch("refresh_pitchapi_uefa_fixtures.time.sleep", return_value=None):
            data = fetch(session, "/leagues", "test-key")
        self.assertEqual(data, {"leagues": []})
        self.assertEqual(session.calls, 2)

    def test_missing_api_key_fails_before_network_call(self):
        with self.assertRaises(ValueError):
            refresh_uefa_fixtures("", session=FakeSession())


if __name__ == "__main__":
    unittest.main()
