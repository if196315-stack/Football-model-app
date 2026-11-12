import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from handicap_zero import analyze_handicap_zero


class HandicapZeroMathTests(unittest.TestCase):
    def test_dnb_probability_is_conditional_and_ev_accounts_for_push(self):
        result = analyze_handicap_zero(0.45, 0.25, 0.30, 2.0)
        self.assertAlmostEqual(result["conditional_win_probability"], 0.60)
        self.assertAlmostEqual(result["push_probability"], 0.25)
        self.assertAlmostEqual(result["break_even_probability"], 0.50)
        self.assertAlmostEqual(result["edge_pp"], 10.0)
        self.assertAlmostEqual(result["expected_value"], 0.15)
        self.assertAlmostEqual(result["full_kelly_fraction"], 0.20)
        self.assertTrue(result["qualifies"])

    def test_draw_probability_is_not_counted_as_a_loss_or_a_win(self):
        result = analyze_handicap_zero(0.30, 0.40, 0.30, 2.0)
        self.assertAlmostEqual(result["conditional_win_probability"], 0.50)
        self.assertAlmostEqual(result["expected_value"], 0.0)
        self.assertFalse(result["qualifies"])

    def test_fails_edge_and_ev_separately(self):
        edge_fail = analyze_handicap_zero(0.40, 0.20, 0.40, 2.0)
        self.assertFalse(edge_fail["passes_probability_edge"])
        self.assertFalse(edge_fail["passes_ev"])
        ev_fail = analyze_handicap_zero(0.12, 0.80, 0.08, 2.0)
        self.assertTrue(ev_fail["passes_probability_edge"])
        self.assertFalse(ev_fail["passes_ev"])

    def test_rejects_invalid_probability_mass_and_odds(self):
        with self.assertRaises(ValueError):
            analyze_handicap_zero(0.4, 0.2, 0.3, 2.0)
        with self.assertRaises(ValueError):
            analyze_handicap_zero(0.4, 0.2, 0.4, 1.0)
        with self.assertRaises(ValueError):
            analyze_handicap_zero(0.0, 1.0, 0.0, 2.0)


if __name__ == "__main__":
    unittest.main()
