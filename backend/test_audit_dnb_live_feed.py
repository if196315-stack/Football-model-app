import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_dnb_live_feed import build_report


class DnbLiveFeedAuditTests(unittest.TestCase):
    def fixture(self, markets):
        return {
            "id": 77,
            "startTimestamp": 1795287600000,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"melbet": {
                "updatedAtByStage": {"prematch": 1795287500000},
                "markets": markets,
            }},
        }

    def test_reports_paired_zero_lines_and_excludes_nonzero_lines(self):
        report = build_report([self.fixture({
            "handicap": {"name": {"en": "Asian Handicap"},
                         "status": "open", "stage": "prematch", "stakes": {
                "handicap_1": {"lines": [
                    {"argument": 0, "factor": 1.91},
                    {"argument": -0.5, "factor": 2.1},
                ]},
                "handicap_2": {"lines": [{"argument": 0, "factor": 1.95}]},
            }},
        })], "2026-11-12T18:00:00Z")
        self.assertEqual(report["counts"]["complete_home_away_pairs"], 1)
        self.assertEqual(report["counts"]["zero_line_quotes"], 2)
        event = report["events_with_handicap_data"][0]
        self.assertEqual({row["team"] for row in event["parsed_zero_handicap_quotes"]},
                         {"home", "away"})
        self.assertEqual({row["point"] for row in event["parsed_zero_handicap_quotes"]}, {0.0})
        self.assertEqual(event["freshness_status"], "fresh")

    def test_reports_incomplete_side_pair_instead_of_assuming_both_prices(self):
        report = build_report([self.fixture({
            "handicap": {"name": {"en": "Asian Handicap"},
                         "status": "open", "stage": "prematch", "stakes": {
                "handicap_1": {"lines": [{"argument": 0, "factor": 1.91}]},
            }},
        })])
        self.assertEqual(report["counts"]["complete_home_away_pairs"], 0)
        self.assertEqual(report["counts"]["incomplete_pairs"], 1)

    def test_excludes_live_and_first_half_handicap_markets(self):
        report = build_report([self.fixture({
            "first_half_handicap": {"name": {"en": "First Half Handicap"},
                "status": "open", "stage": "prematch", "stakes": {
                    "handicap_1": {"lines": [{"argument": 0, "factor": 1.9}]}}},
            "live_handicap": {"name": {"en": "Asian Handicap"},
                "status": "open", "stage": "live", "stakes": {
                    "handicap_1": {"lines": [{"argument": 0, "factor": 1.9}]}}},
        })])
        self.assertEqual(report["counts"]["open_prematch_fulltime_handicap_markets"], 0)
        self.assertEqual(report["counts"]["zero_line_quotes"], 0)
        self.assertEqual(report["events_with_handicap_data"], [])


if __name__ == "__main__":
    unittest.main()
