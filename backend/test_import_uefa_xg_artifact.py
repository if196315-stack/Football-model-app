import unittest

from import_uefa_xg_artifact import build_history


class UefaXgArtifactImportTests(unittest.TestCase):
    def test_joins_paired_xg_to_finished_fixture_scores(self):
        report = {
            "generatedAt": "2026-11-09T00:00:00Z",
            "results": [
                {"id": "m1", "requestStatus": "ok", "pairedXg": True,
                 "homeXg": 1.2, "awayXg": 0.7},
                {"id": "m2", "requestStatus": "ok", "pairedXg": False,
                 "homeXg": 1.0, "awayXg": None},
            ],
        }
        fixtures = {"updatedAt": "2026-11-09T01:00:00Z", "matches": {
            "m1": {"id": "m1", "leagueCode": "UCL", "season": "2026/2027",
                   "date": "2026-11-05", "status": "finished",
                   "home": "Home", "away": "Away", "homeTeamId": "h", "awayTeamId": "a",
                   "homeScore": 2, "awayScore": 1},
            "m2": {"id": "m2", "leagueCode": "UEL", "season": "2026/2027",
                   "date": "2026-11-05", "status": "finished",
                   "home": "Other", "away": "Team", "homeScore": 0, "awayScore": 0},
            "m3": {"id": "m3", "leagueCode": "UCL", "season": "2026/2027",
                   "date": "2026-11-25", "status": "not_started",
                   "home": "Future", "away": "Team"},
        }}
        result = build_history(report, fixtures)
        self.assertEqual(len(result["matches"]), 1)
        row = result["matches"][0]
        self.assertEqual(row["id"], "m1")
        self.assertEqual(row["homeScore"], 2)
        self.assertEqual(row["awayXg"], 0.7)
        self.assertEqual(result["finished_matches_missing_paired_xg"], 1)
        self.assertEqual(result["finished_matches_missing_scores"], 0)


if __name__ == "__main__":
    unittest.main()
