import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_odds_snapshots import append_snapshots, snapshot_rows


class OddsSnapshotTests(unittest.TestCase):
    def sample(self):
        return {"oddsBookmaker": "melbet", "competitions": [{"code": "PL", "matches": [{
            "id": 123, "utcDate": "2026-11-10T17:30:00Z",
            "home": "Arsenal", "away": "Chelsea",
            "odds": {
                "best": {
                    "doubleChance": {"1x": 1.31, "x2": 1.88, "12": 1.29},
                    "spreads": [
                        {"team": "home", "point": 0, "price": 2.1, "bookmaker": "MELBET"},
                        {"team": "away", "point": 0, "price": 1.9, "bookmaker": "MELBET"},
                    ],
                    "teamTotals": [
                        {"team": "home", "point": 1.5, "side": "over", "price": 1.82, "bookmaker": "MELBET"},
                        {"team": "away", "point": 1.5, "side": "under", "price": 2.0, "bookmaker": "MELBET"},
                    ],
                    "btts": {"yes": 1.85, "no": 1.95},
                    "totals": [
                        {"point": 2.5, "side": "over", "price": 1.91},
                        {"point": 2.5, "side": "under", "price": 1.99},
                    ],
                },
                "bestBookmakers": {"doubleChance": {"1x": "Book A"}}
            }
        }]}]}

    def test_only_strategy_markets_are_snapshotted(self):
        rows = list(snapshot_rows(self.sample(), "2026-11-09T12:00:00+00:00"))
        self.assertEqual(len(rows), 11)
        self.assertEqual({row["market"] for row in rows},
                         {"double_chance", "spread", "team_total", "match_total", "btts"})
        self.assertTrue(any(row.get("side") == "under" and row["market"] == "team_total" for row in rows))
        self.assertTrue(any(row.get("side") == "under" and row["market"] == "match_total" for row in rows))
        spread_rows = [row for row in rows if row["market"] == "spread"]
        self.assertEqual(len(spread_rows), 2)
        self.assertTrue(all(abs(row["point"]) < 0.001 for row in spread_rows))
        self.assertTrue(all(row["retrieved_at"] == "2026-11-09T12:00:00+00:00" for row in rows))

    def test_append_preserves_prior_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "data.json"
            archive = Path(tmp) / "history.jsonl"
            source.write_text(json.dumps(self.sample()), encoding="utf-8")
            first = append_snapshots(source, archive, "2026-11-09T12:00:00+00:00")
            second = append_snapshots(source, archive, "2026-11-09T18:00:00+00:00")
            self.assertEqual(first["records_appended"], 11)
            self.assertEqual(second["records_appended"], 11)
            lines = archive.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 22)
            self.assertNotEqual(json.loads(lines[0])["retrieved_at"],
                                json.loads(lines[-1])["retrieved_at"])

    def test_legacy_non_melbet_quotes_are_not_archived(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "data.json"
            archive = Path(tmp) / "history.jsonl"
            data = self.sample()
            data["oddsBookmaker"] = "legacy-mixed-books"
            source.write_text(json.dumps(data), encoding="utf-8")
            result = append_snapshots(source, archive, "2026-11-09T12:00:00+00:00")
            self.assertEqual(result["records_appended"], 0)
            self.assertFalse(archive.exists())

    def test_empty_snapshot_does_not_create_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "data.json"
            archive = Path(tmp) / "history.jsonl"
            source.write_text(json.dumps({"competitions": []}), encoding="utf-8")
            result = append_snapshots(source, archive, "2026-11-09T12:00:00+00:00")
            self.assertEqual(result["records_appended"], 0)
            self.assertFalse(result["archive_written"])
            self.assertFalse(archive.exists())


if __name__ == "__main__":
    unittest.main()
