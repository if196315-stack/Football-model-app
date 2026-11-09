import unittest

from backtest_all_markets import market_outcomes, market_probs, run


class AllMarketBacktestTests(unittest.TestCase):
    def test_market_probabilities_are_valid(self):
        p = market_probs(1.35, 0.95)
        self.assertAlmostEqual(sum(p["1X2"].values()), 1.0, places=8)
        self.assertAlmostEqual(p["DC_1X"] + (1.0 - p["DC_1X"]), 1.0, places=8)
        self.assertTrue(0.0 <= p["BTTS"] <= 1.0)
        self.assertAlmostEqual(sum(p["DNB_HOME"].values()), 1.0, places=8)
        self.assertAlmostEqual(sum(p["DNB_AWAY"].values()), 1.0, places=8)
        self.assertTrue(all(0.0 <= p[k] <= 1.0 for k in p if isinstance(p[k], float)))

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
        for i, (home, away, hg, ag, hx, ax) in enumerate(fixtures):
            rows.append({"id": f"m{i}", "date": f"2025-08-{i+1:02d}",
                "league": "ENG", "season": "2025/26", "home": home, "away": away,
                "hg": hg, "ag": ag, "hx": hx, "ax": ax})
        report = run(rows, "2025-08-07")
        self.assertEqual(report["input_matches"], 8)
        self.assertIn("1X2", report["markets"])
        self.assertIn("BTTS", report["markets"])
        self.assertIn("TOTAL_OVER_2.5", report["markets"])
        self.assertIn("HOME_OVER_1.5", report["markets"])
        self.assertIn("DNB_HOME", report["markets"])
        self.assertEqual(report["markets"]["1X2"]["holdout"]["model"]["n"], 2)
        self.assertIn("ENG", report["markets"]["1X2"]["holdout_by_competition"])


if __name__ == "__main__":
    unittest.main()
