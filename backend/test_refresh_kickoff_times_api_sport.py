import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from refresh_kickoff_times_api_sport import enrich_feed, is_date_only, iter_windows


class KickoffRefreshTests(unittest.TestCase):
    def test_only_plain_calendar_dates_are_enriched(self):
        self.assertTrue(is_date_only("2026-11-24"))
        self.assertFalse(is_date_only("2026-11-24T19:30:00Z"))
        self.assertFalse(is_date_only(None))

    def test_date_windows_are_inclusive_and_limited_to_31_days(self):
        windows = list(iter_windows(date(2026, 1, 1), date(2026, 2, 2)))
        self.assertEqual(windows, [
            (date(2026, 1, 1), date(2026, 1, 31)),
            (date(2026, 2, 1), date(2026, 2, 2)),
        ])

    def test_enriches_date_only_fixture_even_without_odds(self):
        payload = {"competitions": [{"code": "PL", "matches": [{
            "utcDate": "2026-11-24", "status": "SCHEDULED",
            "home": "Manchester City", "away": "Fulham",
        }]}]}
        fixtures = [{
            "id": 123, "startTimestamp": 1795548600000,
            "homeTeam": {"name": "Man City", "translations": {"en": "Manchester City"}},
            "awayTeam": {"name": "Fulham"},
        }]
        result = enrich_feed(payload, fixtures)
        self.assertEqual(result["enriched"], 1)
        self.assertEqual(payload["competitions"][0]["matches"][0]["utcDate"],
                         "2026-11-24T19:30:00Z")

    def test_preserves_existing_timestamp_and_finished_matches(self):
        payload = {"competitions": [{"code": "PL", "matches": [
            {"utcDate": "2026-11-24T18:00:00Z", "status": "SCHEDULED",
             "home": "A", "away": "B"},
            {"utcDate": "2026-11-24", "status": "FINISHED",
             "home": "C", "away": "D"},
        ]}]}
        result = enrich_feed(payload, [])
        self.assertEqual(result["dateOnlyChecked"], 0)
        self.assertEqual(payload["competitions"][0]["matches"][0]["utcDate"],
                         "2026-11-24T18:00:00Z")


if __name__ == "__main__":
    unittest.main()
