import unittest

from backtest_all_markets import market_outcomes, market_probs, run


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
        self.assertEqual(report["predicted_matches_by_competition"], {"ENG": 2})
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

    def test_same_day_fixtures_are_not_added_to_history_before_scoring(self):
        # Both teams appear on the same date; they are only eligible for predictions
        # on later dates, regardless of ordering inside that date.
        rows = [
            {"id": "a", "date": "2025-01-01", "league": "ENG", "season": "s",
             "home": "A", "away": "B", "hg": 3, "ag": 0, "hx": 2.4, "ax": 0.2},
            {"id": "b", "date": "2025-01-01", "league": "ENG", "season": "s",
             "home": "C", "away": "D", "hg": 0, "ag": 2, "hx": 0.1, "ax": 1.8},
            {"id": "c", "date": "2025-01-02", "league": "ENG", "season": "s",
             "home": "A", "away": "C", "hg": 1, "ag": 1, "hx": 1.0, "ax": 1.0},
            {"id": "d", "date": "2025-01-02", "league": "ENG", "season": "s",
             "home": "B", "away": "D", "hg": 0, "ag": 1, "hx": 0.2, "ax": 1.1},
        ]
        first = run(rows, "2025-01-02")
        changed = [dict(row) for row in rows]
        changed[0] = {**changed[0], "hg": 0, "ag": 4, "hx": 0.1, "ax": 3.5}
        second = run(changed, "2025-01-02")
        self.assertEqual(first["predicted_matches_by_competition"], {"ENG": 0})
        self.assertEqual(second["predicted_matches_by_competition"], {"ENG": 0})


if __name__ == "__main__":
    unittest.main()
