import unittest
from datetime import date, datetime, timezone

from backend.diagnose_seven_competition_api_sport import upcoming_one_per_competition


class SevenCompetitionSampleTests(unittest.TestCase):
    def test_selects_one_upcoming_fixture_for_each_supported_competition(self):
        domestic = {"competitions": [
            {"code": "PL", "matches": [
                {"id": "pl-old", "utcDate": "2026-11-10T10:00:00Z", "status": "FINISHED", "home": "A", "away": "B"},
                {"id": "pl-2", "utcDate": "2026-11-21", "status": "SCHEDULED", "home": "C", "away": "D"},
                {"id": "pl-1", "utcDate": "2026-11-20T10:00:00Z", "status": "SCHEDULED", "home": "E", "away": "F"},
            ]},
            {"code": "PD", "matches": [
                {"id": "pd-1", "utcDate": "2026-11-22T10:00:00Z", "status": "TIMED", "home": "G", "away": "H"}
            ]},
        ]}
        uefa = {"competitions": [
            {"code": "UCL", "matches": [
                {"id": "ucl-1", "utcDate": "2026-11-24", "status": "SCHEDULED", "home": "I", "away": "J"}
            ]},
            {"code": "UEL", "matches": [
                {"id": "uel-1", "utcDate": "2026-11-25T19:00:00Z", "status": "SCHEDULED", "home": "K", "away": "L"}
            ]},
        ]}
        result = upcoming_one_per_competition(
            domestic, uefa, today=date(2026, 11, 11)
        )
        self.assertEqual([row["competition"] for row in result], ["PL", "PD", "UCL", "UEL"])
        self.assertEqual(result[0]["id"], "pl-1")
        self.assertEqual(result[2]["utcDate"], "2026-11-24")

    def test_handles_naive_and_aware_timestamps_in_same_competition(self):
        data = {"competitions": [{"code": "PL", "matches": [
            {"id": "naive", "utcDate": "2026-11-20T10:00:00", "status": "SCHEDULED", "home": "A", "away": "B"},
            {"id": "aware", "utcDate": "2026-11-21T10:00:00Z", "status": "SCHEDULED", "home": "C", "away": "D"},
        ]}]}
        result = upcoming_one_per_competition(data, {}, today=date(2026, 11, 11))
        self.assertEqual(result[0]["id"], "naive")
        self.assertEqual(result[0]["utcDate"], "2026-11-20T10:00:00+00:00")


if __name__ == "__main__":
    unittest.main()
