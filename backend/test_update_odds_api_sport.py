import copy
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
            "homeTeam": {"name": "Man City", "translations": {"en": "Manchester City"}},
            "awayTeam": {"name": "Fulham"}
        }]
        self.assertIsNotNone(match_fixture(target, source))

    def test_parses_btts_match_totals_and_both_team_total_sides_from_betboom_only(self):
        source = {"id": 88, "oddsBk": {
            "betboom": {"markets": {
                "both_teams_to_score": {"status": "open", "stage": "prematch", "stakes": {
                    "yes": {"factor": 1.82}, "no": {"factor": 1.96}
                }},
                "total_goals": {"status": "open", "stage": "prematch", "stakes": {
                    "over": {"lines": [{"argument": 2.5, "factor": 1.91}]},
                    "under": {"lines": [{"argument": 2.5, "factor": 1.95}]}
                }},
                "total_team_1": {"status": "open", "stage": "prematch", "stakes": {
                    "over": {"lines": [{"argument": 1.5, "factor": 1.88}]},
                    "under": {"lines": [{"argument": 1.5, "factor": 1.92}]}
                }},
                "total_team_2": {"status": "open", "stage": "prematch", "stakes": {
                    "over": {"lines": [{"argument": 0.5, "factor": 1.7}]},
                    "under": {"lines": [{"argument": 0.5, "factor": 2.1}]}
                }}
            }},
            "pari": {"markets": {
                "both_teams_to_score": {"status": "open", "stage": "prematch", "stakes": {"yes": {"factor": 9.99}}}
            }}
        }}
        quote = parse_odds(source)
        best = quote["best"]
        self.assertEqual(best["btts"], {"yes": 1.82, "no": 1.96})
        self.assertEqual({(x["point"], x["side"]) for x in best["totals"]},
                         {(2.5, "over"), (2.5, "under")})
        self.assertEqual({(x["team"], x["point"], x["side"]) for x in best["teamTotals"]},
                         {("home", 1.5, "over"), ("home", 1.5, "under"),
                          ("away", 0.5, "over"), ("away", 0.5, "under")})
        self.assertEqual([book["key"] for book in quote["bookmakers"]], ["betboom"])
        self.assertEqual(quote["bestBookmakers"]["btts"]["yes"], "BETBOOM")

    def test_parses_itb_when_provider_uses_line_in_outcome_name(self):
        source = {"id": 102, "oddsBk": {"betboom": {"markets": {
            "team_total_home_goals": {"status": "open", "stage": "prematch", "stakes": {
                "Over 1.5": {"lines": [{"argument": 1.5, "factor": 1.87}]},
                "Under 1.5": {"lines": [{"argument": 1.5, "factor": 1.94}]}
            }},
            "total_team_2": {"status": "open", "stage": "prematch", "stakes": {
                "over_0_5": {"lines": [{"argument": 0.5, "factor": 1.73}]}
            }}
        }}}}
        best = parse_odds(source)["best"]
        self.assertIn({"team": "home", "point": 1.5, "side": "over", "price": 1.87},
                      best["teamTotals"])
        self.assertIn({"team": "away", "point": 0.5, "side": "over", "price": 1.73},
                      best["teamTotals"])
        # The parser keeps both sides in its raw quote payload; the app's
        # display layer filters teamTotals to OVER only.
        self.assertIn({"team": "home", "point": 1.5, "side": "under", "price": 1.94},
                      best["teamTotals"])
        self.assertFalse(any(x["side"] == "under" for x in best["totals"]))

    def test_keeps_only_model_goal_lines_for_totals_and_team_totals(self):
        source = {"id": 101, "oddsBk": {"betboom": {"markets": {
            "total_goals": {"status": "open", "stage": "prematch", "stakes": {
                "over": {"lines": [
                    {"argument": 1.5, "factor": 1.7},
                    {"argument": 2.5, "factor": 1.9},
                    {"argument": 3.5, "factor": 2.1},
                    {"argument": 2.75, "factor": 2.0}
                ]},
                "under": {"lines": [
                    {"argument": 1.5, "factor": 2.2},
                    {"argument": 2.5, "factor": 1.9},
                    {"argument": 3.5, "factor": 1.7},
                    {"argument": 2.75, "factor": 1.8}
                ]}
            }},
            "total_team_1": {"status": "open", "stage": "prematch", "stakes": {
                "over": {"lines": [
                    {"argument": 0.5, "factor": 1.2},
                    {"argument": 1.5, "factor": 1.8},
                    {"argument": 2.5, "factor": 2.4},
                    {"argument": 3.5, "factor": 3.2},
                    {"argument": 1.75, "factor": 2.0}
                ]}
            }}
        }}}}
        best = parse_odds(source)["best"]
        self.assertEqual({x["point"] for x in best["totals"]}, {1.5, 2.5, 3.5})
        self.assertEqual({x["side"] for x in best["totals"]}, {"over", "under"})
        self.assertEqual({x["point"] for x in best["teamTotals"]}, {0.5, 1.5, 2.5, 3.5})

    def test_parses_double_chance_and_only_zero_handicap(self):
        source = {"id": 7, "oddsBk": {"betboom": {"markets": {
            "double_chance": {"status": "open", "stage": "prematch", "stakes": {
                "1x": {"factor": 1.3}, "x2": {"factor": 2.7}
            }},
            "handicap": {"status": "open", "stage": "prematch", "stakes": {
                "handicap_1": {"lines": [
                    {"argument": 0, "factor": 1.91},
                    {"argument": -0.5, "factor": 2.2}
                ]},
                "handicap_2": {"lines": [{"argument": 0, "factor": 1.95}]}
            }}
        }}}}
        source["oddsBk"]["pari"] = copy.deepcopy(source["oddsBk"]["betboom"])
        source["oddsBk"]["pari"]["markets"]["double_chance"]["stakes"]["1x"]["factor"] = 9.99
        quote = parse_odds(source)
        self.assertEqual(quote["best"]["doubleChance"], {"1x": 1.3, "x2": 2.7})
        self.assertEqual([book["key"] for book in quote["bookmakers"]], ["betboom"])
        self.assertEqual(quote["bestBookmakers"]["doubleChance"]["1x"], "BETBOOM")
        self.assertEqual({(x["team"], x["point"]) for x in quote["best"]["spreads"]},
                         {("home", 0.0), ("away", 0.0)})


    def test_ignores_live_markets_and_keeps_prematch_quotes(self):
        source = {"id": 99, "oddsBk": {"betboom": {"markets": {
            "total_live": {"status": "open", "stage": "live", "stakes": {
                "over": {"lines": [{"argument": 2.5, "factor": 8.5}]},
                "under": {"lines": [{"argument": 2.5, "factor": 1.05}]}
            }},
            "total": {"status": "open", "stage": "prematch", "stakes": {
                "over": {"lines": [{"argument": 2.5, "factor": 1.91}]},
                "under": {"lines": [{"argument": 2.5, "factor": 1.95}]}
            }},
            "double_chance": {"status": "open", "stage": "live", "stakes": {
                "1x": {"factor": 1.01}
            }}
        }}}}
        quote = parse_odds(source)
        self.assertEqual({(x["point"], x["side"], x["price"]) for x in quote["best"]["totals"]},
                         {(2.5, "over", 1.91), (2.5, "under", 1.95)})
        self.assertNotIn("doubleChance", quote["best"])


    def test_matches_date_only_model_fixture_on_same_calendar_day(self):
        target = {"utcDate": "2026-11-21", "home": "Manchester City", "away": "Fulham"}
        candidate = {
            "startTimestamp": 1795273200000,
            "homeTeam": {"name": "Manchester City"},
            "awayTeam": {"name": "Fulham"},
        }
        self.assertIs(match_fixture(target, [candidate]), candidate)

    def test_date_only_fixture_does_not_match_different_calendar_day(self):
        target = {"utcDate": "2026-11-21", "home": "Manchester City", "away": "Fulham"}
        candidate = {
            "startTimestamp": 1795186800000,
            "homeTeam": {"name": "Manchester City"},
            "awayTeam": {"name": "Fulham"},
        }
        self.assertIsNone(match_fixture(target, [candidate]))

    def test_exact_kickoff_keeps_six_hour_tolerance(self):
        target = {"utcDate": "2026-11-21T15:00:00Z", "home": "Manchester City", "away": "Fulham"}
        candidate = {
            "startTimestamp": 1795273200000,
            "homeTeam": {"name": "Manchester City"},
            "awayTeam": {"name": "Fulham"},
        }
        self.assertIs(match_fixture(target, [candidate]), candidate)


if __name__ == "__main__":
    unittest.main()
