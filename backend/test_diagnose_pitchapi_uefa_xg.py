import tempfile
import unittest
from pathlib import Path
from diagnose_pitchapi_uefa_xg import summarize_shots, select_sample, build_report, write_report


class XgDiagnosticTests(unittest.TestCase):
    def test_shot_payload_and_team_mapping(self):
        data = {"shots": [
            {"team_id": "h", "expected_goals": 0.2},
            {"team_id": "a", "expected_goals": 0.3},
            {"team_id": "h", "expected_goals": None},
            {"team_id": "other", "expected_goals": 0.9},
            {"team_id": "h", "expected_goals": True},
        ]}
        result = summarize_shots(data, "h", "a")
        self.assertEqual(result["validShots"], 2)
        self.assertEqual(result["homeXg"], 0.2)
        self.assertEqual(result["awayXg"], 0.3)
        self.assertTrue(result["pairedXg"])

    def test_period_payload(self):
        result = summarize_shots(
            {"periods": [{"shots": [
                {"team_id": "h", "expected_goals": 0.1},
                {"team_id": "a", "expected_goals": 0.2},
            ]}]},
            "h", "a",
        )
        self.assertEqual(result["validShots"], 2)
        self.assertTrue(result["pairedXg"])

    def test_empty_shots_are_unknown_not_zero(self):
        result = summarize_shots({"shots": []}, "h", "a")
        self.assertIsNone(result["homeXg"])
        self.assertIsNone(result["awayXg"])
        self.assertFalse(result["pairedXg"])

    def test_default_selects_all_finished_fixtures(self):
        rows = [
            {"id": str(i), "leagueCode": "UCL", "season": "2025/2026",
             "status": "finished", "homeTeamId": "h", "awayTeamId": "a",
             "date": str(i)}
            for i in range(20)
        ]
        rows.extend([
            {"id": "future", "status": "not_started", "homeTeamId": "h", "awayTeamId": "a"},
            {"id": "missing-team", "status": "finished", "homeTeamId": None, "awayTeamId": "a"},
        ])
        selected = select_sample(rows)
        self.assertEqual(len(selected), 20)
        self.assertEqual(len(select_sample(rows, limit=5)), 5)
        self.assertTrue(all(row["status"] == "finished" for row in selected))

    def test_report_has_group_coverage_and_error_counts(self):
        rows = [{"id": "1", "leagueCode": "UCL", "season": "2025/2026",
                 "status": "finished", "homeTeamId": "h", "awayTeamId": "a"}]
        results = [{"id": "1", "leagueCode": "UCL", "season": "2025/2026",
                    "requestStatus": "ok", "validShots": 2, "pairedXg": True}]
        report = build_report(rows, results, [], 0, True)
        self.assertEqual(report["eligibleFinishedMatches"], 1)
        self.assertEqual(report["matchesWithPairedXg"], 1)
        self.assertEqual(report["coverageByCompetitionSeason"]["UCL:2025/2026"]["validShots"], 2)


if __name__ == "__main__":
    unittest.main()
