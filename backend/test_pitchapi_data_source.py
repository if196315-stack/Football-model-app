import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pitchapi_data_source import (
    flatten_shots, load_cache, model_history_rows, normalize_match,
    save_cache, shot_xg,
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


if __name__ == "__main__":
    unittest.main()
