import copy
import unittest

from soccer365_quality import validate_match_records


def record(**overrides):
    value = {
        "id": "s365-100",
        "home": "Alpha FC",
        "away": "Beta FC",
        "competitionId": "eng-prem",
        "competition": "Premier League",
        "competitionType": "league",
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

    def test_possible_duplicate_fixture_with_different_ids_is_reported(self):
        first = record()
        second = record(id="s365-101")
        result = validate_match_records([first, second])
        self.assertTrue(result["valid"])
        self.assertIn("possible_duplicate_fixture", [x["code"] for x in result["issues"]])

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
        result = validate_match_records([record(competitionId=None, competition=None,
                                                utcDate=None, localKickoff=None)])
        codes = [x["code"] for x in result["issues"]]
        self.assertIn("missing_competition", codes)
        self.assertIn("missing_kickoff", codes)

    def test_finished_without_score_is_warning(self):
        result = validate_match_records([record(homeScore=None, awayScore=None)])
        self.assertIn("finished_without_score", [x["code"] for x in result["issues"]])
        self.assertTrue(result["valid"])

    def test_same_team_on_both_sides_is_error(self):
        result = validate_match_records([record(away="Alpha FC")])
        self.assertIn("same_home_away", [x["code"] for x in result["issues"]])

    def test_xg_availability_flag_must_match_pair(self):
        result = validate_match_records([record(
            statistics={"homeXg": 1.2, "awayXg": 0.8, "xgAvailable": False}
        )])
        self.assertIn("xg_flag_mismatch", [x["code"] for x in result["issues"]])


if __name__ == "__main__":
    unittest.main()
