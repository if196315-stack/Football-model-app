import unittest
from datetime import datetime, timezone

from update_uefa_xg_history import history_report_metadata, update_history


class UefaXgHistoryUpdateTests(unittest.TestCase):
    def test_updates_only_new_finished_uefa_matches_and_retries_unpaired_later(self):
        now = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)
        cache = {"matches": {
            "new": {"id": "new", "leagueCode": "UCL", "season": "2026/2027",
                    "date": "2026-11-09", "status": "finished",
                    "home": "Home", "away": "Away", "homeTeamId": "h", "awayTeamId": "a",
                    "homeScore": 2, "awayScore": 0},
            "recent": {"id": "recent", "leagueCode": "UEL", "season": "2026/2027",
                       "date": "2026-11-08", "status": "finished",
                       "home": "A", "away": "B", "homeTeamId": "ha", "awayTeamId": "ab",
                       "homeScore": 0, "awayScore": 0},
            "uecl": {"id": "uecl", "leagueCode": "UECL", "provider": "PitchAPI",
                     "date": "2026-11-09", "status": "finished",
                     "home": "UECL Home", "away": "UECL Away", "homeTeamId": "h2", "awayTeamId": "a2",
                     "homeScore": 1, "awayScore": 1},
            "unl": {"id": "unl", "leagueCode": "UNL", "provider": "API-Sport",
                    "date": "2026-11-09", "status": "finished",
                    "home": "UNL Home", "away": "UNL Away", "homeTeamId": "api-h", "awayTeamId": "api-a",
                    "homeScore": 1, "awayScore": 1},
            "unl_pitchapi": {"id": "unl_pitchapi", "leagueCode": "UNL", "provider": "PitchAPI",
                            "date": "2026-11-09", "status": "finished",
                            "home": "PitchAPI Home", "away": "PitchAPI Away",
                            "homeTeamId": "ph", "awayTeamId": "pa",
                            "homeScore": 2, "awayScore": 1},
            "domestic": {"id": "domestic", "leagueCode": "PL", "status": "finished"},
        }}
        history = {"schema_version": 1, "matches": [{
            "id": "recent", "pairedXg": False,
            "lastCheckedAt": "2026-11-10T11:00:00+00:00",
        }]}
        calls = []
        def fetch(match_id):
            calls.append(match_id)
            home_id, away_id = (("h2", "a2") if match_id == "uecl" else
                               ("ph", "pa") if match_id == "unl_pitchapi" else ("h", "a"))
            return {"shots": [
                {"team_id": home_id, "expected_goals": 0.6},
                {"team_id": away_id, "expected_goals": 0.2},
            ]}
        updated, summary = update_history(cache, history, fetch, now)
        self.assertEqual(calls, ["new", "uecl", "unl_pitchapi"])
        self.assertEqual(summary["attempted"], 3)
        self.assertEqual(summary["added_or_updated"], 3)
        self.assertEqual(summary["skipped_recent_unpaired"], 1)
        new = next(row for row in updated["matches"] if row["id"] == "new")
        self.assertTrue(new["pairedXg"])
        self.assertEqual(new["homeXg"], 0.6)
        self.assertEqual(new["awayScore"], 0)
        uecl = next(row for row in updated["matches"] if row["id"] == "uecl")
        self.assertEqual(uecl["leagueCode"], "UECL")
        self.assertTrue(uecl["pairedXg"])
        self.assertNotIn("unl", {row["id"] for row in updated["matches"] if row.get("pairedXg")})
        unl = next(row for row in updated["matches"] if row["id"] == "unl_pitchapi")
        self.assertEqual(unl["leagueCode"], "UNL")
        self.assertTrue(unl["pairedXg"])


    def test_report_metadata_is_rebuilt_from_fixture_cache_and_paired_rows(self):
        cache = {"matches": {
            "paired": {"id": "paired", "leagueCode": "UECL", "provider": "PitchAPI",
                       "status": "finished", "homeScore": 2, "awayScore": 1},
            "missing_xg": {"id": "missing_xg", "leagueCode": "UCL",
                           "status": "finished", "homeScore": 0, "awayScore": 0},
            "missing_score": {"id": "missing_score", "leagueCode": "UEL",
                              "status": "finished", "homeScore": None, "awayScore": None},
            "api_sport": {"id": "api_sport", "leagueCode": "UNL", "provider": "API-Sport",
                          "status": "finished", "homeScore": 1, "awayScore": 0},
            "scheduled": {"id": "scheduled", "leagueCode": "UNL",
                          "status": "scheduled"},
            "domestic": {"id": "domestic", "leagueCode": "PL", "status": "finished"},
        }}
        rows = [{"id": "paired", "pairedXg": True, "homeXg": 1.2, "awayXg": 0.7}]
        metadata = history_report_metadata(cache, rows, "2026-11-11T12:00:00+00:00")
        self.assertEqual(metadata, {
            "fixture_cache_matches": 6,
            "xg_report_generated_at": "2026-11-11T12:00:00+00:00",
            "finished_matches_with_paired_xg_and_scores": 1,
            "finished_matches_missing_paired_xg": 1,
            "finished_matches_missing_scores": 1,
        })

    def test_update_history_refreshes_stale_report_counters(self):
        now = datetime(2026, 11, 11, 12, 0, tzinfo=timezone.utc)
        cache = {"matches": {
            "finished": {"id": "finished", "leagueCode": "UECL", "status": "finished",
                         "homeScore": 1, "awayScore": 0},
        }}
        history = {
            "fixture_cache_matches": 1840,
            "finished_matches_with_paired_xg_and_scores": 1693,
            "finished_matches_missing_paired_xg": 3,
            "finished_matches_missing_scores": 0,
            "matches": [{"id": "finished", "pairedXg": True, "homeXg": 0.9, "awayXg": 0.2}],
        }
        updated, _ = update_history(cache, history, lambda _: None, now)
        self.assertEqual(updated["fixture_cache_matches"], 1)
        self.assertEqual(updated["finished_matches_with_paired_xg_and_scores"], 1)
        self.assertEqual(updated["finished_matches_missing_paired_xg"], 0)
        self.assertEqual(updated["finished_matches_missing_scores"], 0)
        self.assertEqual(updated["xg_report_generated_at"], now.isoformat())


if __name__ == "__main__":
    unittest.main()
