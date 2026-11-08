import unittest
from unittest.mock import patch

import pandas as pd

from update_data_pitchapi import (
    app_status, build_model_frames, fixture_identity, preserve_odds_by_identity,
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
        self.assertEqual(len(history), 2)
        self.assertIsNone(history.date.dt.tz)
        self.assertIsNone(results.date.dt.tz)
        self.assertEqual(len(results), 1)
        self.assertEqual(set(history.team), {"Arsenal", "Chelsea"})
        self.assertAlmostEqual(base["eng", "home"], 1.4)
        self.assertAlmostEqual(base["eng", "away"], 0.4)

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

if __name__ == "__main__":
    unittest.main()
