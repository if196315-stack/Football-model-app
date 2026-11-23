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

    def test_unified_collector_refreshes_domestic_feed_before_snapshot_application(self):
        workflow = self.read("collect-uefa-melbet-snapshots.yml")
        refresh = workflow.index("run: python backend/update_data_pitchapi.py")
        capture = workflow.index("run: python backend/collect_uefa_melbet_snapshots.py")
        apply = workflow.index("run: python backend/apply_melbet_snapshots.py")
        install = workflow.index("run: python -m pip install requests pandas")
        self.assertLess(install, refresh)
        self.assertLess(refresh, capture)
        self.assertLess(capture, apply)
        self.assertIn('PITCHAPI_MAX_SHOT_REQUESTS: "0"', workflow)
        self.assertIn("PITCHAPI_API_KEY: ${{ secrets.PITCHAPI_API_KEY }}", workflow)

if __name__ == "__main__":
    unittest.main()
