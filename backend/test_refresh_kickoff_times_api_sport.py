import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from refresh_kickoff_times_api_sport import (enrich_feed, is_date_only, iter_windows, match_kickoff_fixture)


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

    def test_accepts_duplicate_provider_rows_when_kickoff_is_identical(self):
        target = {"utcDate": "2026-12-21", "home": "Chelsea", "away": "Aston Villa"}
        candidates = [
            {"id": 1, "startTimestamp": 1797883200000,
             "homeTeam": {"name": "Chelsea"}, "awayTeam": {"name": "Aston Villa"}},
            {"id": 2, "startTimestamp": 1797883200000,
             "homeTeam": {"name": "Chelsea"}, "awayTeam": {"name": "Aston Villa"}},
        ]
        self.assertIsNotNone(match_kickoff_fixture(target, candidates))

    def test_rejects_duplicate_provider_rows_with_conflicting_kickoffs(self):
        target = {"utcDate": "2026-12-21", "home": "Chelsea", "away": "Aston Villa"}
        candidates = [
            {"id": 1, "startTimestamp": 1797883200000,
             "homeTeam": {"name": "Chelsea"}, "awayTeam": {"name": "Aston Villa"}},
            {"id": 2, "startTimestamp": 1797886800000,
             "homeTeam": {"name": "Chelsea"}, "awayTeam": {"name": "Aston Villa"}},
        ]
        self.assertIsNone(match_kickoff_fixture(target, candidates))

    def test_aliases_match_common_italian_and_german_club_names(self):
        target = {"utcDate": "2026-12-13", "home": "Napoli", "away": "Milan"}
        candidate = {
            "id": 3, "startTimestamp": 1797181500000,
            "homeTeam": {"name": "SSC Napoli"}, "awayTeam": {"name": "AC Milan"},
        }
        self.assertIsNotNone(match_kickoff_fixture(target, [candidate]))
        target = {"utcDate": "2026-12-12", "home": "Paderborn", "away": "Elversberg"}
        candidate = {
            "id": 4, "startTimestamp": 1797085800000,
            "homeTeam": {"name": "SC Paderborn 07"}, "awayTeam": {"name": "SV 07 Elversberg"},
        }
        self.assertIsNotNone(match_kickoff_fixture(target, [candidate]))

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
