import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from backtest_all_markets import (apply_vector_scaling, fit_vector_scaling, market_outcomes, market_probs, run)


class AllMarketBacktestTests(unittest.TestCase):
    def test_market_probabilities_are_valid(self):
        probabilities = market_probs(1.35, 0.95)
        self.assertAlmostEqual(sum(probabilities["1X2"].values()), 1.0, places=8)
        self.assertAlmostEqual(probabilities["DC_1X"] + (1.0 - probabilities["DC_1X"]), 1.0, places=8)
        self.assertTrue(0.0 <= probabilities["BTTS"] <= 1.0)
        self.assertAlmostEqual(sum(probabilities["DNB_HOME"].values()), 1.0, places=8)
        self.assertAlmostEqual(sum(probabilities["DNB_AWAY"].values()), 1.0, places=8)
        self.assertTrue(all(0.0 <= value <= 1.0
                            for value in probabilities.values() if isinstance(value, float)))

    def test_outcomes_keep_draw_and_push_semantics(self):
        draw = market_outcomes(1, 1)
        self.assertEqual(draw["1X2"], "X")
        self.assertEqual(draw["DC_1X"], 1)
        self.assertEqual(draw["DC_X2"], 1)
        self.assertEqual(draw["DNB_HOME"], "P")
        self.assertEqual(draw["DNB_AWAY"], "P")

    def test_report_covers_every_market_and_competition(self):
        rows = []
        fixtures = [
            ("A", "B", 2, 0, 1.8, 0.5), ("C", "D", 0, 1, 0.4, 1.2),
            ("A", "C", 1, 1, 1.0, 1.0), ("B", "D", 0, 2, 0.3, 1.7),
            ("A", "D", 3, 1, 2.1, 0.8), ("B", "C", 1, 0, 1.1, 0.4),
            ("C", "A", 0, 2, 0.2, 1.9), ("D", "B", 2, 2, 1.4, 1.3),
        ]
        for index, (home, away, hg, ag, hx, ax) in enumerate(fixtures):
            rows.append({
                "id": f"m{index}", "date": f"2025-08-{index + 1:02d}",
                "league": "ENG", "season": "2025/26", "home": home, "away": away,
                "hg": hg, "ag": ag, "hx": hx, "ax": ax,
            })
        report = run(rows, "2025-08-07")
        self.assertEqual(report["input_matches"], 8)
        self.assertEqual(report["input_matches_by_competition"], {"ENG": 8})
        self.assertEqual(report["predicted_matches_by_competition"], {"ENG": 6})
        self.assertIn("1X2", report["markets"])
        self.assertIn("BTTS", report["markets"])
        self.assertIn("TOTAL_OVER_2.5", report["markets"])
        self.assertIn("HOME_OVER_1.5", report["markets"])
        self.assertIn("DNB_HOME", report["markets"])
        self.assertEqual(report["markets"]["1X2"]["holdout"]["model"]["n"], 2)
        self.assertIn("ENG", report["markets"]["1X2"]["holdout_by_competition"])
        self.assertIn("ENG", report["markets"]["1X2"]["all_by_competition"])
        self.assertIn("brier_skill", report["markets"]["1X2"]["holdout"])
        self.assertIn("limitations", report)
        self.assertEqual(report["independent_market_count"], 15)
        self.assertIn("DNB_HOME", report["market_relationships"])
        self.assertIn("calibration_global", report["markets"]["1X2"])
        self.assertEqual(report["markets"]["1X2"]["calibration_global"]["calibrator"]["train_n"], 4)
        self.assertFalse(report["markets"]["1X2"]["calibration_global"]["calibrator"]["available"])
        self.assertIn("ENG", report["markets"]["1X2"]["calibration_by_competition"])

    def test_walk_forward_produces_predictions_only_after_history_exists(self):
        rows = [
            {"id": "a", "date": "2025-01-01", "league": "ENG", "season": "s",
             "home": "A", "away": "B", "hg": 2, "ag": 0, "hx": 1.7, "ax": 0.3},
            {"id": "b", "date": "2025-01-01", "league": "ENG", "season": "s",
             "home": "C", "away": "D", "hg": 0, "ag": 2, "hx": 0.2, "ax": 1.6},
            {"id": "c", "date": "2025-01-02", "league": "ENG", "season": "s",
             "home": "A", "away": "C", "hg": 1, "ag": 1, "hx": 1.0, "ax": 1.0},
            {"id": "d", "date": "2025-01-02", "league": "ENG", "season": "s",
             "home": "B", "away": "D", "hg": 0, "ag": 1, "hx": 0.2, "ax": 1.1},
        ]
        report = run(rows, "2025-01-02")
        self.assertEqual(report["predicted_matches_by_competition"], {"ENG": 2})
        self.assertEqual(report["markets"]["1X2"]["holdout"]["model"]["n"], 2)


    def test_vector_scaling_uses_training_rows_and_preserves_probability_simplex(self):
        train = []
        for i in range(120):
            actual = "1" if i % 3 == 0 else "X" if i % 3 == 1 else "2"
            train.append({
                "market": "1X2", "actual": actual,
                "model": {"1": 0.60, "X": 0.25, "2": 0.15},
            })
        params = fit_vector_scaling(train, "1X2")
        self.assertTrue(params["available"])
        self.assertEqual(params["train_n"], 120)
        self.assertEqual(set(params["classes"]), {"1", "X", "2"})
        holdout = {"market": "1X2", "actual": "2",
                   "model": {"1": 0.55, "X": 0.30, "2": 0.15}}
        calibrated = apply_vector_scaling(holdout, params)["calibrated"]
        self.assertAlmostEqual(sum(calibrated.values()), 1.0, places=8)
        self.assertTrue(all(0.0 <= value <= 1.0 for value in calibrated.values()))

    def test_vector_scaling_refuses_small_training_sample(self):
        params = fit_vector_scaling([
            {"market": "BTTS", "actual": "1", "model": 0.65}
        ] * 20, "BTTS")
        self.assertFalse(params["available"])
        self.assertEqual(params["train_n"], 20)


if __name__ == "__main__":
    unittest.main()
