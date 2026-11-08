import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_odds_snapshots import extract_h2h, match_event, normalize


class OddsSnapshotTests(unittest.TestCase):
    def test_normalize_ignores_common_club_suffix(self):
        self.assertEqual(normalize("Arsenal FC"), normalize("Arsenal"))

    def test_matches_home_and_away_with_kickoff(self):
        fixture = {
            "utcDate": "2026-11-10T19:00:00Z",
            "home": "Arsenal",
            "away": "Chelsea",
        }
        events = [{
            "commence_time": "2026-11-10T19:00:00Z",
            "home_team": "Arsenal FC",
            "away_team": "Chelsea FC",
        }]
        self.assertIs(match_event(fixture, events), events[0])

    def test_rejects_wrong_kickoff(self):
        fixture = {
            "utcDate": "2026-11-10T19:00:00Z",
            "home": "Arsenal",
            "away": "Chelsea",
        }
        events = [{
            "commence_time": "2026-11-12T19:00:00Z",
            "home_team": "Arsenal",
            "away_team": "Chelsea",
        }]
        self.assertIsNone(match_event(fixture, events))

    def test_rejects_ambiguous_event_match(self):
        fixture = {
            "utcDate": "2026-11-10T19:00:00Z",
            "home": "Arsenal",
            "away": "Chelsea",
        }
        events = [
            {"commence_time": "2026-11-10T19:00:00Z", "home_team": "Arsenal", "away_team": "Chelsea"},
            {"commence_time": "2026-11-10T19:05:00Z", "home_team": "Arsenal", "away_team": "Chelsea"},
        ]
        self.assertIsNone(match_event(fixture, events))

    def test_extracts_only_decimal_h2h_prices(self):
        event = {"bookmakers": [{
            "key": "book",
            "title": "Bookmaker",
            "last_update": "2026-11-09T12:00:00Z",
            "markets": [
                {"key": "totals", "outcomes": [{"name": "Over", "price": 1.9}]},
                {"key": "h2h", "outcomes": [
                    {"name": "Arsenal", "price": 2.1},
                    {"name": "Draw", "price": 3.4},
                    {"name": "Chelsea", "price": 3.8},
                ]},
            ],
        }]}
        result = extract_h2h(event)
        self.assertEqual(len(result), 1)
        self.assertEqual([x["price"] for x in result[0]["outcomes"]], [2.1, 3.4, 3.8])


if __name__ == "__main__":
    unittest.main()
