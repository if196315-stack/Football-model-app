"""Regression tests for the runner-local unified MelBet snapshot archive."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"


class RunnerLocalSnapshotWorkflowTests(unittest.TestCase):
    def read(self, name):
        return (WORKFLOWS / name).read_text(encoding="utf-8")

    def test_domestic_refresh_does_not_apply_archive_from_another_runner(self):
        workflow = self.read("update-data.yml")
        self.assertNotIn("run: python backend/apply_melbet_snapshots.py", workflow)
        self.assertIn("runner-local", workflow)

    def test_legacy_manual_odds_refresh_does_not_apply_missing_shared_archive(self):
        workflow = self.read("update-odds-only.yml")
        self.assertNotIn("run: python backend/apply_melbet_snapshots.py", workflow)
        self.assertIn("runner-local", workflow)

    def test_unified_collector_captures_before_applying_same_run_archive(self):
        workflow = self.read("collect-uefa-melbet-snapshots.yml")
        capture = workflow.index("run: python backend/collect_uefa_melbet_snapshots.py")
        apply = workflow.index("run: python backend/apply_melbet_snapshots.py")
        self.assertLess(capture, apply)
        self.assertIn("Capture MelBet snapshots for all eight competitions", workflow)
        self.assertIn("Apply fresh MelBet snapshots to domestic competitions", workflow)

    def test_strongest_bet_signal_can_pass_for_each_of_the_eight_leagues(self):
        from evaluate_uefa_paper_signals import build_signals
        from predict_uefa_markets import SUPPORTED_LEAGUES

        leagues = {"ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL"}
        self.assertEqual(SUPPORTED_LEAGUES, leagues)
        rows = []
        for league in sorted(leagues):
            rows.append({
                "fixture_id": f"{league}:fixture-1",
                "captured_at": "2026-11-23T12:00:00+00:00",
                "league_code": league,
                "kickoff_utc": "2026-11-24T18:00:00+00:00",
                "home_team": "Home FC",
                "away_team": "Away FC",
                "market": "double_chance",
                "outcome": "1x",
                "price": 1.60,
                "model_probability": 0.75,
                "bookmaker_update_age_seconds": 60,
                "freshness_status": "fresh",
                "model_history_max_date": "2026-11-20",
                "candidate_market": True,
                "reference_only": False,
                "market_parser_version": "melbet-fulltime-v2",
            })

        signals = build_signals(rows)
        self.assertEqual({row["league_code"] for row in signals}, leagues)
        self.assertEqual({row["decision"] for row in signals}, {"BET"})
        self.assertTrue(all(row["edge"] >= 0.05 for row in signals))
        self.assertTrue(all(row["expected_value"] >= 0.05 for row in signals))
        self.assertTrue(all(row["model_probability"] >= 0.60 for row in signals))


if __name__ == "__main__":
    unittest.main()
