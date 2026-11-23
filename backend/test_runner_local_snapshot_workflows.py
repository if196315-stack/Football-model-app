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

    def test_android_strongest_ai_signal_is_audited_for_all_eight_leagues(self):
        from datetime import date, datetime, timezone
        from audit_domestic_ai_signals import build
        from predict_uefa_markets import SUPPORTED_LEAGUES

        leagues = {"ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL"}
        self.assertEqual(SUPPORTED_LEAGUES, leagues)
        feed_codes = {"ENG": "PL", "ESP": "PD", "ITA": "SA", "GER": "BL1", "FRA": "FL1",
                      "UCL": "UCL", "UEL": "UEL", "UECL": "UECL"}
        competitions = []
        for league, feed_code in feed_codes.items():
            competitions.append({
                "code": feed_code,
                "matches": [{
                    "id": f"{league}-fixture",
                    "status": "SCHEDULED",
                    "utcDate": "2026-11-24T18:00:00Z",
                    "home": "Home FC",
                    "away": "Away FC",
                    "prediction": {"1x": 0.70},
                    "odds": {
                        "bookmaker": "MELBET",
                        "prematchActive": True,
                        "freshnessStatus": "fresh",
                        "updatedAt": datetime.now(timezone.utc).isoformat(),
                        "best": {"doubleChance": {"1x": 2.0}},
                    },
                }],
            })

        report = build({"competitions": competitions}, today=date(2026, 11, 23))
        self.assertEqual(set(report["leagues"]), leagues)
        for league in leagues:
            with self.subTest(league=league):
                result = report["leagues"][league]
                self.assertEqual(result["counts"].get("qualifying_signals"), 1)
                self.assertEqual(result["qualifying_matches"][0]["line_leader"], "1X")
                self.assertEqual(result["qualifying_matches"][0]["ai_signal"]["side"], "1X")


if __name__ == "__main__":
    unittest.main()
