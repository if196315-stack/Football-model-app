import csv
import json
import tempfile
import unittest
from pathlib import Path

from export_pitchapi_backtest import export


class PitchApiBacktestExportTests(unittest.TestCase):
    def test_exports_only_clean_paired_xg_rows_and_hashes_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache_path = root / "cache.json"
            csv_path = root / "out" / "cohort.csv"
            manifest_path = root / "out" / "manifest.json"
            matches = {
                "valid": {
                    "id": "valid", "status": "finished", "leagueCode": "ENG",
                    "season": "2024/25", "date": "2025-01-01T15:00:00Z",
                    "home": "Home", "away": "Away", "homeScore": 2, "awayScore": 1,
                    "homeXg": 1.4, "awayXg": 0.8,
                },
                "excluded_season": {
                    "id": "bad-season", "status": "finished", "leagueCode": "ESP",
                    "season": "2023/24", "date": "2024-01-01T15:00:00Z",
                    "home": "A", "away": "B", "homeScore": 1, "awayScore": 0,
                    "homeXg": 1.0, "awayXg": 0.2,
                },
                "excluded_playoff": {
                    "id": "playoff", "status": "finished", "leagueCode": "ENG",
                    "season": "2024/25", "date": "2025-02-01T15:00:00Z",
                    "home": "C", "away": "D", "homeScore": 1, "awayScore": 1,
                    "homeXg": 0.5, "awayXg": 0.5, "hasPlayoff": True,
                },
                "partial": {
                    "id": "partial", "status": "finished", "leagueCode": "ENG",
                    "season": "2024/25", "date": "2025-03-01T15:00:00Z",
                    "home": "E", "away": "F", "homeScore": 0, "awayScore": 0,
                    "homeXg": 0.0, "awayXg": None,
                },
            }
            cache_path.write_text(json.dumps({"provider": "PitchAPI", "matches": matches}), encoding="utf-8")
            manifest = export(cache_path, csv_path, manifest_path)
            with csv_path.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual([row["match_id"] for row in rows], ["valid"])
            self.assertEqual(manifest["rows"], 1)
            self.assertEqual(manifest["unique_match_ids"], 1)
            self.assertEqual(manifest["sha256"], __import__("hashlib").sha256(csv_path.read_bytes()).hexdigest())
            self.assertEqual(json.loads(manifest_path.read_text(encoding="utf-8"))["sha256"], manifest["sha256"])


if __name__ == "__main__":
    unittest.main()
