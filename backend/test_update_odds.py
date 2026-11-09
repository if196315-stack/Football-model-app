import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from update_odds import read_market, convert_event


class OddsMarketParsingTests(unittest.TestCase):
    def test_double_chance_labels(self):
        bookmaker = {"markets": [{"key": "double_chance", "outcomes": [
            {"name": "Home/Draw", "price": 1.35},
            {"name": "Draw/Away", "price": 1.65},
            {"name": "Home/Away", "price": 1.25},
        ]}]}
        self.assertEqual(read_market(bookmaker, "double_chance", "Arsenal", "Chelsea"),
                         {"1x": 1.35, "x2": 1.65, "12": 1.25})

    def test_spreads_exclude_zero_and_preserve_sign(self):
        bookmaker = {"markets": [{"key": "spreads", "outcomes": [
            {"name": "Arsenal", "point": 0, "price": 1.9},
            {"name": "Arsenal", "point": -1.5, "price": 2.2},
            {"name": "Chelsea", "point": 1.5, "price": 1.7},
        ]}]}
        self.assertEqual(read_market(bookmaker, "spreads", "Arsenal", "Chelsea"), [
            {"team": "home", "point": -1.5, "price": 2.2},
            {"team": "away", "point": 1.5, "price": 1.7},
        ])

    def test_convert_event_never_keeps_ah0(self):
        event = {"id": "event-1", "home_team": "Arsenal", "away_team": "Chelsea",
                 "bookmakers": [{"key": "book", "title": "Book", "markets": [
                     {"key": "spreads", "outcomes": [
                         {"name": "Arsenal", "point": 0, "price": 1.9},
                         {"name": "Arsenal", "point": -0.5, "price": 2.0},
                     ]}
                 ]}]}
        quote = convert_event(event)
        self.assertEqual(len(quote["best"]["spreads"]), 1)
        self.assertEqual(quote["best"]["spreads"][0]["point"], -0.5)


if __name__ == "__main__":
    unittest.main()
