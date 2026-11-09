import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pitchapi_data_source import (
    flatten_shots, load_cache, model_history_rows, normalize_match,
    save_cache, shot_xg, refresh_history,
)


class PitchApiDataSourceTests(unittest.TestCase):
    def test_flatten_shots_supports_periods_and_direct_list(self):
        self.assertEqual(len(flatten_shots({"periods": [
            {"shots": [{"id": "a"}]}, {"shots": [{"id": "b"}]}
        ]})), 2)
        self.assertEqual(flatten_shots({"shots": [{"id": "c"}]})[0]["id"], "c")

    def test_shot_xg_sums_by_team_and_keeps_zero_distinct_from_missing(self):
        home, away, count = shot_xg({
            "periods": [{"shots": [
                {"team_id": 1, "expected_goals": 0.2},
                {"team_id": 1, "expected_goals": 0.0},
                {"team_id": 2, "expected_goals": 0.4},
                {"team_id": 3, "expected_goals": 0.9},
            ]}]
        }, 1, 2)
        self.assertEqual((home, away, count), (0.2, 0.4, 3))

    def test_no_valid_shots_is_null_not_zero(self):
        self.assertEqual(shot_xg({"periods": []}, 1, 2), (None, None, 0))
        self.assertEqual(shot_xg({"shots": [
            {"team_id": 1, "expected_goals": None}
        ]}, 1, 2), (None, None, 0))

    def test_normalize_fixture_maps_pitchapi_fields(self):
        row = normalize_match({
            "id": "m1", "date": "2026-11-01T12:00:00Z",
            "status": "finished", "score_home": 2, "score_away": 1,
            "home_team": {"id": 10, "name": "Home"},
            "away_team": {"id": 20, "name": "Away"},
        }, "ENG", {"id": 7, "name": "Premier League"}, "2026/2027")
        self.assertEqual(row["id"], "m1")
        self.assertEqual(row["homeScore"], 2)
        self.assertEqual(row["awayScore"], 1)
        self.assertEqual(row["homeTeamId"], 10)
        self.assertEqual(row["provider"], "PitchAPI")

    def test_cache_round_trip_is_atomic_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cache.json"
            cache = {"version": 1, "provider": "PitchAPI", "matches": {"m1": {"id": "m1"}}}
            save_cache(cache, path)
            self.assertEqual(load_cache(path)["matches"]["m1"]["id"], "m1")
            self.assertFalse(path.with_suffix(".json.tmp").exists())

    def test_model_history_requires_paired_xg_scores_and_regular_season(self):
        cache = {"matches": {
            "good": {"id": "good", "status": "finished", "homeScore": 1,
                     "awayScore": 0, "homeXg": 0.8, "awayXg": 0.2,
                     "hasPlayoff": False, "date": "2025-01-01"},
            "partial": {"id": "partial", "status": "finished", "homeScore": 1,
                        "awayScore": 0, "homeXg": 0.8, "awayXg": None},
            "playoff": {"id": "playoff", "status": "finished", "homeScore": 1,
                        "awayScore": 0, "homeXg": 0.8, "awayXg": 0.2,
                        "hasPlayoff": True},
            "live": {"id": "live", "status": "in_play", "homeXg": 0.8, "awayXg": 0.2},
        }}
        self.assertEqual([r["id"] for r in model_history_rows(cache)], ["good"])



    def test_refresh_requests_upcoming_fixtures_and_caches_them(self):
        class Response:
            status_code = 200
            headers = {}
            def __init__(self, data):
                self.data = data
            def raise_for_status(self):
                pass
            def json(self):
                return {"data": self.data}

        leagues = [
            {"id": f"l_{code}", "name": name, "country_code": code,
             "seasons": ["2026/2027"]}
            for code, name in [
                ("ENG", "Premier League"), ("ESP", "LaLiga"),
                ("ITA", "Serie A"), ("GER", "Bundesliga"),
                ("FRA", "Ligue 1"),
            ]
        ]
        class Session:
            def __init__(self):
                self.match_params = []
            def get(self, url, params=None, **kwargs):
                if url.endswith("/leagues"):
                    return Response({"leagues": leagues})
                self.match_params.append(params)
                code = url.split("/leagues/")[1].split("/")[0]
                return Response({"matches": [{
                    "id": f"m_{code}", "date": "2026-11-20",
                    "status": "not_started", "score_home": None,
                    "score_away": None,
                    "home_team": {"id": f"h_{code}", "name": "Home"},
                    "away_team": {"id": f"a_{code}", "name": "Away"},
                }]})

        with tempfile.TemporaryDirectory() as tmp:
            session = Session()
            result = refresh_history(
                "test-key", session=session, cache_path=Path(tmp) / "cache.json",
                pause_seconds=0, max_shot_requests=0,
            )
        self.assertEqual(len(session.match_params), 5)
        self.assertTrue(all(p["status"] == "all" for p in session.match_params))
        self.assertEqual(len(result["cache"]["matches"]), 5)
        self.assertTrue(all(r["status"] == "not_started"
                            for r in result["cache"]["matches"].values()))



    def test_shot_requests_prioritize_oldest_unresolved_matches(self):
        class Response:
            status_code = 200
            headers = {}
            def __init__(self, data):
                self.data = data
            def raise_for_status(self):
                pass
            def json(self):
                return {"data": self.data}

        leagues = [
            {"id": f"l_{code}", "name": name, "country_code": code,
             "seasons": ["2021/2022", "2026/2027"]}
            for code, name in [
                ("ENG", "Premier League"), ("ESP", "LaLiga"),
                ("ITA", "Serie A"), ("GER", "Bundesliga"),
                ("FRA", "Ligue 1"),
            ]
        ]
        class Session:
            def __init__(self):
                self.shot_match_ids = []
            def get(self, url, params=None, **kwargs):
                if url.endswith("/leagues"):
                    return Response({"leagues": leagues})
                if "/shots" in url:
                    match_id = url.rsplit("/", 2)[-2]
                    self.shot_match_ids.append(match_id)
                    return Response({"shots": [
                        {"team_id": f"h_{match_id}", "expected_goals": 0.2},
                        {"team_id": f"a_{match_id}", "expected_goals": 0.3},
                    ]})
                code = url.split("/leagues/")[1].split("/")[0].replace("l_", "")
                matches = []
                for suffix, date in (("old", "2021-08-01"), ("new", "2026-08-01")):
                    matches.append({
                        "id": f"m_{code}_{suffix}", "date": date,
                        "status": "finished", "score_home": 1, "score_away": 0,
                        "home_team": {"id": f"h_m_{code}_{suffix}", "name": "Home"},
                        "away_team": {"id": f"a_m_{code}_{suffix}", "name": "Away"},
                    })
                return Response({"matches": matches})

        with tempfile.TemporaryDirectory() as tmp:
            session = Session()
            result = refresh_history(
                "test-key", session=session, cache_path=Path(tmp) / "cache.json",
                pause_seconds=0, max_shot_requests=1,
            )
        self.assertEqual(session.shot_match_ids, ["m_ENG_old"])
        self.assertEqual(result["summary"]["shotAttempts"], 1)
        self.assertEqual(result["cache"]["matches"]["m_ENG_old"]["homeXg"], 0.2)

    def test_unresolved_shot_feed_uses_seven_day_retry_cooldown(self):
        from datetime import datetime, timedelta, timezone

        class Response:
            status_code = 200
            headers = {}
            def __init__(self, data):
                self.data = data
            def raise_for_status(self):
                pass
            def json(self):
                return {"data": self.data}

        leagues = [
            {"id": f"l_{code}", "name": name, "country_code": code,
             "seasons": ["2026/2027"]}
            for code, name in [
                ("ENG", "Premier League"), ("ESP", "LaLiga"),
                ("ITA", "Serie A"), ("GER", "Bundesliga"),
                ("FRA", "Ligue 1"),
            ]
        ]
        class Session:
            def __init__(self):
                self.shot_calls = 0
            def get(self, url, params=None, **kwargs):
                if url.endswith("/leagues"):
                    return Response({"leagues": leagues})
                if "/shots" in url:
                    self.shot_calls += 1
                    return Response({"periods": []})
                code = url.split("/leagues/")[1].split("/")[0].replace("l_", "")
                return Response({"matches": [{
                    "id": f"m_{code}", "date": "2026-11-01",
                    "status": "finished", "score_home": 1, "score_away": 0,
                    "home_team": {"id": f"h_{code}", "name": "Home"},
                    "away_team": {"id": f"a_{code}", "name": "Away"},
                }]})

        now = datetime.now(timezone.utc).isoformat()
        old = (datetime.now(timezone.utc) - timedelta(days=8)).isoformat()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cache.json"
            cache = {"version": 1, "provider": "PitchAPI", "matches": {}}
            for code in ("ENG", "ESP", "ITA", "GER", "FRA"):
                cache["matches"][f"m_{code}"] = {
                    "id": f"m_{code}", "homeXg": None, "awayXg": None,
                    "xgShotCount": 0, "xgFetchedAt": now,
                }
            save_cache(cache, path)
            session = Session()
            result = refresh_history(
                "test-key", session=session, cache_path=path,
                pause_seconds=0, max_shot_requests=5,
            )
            self.assertEqual(session.shot_calls, 0)
            self.assertEqual(result["summary"]["shotAttempts"], 0)
            self.assertEqual(result["summary"]["pendingShotXg"], 5)

            cache = load_cache(path)
            for row in cache["matches"].values():
                row["xgFetchedAt"] = old
            save_cache(cache, path)
            session = Session()
            result = refresh_history(
                "test-key", session=session, cache_path=path,
                pause_seconds=0, max_shot_requests=1,
            )
            self.assertEqual(session.shot_calls, 1)
            self.assertEqual(result["summary"]["shotAttempts"], 1)
            attempted = next(iter(result["cache"]["matches"].values())).get("xgAttemptedAt")
            self.assertTrue(attempted)

if __name__ == "__main__":
    unittest.main()
