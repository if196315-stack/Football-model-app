import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diagnose_betboom_team_totals import extract_match, summarize, is_team_total_market_slug


class BetBoomTeamTotalDiagnosticTests(unittest.TestCase):
    def test_extracts_match_from_detail_envelope(self):
        match = {"id": 7}
        self.assertIs(extract_match({"match": match}), match)

    def test_summarizes_betboom_team_total_markets_and_flags(self):
        match = {
            "id": 16363661,
            "homeTeam": {"name": "Manchester City"},
            "awayTeam": {"name": "Fulham"},
            "hasBkOdds": {"betboom": True},
            "oddsBk": {"betboom": {"markets": {
                "total_team_1": {
                    "status": "open", "stage": "prematch",
                    "stakes": {"over": {"lines": [
                        {"argument": 1.5, "factor": 1.91}
                    ]}},
                },
                "total_goals": {
                    "status": "open", "stage": "prematch",
                    "stakes": {"over": {"factor": 1.8}},
                },
            }}},
        }
        result = summarize(match)
        self.assertTrue(result["hasBkOddsBetboom"])
        self.assertEqual(len(result["teamTotalMarkets"]), 1)
        self.assertEqual(
            result["teamTotalMarkets"][0]["stakes"]["over"][0],
            {"line": 1.5, "factor": 1.91, "suspended": False},
        )

    def test_reports_missing_board_without_crashing(self):
        result = summarize({"id": 99, "hasBkOdds": {"betboom": False}})
        self.assertFalse(result["hasBetboomBoard"])
        self.assertEqual(result["teamTotalMarkets"], [])

    def test_accepts_explicit_team_total_market_slugs(self):
        for slug in (
            "total_team_1", "total_team_2",
            "first_half_total_team_1", "second_half_total_team_2",
            "1st_half_total_team_1", "2nd_half_total_team_2",
        ):
            with self.subTest(slug=slug):
                self.assertTrue(is_team_total_market_slug(slug))

    def test_rejects_btts_and_generic_team_word_markets(self):
        for slug in ("btts", "both_teams_to_score", "team_goals_home",
                     "team_total_home_goals", "total_goals", "teams_total"):
            with self.subTest(slug=slug):
                self.assertFalse(is_team_total_market_slug(slug))

    def test_summary_never_classifies_btts_as_team_total(self):
        match = {"id": 1, "oddsBk": {"betboom": {"markets": {
            "btts": {"name": {"en": "Both Teams to Score"},
                     "status": "open", "stage": "prematch",
                     "stakes": {"yes": {"factor": 1.67}}},
            "total_team_1": {"status": "open", "stage": "prematch",
                             "stakes": {"over": {"factor": 1.4}}},
        }}}}
        result = summarize(match)
        self.assertEqual([m["slug"] for m in result["teamTotalMarkets"]],
                         ["total_team_1"])


if __name__ == "__main__":
    unittest.main()
