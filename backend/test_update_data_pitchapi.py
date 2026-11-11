import unittest
from unittest.mock import patch

import pandas as pd

from update_data import markets

from update_data_pitchapi import (
    app_status, build_model_frames, find_odds_event, fixture_identity, preserve_odds_by_identity,
)


class PitchApiUpdaterTests(unittest.TestCase):
    def test_upcoming_pitchapi_status_maps_to_app_schedule(self):
        self.assertEqual(app_status("not_started", "2026-11-20", today=pd.Timestamp("2026-11-09").date()), "SCHEDULED")
        self.assertEqual(app_status("not_started", "2026-10-10", today=pd.Timestamp("2026-11-09").date()), "POSTPONED")
        self.assertEqual(app_status("finished"), "FINISHED")
        self.assertEqual(app_status("in_play"), "IN_PLAY")

    def test_build_model_frames_uses_paired_xg_and_excludes_known_bad_cohorts(self):
        cache = {"matches": {
            "good": {"id": "good", "leagueCode": "ENG", "season": "2024/25",
                     "status": "finished", "date": "2025-01-01T12:00:00Z",
                     "home": "Arsenal", "away": "Chelsea", "homeScore": 2,
                     "awayScore": 0, "homeXg": 1.4, "awayXg": 0.4,
                     "hasPlayoff": False},
            "partial": {"id": "partial", "leagueCode": "ENG", "season": "2024/25",
                        "status": "finished", "date": "2025-01-02T12:00:00Z",
                        "home": "A", "away": "B", "homeScore": 1,
                        "awayScore": 1, "homeXg": 0.4, "awayXg": None},
            "bad_cohort": {"id": "bad_cohort", "leagueCode": "ESP", "season": "2023/24",
                           "status": "finished", "date": "2024-01-01T12:00:00Z",
                           "home": "A", "away": "B", "homeScore": 1,
                           "awayScore": 0, "homeXg": 1.0, "awayXg": 0.2},
            "germany_awarded_season_finished": {"id": "germany_good", "leagueCode": "GER", "season": "2024/25",
                        "status": "finished", "date": "2025-02-02T12:00:00Z",
                        "home": "Bayern München", "away": "Mainz 05", "homeScore": 3,
                        "awayScore": 0, "homeXg": 2.1, "awayXg": 0.3,
                        "hasPlayoff": False},
            "germany_awarded_fixture": {"id": "germany_awarded", "leagueCode": "GER", "season": "2024/25",
                        "status": "awarded", "date": "2024-12-14T14:30:00Z",
                        "home": "Union Berlin", "away": "Bochum", "homeScore": 0,
                        "awayScore": 2, "homeXg": None, "awayXg": None,
                        "hasPlayoff": False},
            "playoff": {"id": "playoff", "leagueCode": "ITA", "season": "2024/25",
                        "status": "finished", "date": "2025-02-01T12:00:00Z",
                        "home": "C", "away": "D", "homeScore": 1,
                        "awayScore": 0, "homeXg": 1.0, "awayXg": 0.2,
                        "hasPlayoff": True},
            "manual_playoff": {"id": "m_3ir3kE", "leagueCode": "ITA", "season": "2022/23",
                               "status": "finished", "date": "2023-06-11T12:00:00Z",
                               "home": "Spezia", "away": "Hellas Verona", "homeScore": 1,
                               "awayScore": 3, "homeXg": 0.8, "awayXg": 1.1,
                               "hasPlayoff": False},
        }}
        history, base, results = build_model_frames(cache)
        self.assertEqual(len(history), 4)
        self.assertIsNone(history.date.dt.tz)
        self.assertIsNone(results.date.dt.tz)
        self.assertEqual(len(results), 3)
        self.assertIn("partial", set(results.match_id))
        self.assertEqual(results.match_id.value_counts().max(), 1)
        self.assertEqual(set(history.team), {"Arsenal", "Chelsea", "Bayern München", "Mainz 05"})
        self.assertNotIn("germany_awarded", set(history.match_id))
        self.assertAlmostEqual(base["eng", "home"], 1.4)
        self.assertAlmostEqual(base["eng", "away"], 0.4)

    def test_baseline_sample_includes_finished_results_without_paired_xg(self):
        from update_data import historical_baselines
        cache = {"matches": {
            "paired": {"id": "paired", "leagueCode": "ENG", "season": "2024/25",
                       "status": "finished", "date": "2025-01-01",
                       "home": "A", "away": "B", "homeScore": 2, "awayScore": 1,
                       "homeXg": 1.2, "awayXg": 0.5, "hasPlayoff": False},
            "score_only": {"id": "score_only", "leagueCode": "ENG", "season": "2024/25",
                           "status": "finished", "date": "2025-01-02",
                           "home": "C", "away": "D", "homeScore": 0, "awayScore": 0,
                           "homeXg": None, "awayXg": None, "hasPlayoff": False},
        }}
        _, _, results = build_model_frames(cache)
        baseline = historical_baselines(results, "eng", "2025-02-01")
        self.assertEqual(len(results), 2)
        self.assertEqual(baseline["matches"], 2)
        self.assertEqual(set(results.match_id), {"paired", "score_only"})

    def test_odds_event_matches_date_only_pitchapi_fixture(self):
        fixture = {"home": "Manchester City", "away": "Fulham", "utcDate": "2026-11-21"}
        events = [
            {"home_team": "Manchester City FC", "away_team": "Fulham FC",
             "commence_time": "2026-11-21T12:30:00Z", "id": "matching"},
            {"home_team": "Manchester City FC", "away_team": "Fulham FC",
             "commence_time": "2026-11-22T12:30:00Z", "id": "wrong-date"},
        ]
        self.assertEqual(find_odds_event(fixture, events, "PL")["id"], "matching")

    def test_odds_event_rejects_ambiguous_same_day_double_match(self):
        fixture = {"home": "Manchester City", "away": "Fulham", "utcDate": "2026-11-21"}
        events = [
            {"home_team": "Manchester City FC", "away_team": "Fulham FC",
             "commence_time": "2026-11-21T12:30:00Z", "id": "one"},
            {"home_team": "Manchester City", "away_team": "Fulham",
             "commence_time": "2026-11-21T17:30:00Z", "id": "two"},
        ]
        self.assertIsNone(find_odds_event(fixture, events, "PL"))

    def test_fixture_identity_uses_competition_teams_and_kickoff(self):
        fixture = {"home": "Manchester United", "away": "Chelsea",
                   "utcDate": "2026-11-10T19:00:00Z"}
        self.assertEqual(
            fixture_identity(fixture, "PL"),
            ("PL", "manchester united", "chelsea", "2026-11-10T19:00"),
        )

    def test_odds_transfer_across_provider_id_change(self):
        import tempfile
        from pathlib import Path
        import update_data_pitchapi as updater

        with tempfile.TemporaryDirectory() as tmp:
            old_out = updater.OUT
            updater.OUT = Path(tmp) / "data.json"
            try:
                updater.OUT.write_text(__import__("json").dumps({
                    "competitions": [{"code": "PL", "matches": [{
                        "id": 123, "home": "Manchester United", "away": "Chelsea",
                        "utcDate": "2026-11-10T19:00:00Z", "status": "SCHEDULED",
                        "odds": {"best": {"h2h": {"home": 2.1}}}
                    }]}]
                }), encoding="utf-8")
                payload = {"competitions": [{"code": "PL", "matches": [{
                    "id": "pitchapi-xyz", "home": "Manchester United", "away": "Chelsea",
                    "utcDate": "2026-11-10T19:00:00Z", "status": "SCHEDULED"
                }]}]}
                self.assertEqual(preserve_odds_by_identity(payload), 1)
                self.assertEqual(payload["competitions"][0]["matches"][0]["odds"]["best"]["h2h"]["home"], 2.1)
            finally:
                updater.OUT = old_out

    def test_odds_transfer_when_pitchapi_fixture_has_date_only(self):
        import tempfile
        import json
        from pathlib import Path
        import update_data_pitchapi as updater

        with tempfile.TemporaryDirectory() as tmp:
            old_out = updater.OUT
            updater.OUT = Path(tmp) / "data.json"
            try:
                quote = {"best": {"h2h": {"home": 1.95}}}
                updater.OUT.write_text(json.dumps({
                    "competitions": [{"code": "PL", "matches": [{
                        "id": "old-id", "home": "Manchester City", "away": "Fulham",
                        "utcDate": "2026-11-21T15:00:00Z", "status": "SCHEDULED",
                        "odds": quote
                    }]}],
                    "oddsUpdatedAt": "2026-11-08T05:30:00Z",
                }), encoding="utf-8")
                payload = {"competitions": [{"code": "PL", "matches": [{
                    "id": "new-id", "home": "Manchester City", "away": "Fulham",
                    "utcDate": "2026-11-21", "status": "SCHEDULED"
                }]}]}
                self.assertEqual(preserve_odds_by_identity(payload), 1)
                match = payload["competitions"][0]["matches"][0]
                self.assertEqual(match["odds"], quote)
                self.assertEqual(payload["oddsCoverage"]["eligible"], 1)
                self.assertEqual(payload["oddsCoverage"]["retained"], 1)
                self.assertEqual(payload["oddsCoverage"]["notMatched"], 0)
            finally:
                updater.OUT = old_out

    def test_odds_transfer_reads_explicit_main_snapshot_path(self):
        import tempfile
        import json
        import os
        from pathlib import Path
        import update_data_pitchapi as updater

        with tempfile.TemporaryDirectory() as tmp:
            old_out = updater.OUT
            updater.OUT = Path(tmp) / "feature-data.json"
            source = Path(tmp) / "main-data.json"
            quote = {"best": {"h2h": {"home": 2.05}}}
            source.write_text(json.dumps({
                "competitions": [{"code": "PL", "matches": [{
                    "id": "main-fixture", "home": "Liverpool", "away": "Manchester United",
                    "utcDate": "2026-11-22T16:30:00Z", "status": "SCHEDULED",
                    "odds": quote
                }]}]
            }), encoding="utf-8")
            payload = {"competitions": [{"code": "PL", "matches": [{
                "id": "pitchapi-fixture", "home": "Liverpool", "away": "Manchester United",
                "utcDate": "2026-11-22", "status": "SCHEDULED"
            }]}]}
            try:
                with patch.dict(os.environ, {"PITCHAPI_PREVIOUS_DATA_PATH": str(source)}):
                    self.assertEqual(preserve_odds_by_identity(payload), 1)
                self.assertEqual(payload["competitions"][0]["matches"][0]["odds"], quote)
            finally:
                updater.OUT = old_out

    def test_strongest_signal_uses_only_displayed_yes_and_over_markets(self):
        from update_data import signal_candidates, strongest_signal

        fields = ("1x", "x2", "bttsYes", "over15", "over25", "over35",
                  "homeOver05", "homeOver15", "homeOver25", "homeOver35",
                  "awayOver05", "awayOver15", "awayOver25", "awayOver35")
        prediction = {field: 0.80 for field in fields}
        baseline = {field: 0.50 for field in fields}
        baseline["matches"] = 250
        candidates = signal_candidates(prediction, baseline, "Home", "Away")
        sides = {item["side"] for item in candidates}
        self.assertIn("1X", sides)
        self.assertIn("X2", sides)
        self.assertIn("ОЗ ДА", sides)
        self.assertIn("ТБ 2.5", sides)
        self.assertIn("ИТБ 1.5 · Home", sides)
        self.assertFalse(any("Не " in side or "ИТМ" in side or side.startswith("ТМ ")
                             or side in ("П1", "П2", "Ничья") for side in sides))
        best = strongest_signal(prediction, baseline, "Home", "Away")
        self.assertIn(best["side"], sides)

    def test_low_probability_markets_remain_candidates_for_match_level_ranking(self):
        from update_data import signal_candidates, strongest_signal

        fields = ("1x", "x2", "bttsYes", "over15", "over25", "over35",
                  "homeOver05", "homeOver15", "homeOver25", "homeOver35",
                  "awayOver05", "awayOver15", "awayOver25", "awayOver35")
        prediction = {field: 0.10 for field in fields}
        prediction["over25"] = 0.35
        baseline = {field: 0.50 for field in fields}
        baseline["matches"] = 250

        candidates = signal_candidates(prediction, baseline, "Home", "Away")
        self.assertEqual(len(candidates), 14)
        best = strongest_signal(prediction, baseline, "Home", "Away")
        self.assertEqual(best["side"], "ТБ 2.5")
        self.assertLess(best["selectionEdgePp"], 0.0)

    def test_historical_baselines_cover_all_displayed_yes_and_over_markets(self):
        from update_data import historical_baselines

        rows = []
        for i in range(120):
            rows.append({"comp": "eng", "date": pd.Timestamp("2026-01-01") + pd.Timedelta(days=i),
                         "home_score": i % 4, "away_score": (i // 2) % 3})
        matches = pd.DataFrame(rows)
        baseline = historical_baselines(matches, "eng", "2026-06-01")
        self.assertEqual(baseline["matches"], 120)
        for field in ("1x", "x2", "bttsYes", "over15", "over25", "over35",
                      "homeOver05", "homeOver15", "homeOver25", "homeOver35",
                      "awayOver05", "awayOver15", "awayOver25", "awayOver35"):
            self.assertGreater(baseline[field], 0.0)
            self.assertLess(baseline[field], 1.0)

    def test_sparse_league_baseline_is_shrunk_toward_other_leagues(self):
        from update_data import historical_baselines

        rows = []
        # Sparse target league: all eight matches ended 0-0.
        for i in range(8):
            rows.append({"comp": "eng", "date": pd.Timestamp("2026-01-01") + pd.Timedelta(days=i),
                         "home_score": 0, "away_score": 0})
        # Other leagues provide a pooled prior with a materially higher BTTS rate.
        for i in range(40):
            rows.append({"comp": "esp", "date": pd.Timestamp("2026-01-01") + pd.Timedelta(days=i),
                         "home_score": 2, "away_score": 1})
        matches = pd.DataFrame(rows)
        baseline = historical_baselines(matches, "eng", "2026-06-01")

        self.assertIsNotNone(baseline)
        self.assertEqual(baseline["matches"], 8)
        self.assertEqual(baseline["baselinePriorMatches"], 40)
        self.assertEqual(baseline["baselinePriorStrength"], 20.0)
        # The posterior must sit between the sparse league rate and pooled prior.
        self.assertGreater(baseline["bttsYes"], 8.0 / 22.0)
        self.assertLess(baseline["bttsYes"], 1.0)
        self.assertGreater(baseline["over15"], 0.5)

    def test_league_with_zero_history_uses_pooled_other_league_prior(self):
        from update_data import historical_baselines

        rows = []
        for i in range(40):
            rows.append({"comp": "esp", "date": pd.Timestamp("2026-01-01") + pd.Timedelta(days=i),
                         "home_score": 2, "away_score": 1})
        matches = pd.DataFrame(rows)
        baseline = historical_baselines(matches, "eng", "2026-06-01")

        self.assertIsNotNone(baseline)
        self.assertEqual(baseline["matches"], 0)
        self.assertEqual(baseline["baselinePriorMatches"], 40)
        self.assertEqual(baseline["baselinePriorStrength"], 20.0)
        self.assertGreater(baseline["bttsYes"], 0.5)
        self.assertLess(baseline["bttsYes"], 1.0)

    def test_zero_handicap_markets_are_not_calculated_or_signalled(self):
        probabilities = markets(1.4, 0.9)
        self.assertNotIn("dnbHome", probabilities)
        self.assertNotIn("dnbAway", probabilities)
        self.assertNotIn("ah0Home", probabilities)
        self.assertNotIn("ah0Away", probabilities)
        self.assertFalse(any(key.lower().startswith("dnb") for key in probabilities))
        from update_data import strongest_signal
        prediction = {**probabilities, "homeOver15": 0.6, "awayOver15": 0.4}
        baseline = {"1x": 0.5, "x2": 0.5, "homeOver15": 0.5, "awayOver15": 0.5, "matches": 200}
        signal = strongest_signal(prediction, baseline, "Home", "Away")
        self.assertNotIn("Фора 0", signal["market"])
        self.assertNotIn("Фора 0", signal["side"])
        self.assertNotIn("dnbHome", signal["market"])
        self.assertNotIn("dnbAway", signal["market"])


    def test_optional_odds_refresh_requests_full_supported_market_set(self):
        from update_data_pitchapi import refresh_odds_from_api
        class Response:
            headers = {"x-requests-remaining": "100", "x-requests-used": "5"}
            status_code = 200
            def raise_for_status(self):
                pass
            def json(self):
                return []

        payload = {"competitions": [
            {"code": code, "matches": []}
            for code in ("PL", "PD", "SA", "BL1", "FL1")
        ]}
        with patch("update_data_pitchapi.requests.get", return_value=Response()) as get, \
             patch("update_data_pitchapi.time.sleep"):
            self.assertEqual(refresh_odds_from_api(payload, "test-key"), 0)

        self.assertEqual(get.call_count, 5)
        for call in get.call_args_list:
            self.assertEqual(
                call.kwargs["params"]["markets"],
                "h2h,totals,spreads,double_chance,btts",
            )


if __name__ == "__main__":
    unittest.main()
