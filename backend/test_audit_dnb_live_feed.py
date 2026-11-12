import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_dnb_live_feed import build_report, map_model_predictions


class DnbLiveFeedAuditTests(unittest.TestCase):
    def fixture(self, markets):
        return {
            "id": 77,
            "startTimestamp": 1795287600000,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"melbet": {
                "updatedAtByStage": {"prematch": int(time.time() * 1000)},
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



    def test_maps_predictions_to_provider_ids_with_collector_fixture_match_rules(self):
        cache = {"matches": {
            "pitchapi-1": {"id": "pitchapi-1", "date": "2026-11-20T20:00:00Z",
                           "home": "Arsenal", "away": "Chelsea", "leagueCode": "UCL"},
        }}
        provider = [{
            "id": 777, "startTimestamp": 1795204800000,
            "homeTeam": {"name": "Arsenal"}, "awayTeam": {"name": "Chelsea"},
        }]
        prediction = {"markets": {"handicap_zero": {
            "home": {"win": 0.45, "push": 0.25, "lose": 0.30},
            "away": {"win": 0.30, "push": 0.25, "lose": 0.45},
        }}}
        mapped = map_model_predictions(cache, provider, {"pitchapi-1": prediction})
        self.assertEqual(mapped, {"777": prediction})

    def test_calculates_dnb_no_vig_and_experimental_calibrated_edge(self):
        fixture = self.fixture({
            "handicap": {"name": {"en": "Asian Handicap"},
                         "status": "open", "stage": "prematch", "stakes": {
                "handicap_1": {"lines": [{"argument": 0, "factor": 1.91}]},
                "handicap_2": {"lines": [{"argument": 0, "factor": 1.95}]},
            }},
        })
        predictions = {"77": {"markets": {"handicap_zero": {
            "home": {"win": 0.45, "push": 0.25, "lose": 0.30},
            "away": {"win": 0.30, "push": 0.25, "lose": 0.45},
        }}}}
        calibrator = {"available": True, "classes": ["W", "L"],
                      "temperature": 1.0, "bias": {"W": 0.0, "L": 0.0}}
        report = build_report([fixture], model_predictions=predictions,
                              dnb_calibrator=calibrator)
        pair = report["events_with_handicap_data"][0]["dnb_market_analysis"][0]
        self.assertAlmostEqual(pair["overround"], 1/1.91 + 1/1.95)
        self.assertAlmostEqual(pair["no_vig_home_probability"] +
                               pair["no_vig_away_probability"], 1.0)
        self.assertAlmostEqual(pair["raw_model_home_win_given_decisive"], 0.60)
        self.assertAlmostEqual(pair["calibrated_home_win_given_decisive_experimental"], 0.60)
        self.assertAlmostEqual(pair["home_model_ev_pct_raw"], 10.95)
        self.assertGreater(pair["home_model_edge_vs_no_vig_pp_experimental"], 0.0)

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
