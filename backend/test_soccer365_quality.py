import copy
import unittest

from soccer365_quality import summarize_model_coverage, validate_match_records


def record(**overrides):
    value = {
        "id": "s365-100",
        "home": "Alpha FC",
        "away": "Beta FC",
        "competitionId": "eng-prem",
        "competition": "Premier League",
        "competitionType": "league",
        "season": "2026-2027",
        "utcDate": "2026-08-01T15:00:00+00:00",
        "status": "FINISHED",
        "homeScore": 2,
        "awayScore": 1,
        "statistics": {"homeXg": 1.2, "awayXg": 0.8, "xgAvailable": True},
    }
    value.update(overrides)
    return value


class Soccer365QualityTests(unittest.TestCase):
    def test_clean_record_is_valid(self):
        result = validate_match_records([record()])
        self.assertTrue(result["valid"])
        self.assertEqual(result["errorCount"], 0)
        self.assertEqual(result["warningCount"], 0)

    def test_duplicate_ids_are_errors(self):
        result = validate_match_records([record(), record()])
        self.assertFalse(result["valid"])
        self.assertIn("duplicate_match_id", [x["code"] for x in result["issues"]])

    def test_near_time_duplicate_with_different_ids_is_reported(self):
        first = record()
        second = record(id="s365-101", utcDate="2026-08-01T15:04:00+00:00")
        result = validate_match_records([first, second])
        self.assertTrue(result["valid"])
        self.assertIn("possible_duplicate_fixture", [x["code"] for x in result["issues"]])

    def test_different_seasons_are_not_flagged_as_duplicates(self):
        result = validate_match_records([
            record(),
            record(id="s365-102", season="2025-2026"),
        ])
        self.assertNotIn("possible_duplicate_fixture", [x["code"] for x in result["issues"]])

    def test_stable_team_ids_prevent_same_name_false_merge(self):
        result = validate_match_records([
            record(homeTeamId="team-1", awayTeamId="team-2"),
            record(id="s365-103", homeTeamId="team-3", awayTeamId="team-4"),
        ])
        self.assertNotIn("possible_duplicate_fixture", [x["code"] for x in result["issues"]])

    def test_different_competitions_are_not_flagged_as_duplicates(self):
        result = validate_match_records([
            record(),
            record(id="s365-101", competitionId="eng-cup", competitionType="cup"),
        ])
        self.assertNotIn("possible_duplicate_fixture", [x["code"] for x in result["issues"]])

    def test_missing_xg_stays_valid_and_is_not_zero_filled(self):
        item = record(statistics={"homeXg": None, "awayXg": None, "xgAvailable": False})
        before = copy.deepcopy(item)
        result = validate_match_records([item])
        self.assertTrue(result["valid"])
        self.assertEqual(item, before)

    def test_partial_xg_is_warning_not_repaired(self):
        result = validate_match_records([record(
            statistics={"homeXg": 1.1, "awayXg": None, "xgAvailable": False}
        )])
        codes = [x["code"] for x in result["issues"]]
        self.assertIn("partial_xg_pair", codes)
        self.assertTrue(result["valid"])

    def test_invalid_score_and_xg_are_errors(self):
        result = validate_match_records([record(
            homeScore=-1,
            statistics={"homeXg": 22, "awayXg": 0.8, "xgAvailable": True},
        )])
        codes = [x["code"] for x in result["issues"]]
        self.assertIn("invalid_score", codes)
        self.assertIn("invalid_xg", codes)
        self.assertFalse(result["valid"])

    def test_missing_competition_and_kickoff_are_warnings(self):
        result = validate_match_records([record(competitionId=None,
                                                utcDate=None, localKickoff=None)])
        codes = [x["code"] for x in result["issues"]]
        self.assertIn("missing_competition_id", codes)
        self.assertIn("missing_kickoff", codes)

    def test_missing_season_and_competition_type_are_warnings(self):
        result = validate_match_records([record(season=None, competitionType=None)])
        codes = [x["code"] for x in result["issues"]]
        self.assertIn("missing_season", codes)
        self.assertIn("missing_competition_type", codes)

    def test_naive_utc_datetime_is_not_model_ready(self):
        result = validate_match_records([record(utcDate="2026-08-01T15:00:00")])
        self.assertIn("invalid_utc_kickoff", [x["code"] for x in result["issues"]])

    def test_finished_without_score_is_warning(self):
        result = validate_match_records([record(homeScore=None, awayScore=None)])
        self.assertIn("finished_without_score", [x["code"] for x in result["issues"]])
        self.assertTrue(result["valid"])

    def test_nonfinished_match_with_score_is_warning(self):
        result = validate_match_records([record(status="SCHEDULED")])
        self.assertIn("score_on_nonfinished_match", [x["code"] for x in result["issues"]])

    def test_same_team_on_both_sides_is_error(self):
        result = validate_match_records([record(away="Alpha FC")])
        self.assertIn("same_home_away", [x["code"] for x in result["issues"]])


    def test_model_coverage_keeps_competition_types_separate(self):
        rows = []
        for i in range(100):
            rows.append(record(
                id=f"league-{i}",
                utcDate=f"2026-08-{(i % 28) + 1:02d}T15:00:00+00:00",
                homeTeamId="team-a",
                awayTeamId="team-b",
                competitionType="league",
                home="Alpha FC",
                away="Beta FC",
            ))
        rows.append(record(
            id="cup-1",
            competitionId="eng-cup",
            competitionType="cup",
            homeTeamId="team-a",
            awayTeamId="team-b",
        ))
        summary = summarize_model_coverage(rows, min_team_xg_matches=30,
                                           min_competition_baseline_matches=100)
        self.assertEqual(len(summary["competitions"]), 2)
        league = next(x for x in summary["competitions"] if x["competitionType"] == "league")
        cup = next(x for x in summary["competitions"] if x["competitionType"] == "cup")
        self.assertTrue(league["baselineReady"])
        self.assertFalse(cup["baselineReady"])
        self.assertEqual(league["finishedWithPairedXg"], 100)

    def test_model_coverage_does_not_count_duplicate_ids_twice(self):
        rows = [record(), record()]
        summary = summarize_model_coverage(rows, min_team_xg_matches=1,
                                           min_competition_baseline_matches=1)
        self.assertEqual(summary["competitions"][0]["finishedWithResult"], 1)
        self.assertEqual(summary["competitions"][0]["finishedWithPairedXg"], 1)

    def test_xg_availability_flag_must_match_pair(self):
        result = validate_match_records([record(
            statistics={"homeXg": 1.2, "awayXg": 0.8, "xgAvailable": False}
        )])
        self.assertIn("xg_flag_mismatch", [x["code"] for x in result["issues"]])

    def test_extremely_large_integer_xg_is_rejected_without_crashing(self):
        result = validate_match_records([record(statistics={
            "homeXg": 10 ** 1000, "awayXg": 0.5, "xgAvailable": True,
        })])
        self.assertIn("invalid_xg", [item["code"] for item in result["issues"]])


if __name__ == "__main__":
    unittest.main()
