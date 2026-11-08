import json
import tempfile
import unittest
from pathlib import Path

from report_soccer365_coverage import build_report


class CoverageReportCliTests(unittest.TestCase):
    def test_reads_catalog_and_jsonl_without_mutating_inputs(self):
        catalog = [{
            "country": "England",
            "competitionId": "eng-prem",
            "competitionName": "Premier League",
            "competitionType": "league",
            "season": "2026-2027",
            "url": "https://soccer365.net/verified/premier/",
        }]
        row = {
            "id": "soccer365:soccer365.net/live/match-a-b",
            "country": "England",
            "competitionId": "eng-prem",
            "competitionType": "league",
            "season": "2026-2027",
            "home": "A FC",
            "away": "B FC",
            "status": "FINISHED",
            "homeScore": 1,
            "awayScore": 0,
            "statistics": {"homeXg": None, "awayXg": None},
        }
        with tempfile.TemporaryDirectory() as directory:
            catalog_path = Path(directory) / "catalog.json"
            records_path = Path(directory) / "archive.jsonl"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            records_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            catalog_before = catalog_path.read_bytes()
            records_before = records_path.read_bytes()

            report = build_report(catalog_path, records_path)

            self.assertTrue(report["valid"])
            self.assertEqual(report["competitions"][0]["finishedWithScore"], 1)
            self.assertEqual(report["competitions"][0]["finishedMissingXg"], 1)
            self.assertEqual(catalog_path.read_bytes(), catalog_before)
            self.assertEqual(records_path.read_bytes(), records_before)

    def test_non_list_catalog_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog_path = Path(directory) / "catalog.json"
            records_path = Path(directory) / "archive.jsonl"
            catalog_path.write_text("{}", encoding="utf-8")
            records_path.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "must contain a list"):
                build_report(catalog_path, records_path)


if __name__ == "__main__":
    unittest.main()
