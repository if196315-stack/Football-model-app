import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from update_odds_api_sport import parse_odds, match_fixture, sim


class ApiSportOddsTests(unittest.TestCase):
    def test_team_similarity_accepts_common_club_name_variants(self):
        self.assertGreaterEqual(sim("Manchester City", "Man City"), 0.84)

    def test_matches_provider_fixture_by_team_and_kickoff(self):
        target = {"utcDate": "2026-11-21T15:00:00Z", "home": "Manchester City", "away": "Fulham"}
        source = [{
            "id": 123, "startTimestamp": 1795273200000,
            "homeTeam": {"name": "Manchester City"}, "awayTeam": {"name": "Fulham"}
        }]
        # Explicit timestamp is derived from 2026-11-21T15:00:00Z.
        self.assertIsNotNone(match_fixture(target, source))

    def test_parses_double_chance_and_only_zero_handicap(self):
        source = {"id": 7, "oddsBk": {"pari": {"markets": {
            "double_chance": {"status": "open", "stakes": {
                "1x": {"factor": 1.3}, "x2": {"factor": 2.7}
            }},
            "handicap": {"status": "open", "stakes": {
                "handicap_1": {"lines": [
                    {"argument": 0, "factor": 1.91},
                    {"argument": -0.5, "factor": 2.2}
                ]},
                "handicap_2": {"lines": [{"argument": 0, "factor": 1.95}]}
            }}
        }}}}
        quote = parse_odds(source)
        self.assertEqual(quote["best"]["doubleChance"], {"1x": 1.3, "x2": 2.7})
        self.assertEqual({(x["team"], x["point"]) for x in quote["best"]["spreads"]},
                         {("home", 0.0), ("away", 0.0)})


if __name__ == "__main__":
    unittest.main()
