import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_market_roi import build, market_key, market_result, select_quotes


class MarketRoiTests(unittest.TestCase):
    def setUp(self):
        self.captured = "2026-11-10T12:00:00+00:00"
        self.domestic = {"predictionArchive": {"matches": {
            "101": {
                "predictionCapturedAt": "2026-11-10T11:00:00+00:00",
                "prediction": {
                    "1x": 0.50, "x2": 0.49, "over25": 0.60,
                    "homeOver15": 0.70, "awayOver15": 0.55,
                    "homeOver05": 0.80, "awayOver05": 0.50,
                    "bttsYes": 0.65,
                },
                "home": "Alpha", "away": "Beta", "utcDate": "2026-11-20",
            }
        }}}
        self.uefa = {"predictionArchive": {"matches": {
            "UCL:202": {
                "predictionCapturedAt": "2026-11-10T11:00:00+00:00",
                "prediction": {"1x": 0.7},
                "home": "Gamma", "away": "Delta", "utcDate": "2026-11-20",
            }
        }}}

    def snapshot(self, market, **fields):
        return {
            "league_code": "ENG", "fixture_id": "101", "fixture_namespace": "DOM",
            "captured_at": self.captured, "bookmaker_updated_at": self.captured,
            "bookmaker": "MELBET", "source": "API-Sport",
            "prematch_active": True, "freshness_status": "fresh",
            "market_parser_version": "melbet-fulltime-v2",
            "candidate_market": True, "reference_only": False,
            "market": market, "price": 1.9, **fields,
        }

    def test_maps_exact_eight_summary_markets(self):
        cases = [
            (self.snapshot("double_chance", outcome="1x"), "1x"),
            (self.snapshot("double_chance", outcome="x2"), "x2"),
            (self.snapshot("match_total_over", side="over", point=2.5), "over25"),
            (self.snapshot("team_total_over", team="home", side="over", point=1.5), "homeOver15"),
            (self.snapshot("team_total_over", team="away", side="over", point=1.5), "awayOver15"),
            (self.snapshot("team_total_over", team="home", side="over", point=0.5), "homeOver05"),
            (self.snapshot("team_total_over", team="away", side="over", point=0.5), "awayOver05"),
            (self.snapshot("btts", outcome="yes"), "bttsYes"),
        ]
        self.assertEqual([market_key(row) for row, _ in cases], [key for _, key in cases])

    def test_freezes_one_quote_per_market_and_includes_probability_exactly_50_percent(self):
        rows = [
            self.snapshot("double_chance", outcome="1x"),
            self.snapshot("double_chance", outcome="x2"),
            self.snapshot("match_total_over", side="over", point=2.5),
            self.snapshot("team_total_over", team="home", side="over", point=1.5),
            self.snapshot("team_total_over", team="away", side="over", point=1.5),
            self.snapshot("team_total_over", team="home", side="over", point=0.5),
            self.snapshot("team_total_over", team="away", side="over", point=0.5),
            self.snapshot("btts", outcome="yes"),
            # Unsupported lines, reference prices and stale/non-MelBet rows must not leak in.
            self.snapshot("match_total_over", side="over", point=1.5),
            self.snapshot("double_chance", outcome="12"),
        ]
        selected = select_quotes(rows, self.domestic, self.uefa, [])
        self.assertEqual(len(selected), 7)  # x2 is below 50%; the others qualify.
        self.assertIn("1x", {row["market_key"] for row in selected})
        self.assertNotIn("x2", {row["market_key"] for row in selected})
        self.assertTrue(all(row["price"] == 1.9 for row in selected))
        # Repeated snapshots never multiply the same forecast-market.
        selected_again = select_quotes(rows + rows, self.domestic, self.uefa, selected)
        self.assertEqual(len(selected_again), len(selected))

    def test_rejects_quotes_before_frozen_prediction_and_invalid_board_state(self):
        before = self.snapshot("double_chance", outcome="1x")
        before["captured_at"] = "2026-11-10T10:59:59+00:00"
        stale = self.snapshot("double_chance", outcome="1x")
        stale["freshness_status"] = "stale"
        inactive = self.snapshot("double_chance", outcome="1x")
        inactive["prematch_active"] = False
        self.assertEqual(select_quotes([before, stale, inactive], self.domestic, self.uefa, []), [])

    def test_only_settled_forecasts_contribute_one_unit_roi(self):
        quote = {
            "league_code": "ENG", "fixture_id": "101", "market_key": "1x",
            "price": 2.0, "kickoff_utc": "2026-11-20",
        }
        domestic = {
            **self.domestic,
            "evaluationMatches": [{
                "id": "101", "status": "FINISHED", "homeScore": 2, "awayScore": 1,
            }],
        }
        report, _ = build(domestic, {}, [], [quote])
        self.assertEqual(report["summary"]["settled"], 1)
        self.assertEqual(report["summary"]["wins"], 1)
        self.assertEqual(report["summary"]["profitUnits"], 1.0)
        self.assertEqual(report["summary"]["turnoverUnits"], 1)
        self.assertEqual(report["summary"]["roi"], 100.0)
        self.assertEqual(report["records"][0]["status"], "SETTLED")

    def test_pending_and_missing_quotes_are_not_in_roi_denominator(self):
        quote = {"league_code": "ENG", "fixture_id": "101", "market_key": "1x", "price": 1.8}
        report, _ = build(self.domestic, {}, [], [quote])
        self.assertEqual(report["summary"]["settled"], 0)
        self.assertEqual(report["summary"]["roi"], None)
        self.assertEqual(report["summary"]["pending"], 1)

    def test_market_result_semantics(self):
        self.assertEqual(market_result({"market_key": "1x"}, 1, 1), "WIN")
        self.assertEqual(market_result({"market_key": "x2"}, 2, 0), "LOSS")
        self.assertEqual(market_result({"market_key": "over25"}, 1, 2), "WIN")
        self.assertEqual(market_result({"market_key": "homeOver15"}, 2, 0), "WIN")
        self.assertEqual(market_result({"market_key": "awayOver05"}, 1, 0), "LOSS")
        self.assertEqual(market_result({"market_key": "bttsYes"}, 1, 1), "WIN")


if __name__ == "__main__":
    unittest.main()
