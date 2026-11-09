import tempfile
import unittest
from pathlib import Path

from evaluate_uefa_paper_signals import (
    build_signals, evaluate_candidate, append_new_signals, append_unique_paper_bets
)


def base_row(**overrides):
    row = {
        "schema_version": 1, "captured_at": "2026-11-10T12:00:00+00:00",
        "fixture_id": "fixture-1", "provider_event_id": "provider-1",
        "league_code": "UCL", "season": "2026/2027",
        "kickoff_utc": "2026-11-24", "home_team": "Home", "away_team": "Away",
        "market": "match_total_over", "market_parser_version": "betboom-fulltime-v2",
        "provider_market_slug": "total_goals", "provider_market_group": "total goals",
        "provider_market_name": "Total Goals", "point": 2.5, "side": "over",
        "candidate_market": True, "reference_only": False,
        "price": 1.9, "model_probability": 0.7,
        "model_probability_status": "available",
        "bookmaker_update_age_seconds": 120.0, "freshness_status": "fresh",
        "model_version": "uefa-xg-poisson-v1",
        "model_prediction_time": "2026-11-10T12:00:00+00:00",
        "model_history_max_date": "2026-11-05",
    }
    row.update(overrides)
    return row


class UefaPaperSignalTests(unittest.TestCase):
    def test_two_way_market_removes_overround_and_passes_all_thresholds(self):
        over = base_row()
        under = base_row(market="match_total_under_reference", side="under",
                         candidate_market=False, reference_only=True,
                         price=1.9, model_probability=0.3)
        signal = evaluate_candidate(over, [over, under])
        self.assertEqual(signal["decision"], "BET")
        self.assertAlmostEqual(signal["fair_probability"], 0.5, places=8)
        self.assertAlmostEqual(signal["edge"], 0.2, places=8)
        self.assertAlmostEqual(signal["expected_value"], 0.33, places=8)
        self.assertEqual(signal["edge_method"], "two_way_overround_removed")
        self.assertEqual(signal["provider_market_group"], "total goals")
        self.assertTrue(signal["paper_only"])

    def test_double_chance_uses_conservative_raw_implied_edge(self):
        row = base_row(market="double_chance", point=None, side=None, outcome="1x",
                       price=1.8, model_probability=0.7)
        signal = evaluate_candidate(row, [row])
        self.assertEqual(signal["decision"], "BET")
        self.assertEqual(signal["edge_method"], "raw_implied_conservative_no_devig")
        self.assertAlmostEqual(signal["fair_probability"], 1 / 1.8, places=8)

    def test_stale_quote_is_wait_not_bet(self):
        over = base_row(bookmaker_update_age_seconds=420.0, freshness_status="stale")
        under = base_row(market="match_total_under_reference", side="under",
                         candidate_market=False, reference_only=True,
                         price=1.9, model_probability=0.3)
        signal = evaluate_candidate(over, [over, under])
        self.assertEqual(signal["decision"], "WAIT")
        self.assertIn("quote_older_than_5_minutes", signal["reasons"])

    def test_complementary_price_from_different_provider_market_is_rejected(self):
        over = base_row()
        under = base_row(market="match_total_under_reference", side="under",
                         candidate_market=False, reference_only=True,
                         provider_market_slug="first_half_total_goals",
                         provider_market_group="first half total goals",
                         provider_market_name="First Half Total Goals",
                         price=1.9, model_probability=0.3)
        signal = evaluate_candidate(over, [over, under])
        self.assertEqual(signal["decision"], "WAIT")
        self.assertIn("complementary_price_missing", signal["reasons"])

    def test_low_probability_is_rejected(self):
        over = base_row(model_probability=0.55)
        under = base_row(market="match_total_under_reference", side="under",
                         candidate_market=False, reference_only=True,
                         price=1.9, model_probability=0.45)
        signal = evaluate_candidate(over, [over, under])
        self.assertEqual(signal["decision"], "REJECT")
        self.assertIn("model_probability_below_60_percent", signal["reasons"])

    def test_missing_complementary_quote_is_wait(self):
        signal = evaluate_candidate(base_row(), [base_row()])
        self.assertEqual(signal["decision"], "WAIT")
        self.assertIn("complementary_price_missing", signal["reasons"])

    def test_only_first_bet_per_fixture_market_becomes_paper_entry(self):
        first = {
            "signal_id": "first", "fixture_id": "fixture-1",
            "market": "match_total_over", "point": 2.5, "team": None, "outcome": None,
            "captured_at": "2026-11-10T12:00:00+00:00", "decision": "BET", "price": 1.9,
        }
        second = {**first, "signal_id": "second",
                  "captured_at": "2026-11-10T15:00:00+00:00", "price": 2.0}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "paper-bets.jsonl"
            result = append_unique_paper_bets([second, first], path)
            self.assertEqual(result["unique_paper_bets_added"], 1)
            self.assertEqual(result["unique_paper_bets_total"], 1)
            rows = path.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(rows), 1)
            self.assertIn('"signal_id": "first"', rows[0])
            again = append_unique_paper_bets([first, second], path)
            self.assertEqual(again["unique_paper_bets_added"], 0)

    def test_legacy_snapshots_without_parser_version_are_ignored(self):
        legacy = base_row()
        legacy.pop("market_parser_version")
        self.assertEqual(build_signals([legacy]), [])

    def test_append_is_idempotent_and_ignores_reference_rows(self):
        over = base_row()
        under = base_row(market="match_total_under_reference", side="under",
                         candidate_market=False, reference_only=True,
                         price=1.9, model_probability=0.3)
        rows = [over, under]
        signals = build_signals(rows)
        self.assertEqual(len(signals), 1)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "signals.jsonl"
            first = append_new_signals(signals, path)
            second = append_new_signals(signals, path)
            self.assertEqual(first["new_signals_appended"], 1)
            self.assertEqual(second["new_signals_appended"], 0)


if __name__ == "__main__":
    unittest.main()
