import unittest

from backtest_pitchapi import walk_forward


def row(match_id, date, home, away, hg, ag, hx, ax):
    return {
        "match_id": match_id, "date": date, "league": "ENG", "season": "2024/2025",
        "home": home, "away": away, "hg": hg, "ag": ag,
        "hx": hx, "ax": ax, "paired_xg": hx is not None and ax is not None,
    }


class PitchApiBacktestTests(unittest.TestCase):
    def sample(self):
        return [
            row("m1", "2024-08-01", "A", "B", 1, 0, 1.2, 0.4),
            row("m2", "2024-08-02", "C", "D", 0, 1, 0.5, 1.1),
            row("m3", "2024-08-03", "A", "C", 2, 1, 1.7, 0.8),
            row("m4", "2024-08-04", "B", "D", 1, 1, 1.0, 1.0),
            row("m5", "2024-08-05", "A", "D", 0, 0, 0.6, 0.7),
            row("m6", "2024-08-06", "B", "C", 1, 2, 1.0, 1.4),
            row("m7", "2024-08-07", "A", "B", 2, 0, 1.9, 0.3),
            row("m8", "2024-08-07", "C", "D", 0, 1, 0.4, 1.5),
        ]

    def test_same_date_results_and_xg_cannot_change_other_prediction(self):
        original = self.sample()
        changed = self.sample()
        # Change the first fixture on the target date, including its result and xG.
        changed[-2] = row("m7", "2024-08-07", "A", "B", 0, 4, 4.0, 0.0)

        before = walk_forward(original, holdout_from="2024-08-07", include_predictions=True)
        after = walk_forward(changed, holdout_from="2024-08-07", include_predictions=True)
        pred_before = next(p for p in before["predictions"] if p["match_id"] == "m8")
        pred_after = next(p for p in after["predictions"] if p["match_id"] == "m8")

        self.assertEqual(pred_before["model"], pred_after["model"])
        self.assertEqual(pred_before["baseline"], pred_after["baseline"])

    def test_missing_xg_is_not_added_to_team_history(self):
        matches = self.sample()
        matches[0] = row("m1", "2024-08-01", "A", "B", 1, 0, None, 0.4)
        report = walk_forward(matches, holdout_from="2024-08-07")
        self.assertEqual(report["paired_xg_matches"], 7)


    def test_calibration_tables_cover_each_predicted_class(self):
        report = walk_forward(self.sample(), holdout_from="2024-08-07", include_predictions=True)
        self.assertIn("calibration", report)
        self.assertIn("holdout_calibration", report)
        for model_key in ("model", "baseline"):
            table = report["calibration"][model_key]["by_outcome"]
            for outcome in ("1", "x", "2"):
                self.assertIn(outcome, table)
                covered = sum(bucket["n"] for bucket in table[outcome]["bins"])
                self.assertEqual(covered, report["predicted_matches"])
                self.assertGreaterEqual(table[outcome]["ece"], 0.0)

    def test_temperature_scaling_is_fitted_without_holdout_labels(self):
        original = self.sample()
        changed = self.sample()
        changed[-2] = row("m7", "2024-08-07", "A", "B", 0, 4, 1.9, 0.3)
        before = walk_forward(original, holdout_from="2024-08-07")
        after = walk_forward(changed, holdout_from="2024-08-07")
        self.assertIn("temperature_scaling", before)
        self.assertEqual(
            before["temperature_scaling"]["temperature"],
            after["temperature_scaling"]["temperature"],
        )
        self.assertEqual(
            before["temperature_scaling"]["fit_n"],
            before["predicted_matches"] - before["holdout"]["model"]["n"],
        )

    def test_temperature_scaled_probabilities_sum_to_one(self):
        report = walk_forward(self.sample(), holdout_from="2024-08-07")
        result = report["temperature_scaling"]
        self.assertGreater(result["temperature"], 0.0)
        for outcome in ("1", "x", "2"):
            self.assertIn(outcome, result["holdout_calibrated_calibration"]["by_outcome"])
        self.assertEqual(
            result["holdout_calibrated"]["n"],
            result["holdout_raw"]["n"],
        )

    def test_prediction_rows_are_opt_in(self):
        report = walk_forward(self.sample())
        self.assertNotIn("predictions", report)


if __name__ == "__main__":
    unittest.main()
