import unittest
from datetime import datetime, timezone

from update_uefa_xg_history import update_history


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
            "domestic": {"id": "domestic", "leagueCode": "PL", "status": "finished"},
        }}
        history = {"schema_version": 1, "matches": [{
            "id": "recent", "pairedXg": False,
            "lastCheckedAt": "2026-11-10T11:00:00+00:00",
        }]}
        calls = []
        def fetch(match_id):
            calls.append(match_id)
            home_id, away_id = ("h2", "a2") if match_id == "uecl" else ("h", "a")
            return {"shots": [
                {"team_id": home_id, "expected_goals": 0.6},
                {"team_id": away_id, "expected_goals": 0.2},
            ]}
        updated, summary = update_history(cache, history, fetch, now)
        self.assertEqual(calls, ["new", "uecl"])
        self.assertEqual(summary["attempted"], 2)
        self.assertEqual(summary["added_or_updated"], 2)
        self.assertEqual(summary["skipped_recent_unpaired"], 1)
        new = next(row for row in updated["matches"] if row["id"] == "new")
        self.assertTrue(new["pairedXg"])
        self.assertEqual(new["homeXg"], 0.6)
        self.assertEqual(new["awayScore"], 0)
        uecl = next(row for row in updated["matches"] if row["id"] == "uecl")
        self.assertEqual(uecl["leagueCode"], "UECL")
        self.assertTrue(uecl["pairedXg"])
        self.assertNotIn("unl", {row["id"] for row in updated["matches"] if row.get("pairedXg")})


if __name__ == "__main__":
    unittest.main()
