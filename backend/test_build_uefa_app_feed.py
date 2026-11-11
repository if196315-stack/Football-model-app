from datetime import datetime, timezone
import unittest

from build_uefa_app_feed import build_feed, build_odds


class BuildUefaAppFeedTests(unittest.TestCase):
    def test_build_odds_uses_only_fresh_full_time_betboom_rows(self):
        rows = [
            {"captured_at": "2026-11-10T10:00:20+00:00", "bookmaker_updated_at": "2026-11-10T10:00:00+00:00", "freshness_status": "fresh",
             "bookmaker_update_age_seconds": 20, "market_parser_version": "betboom-fulltime-v2",
             "market": "team_total_over", "team": "home", "point": 1.5, "side": "over", "price": 1.9},
            {"captured_at": "2026-11-10T10:00:20+00:00", "bookmaker_updated_at": "2026-11-10T09:45:00+00:00", "freshness_status": "stale",
             "bookmaker_update_age_seconds": 900, "market_parser_version": "betboom-fulltime-v2",
             "market": "btts", "outcome": "yes", "price": 1.8},
            {"captured_at": "2026-11-10T09:00:20+00:00", "bookmaker_updated_at": "2026-11-10T09:00:00+00:00", "freshness_status": "fresh",
             "bookmaker_update_age_seconds": 10, "market_parser_version": "betboom-fulltime-v2",
             "market": "btts", "outcome": "yes", "price": 2.0},
        ]
        odds = build_odds(rows, datetime(2026, 11, 10, 10, 0, 20, tzinfo=timezone.utc))
        self.assertIsNotNone(odds)
        self.assertEqual(odds["best"]["teamTotals"][0]["price"], 1.9)
        self.assertEqual(odds["best"]["btts"], {})
        stale = build_odds([rows[0]], datetime(2026, 11, 10, 10, 10, 0, tzinfo=timezone.utc))
        self.assertIsNone(stale)

    def test_feed_contains_only_active_club_competitions_and_settled_paper_signal(self):
        cache = {"matches": {
            "ucl-1": {"leagueCode": "UCL", "status": "finished", "date": "2026-11-01",
                      "home": "A", "away": "B", "homeScore": 2, "awayScore": 0},
            "uel-1": {"leagueCode": "UEL", "status": "not_started", "date": "2026-11-20",
                      "home": "C", "away": "D"},
            "uecl-1": {"leagueCode": "UECL", "status": "not_started", "date": "2026-11-21",
                       "home": "E", "away": "F", "provider": "PitchAPI"},
            "UNL:1": {"leagueCode": "UNL", "status": "not_started", "date": "2026-11-22",
                      "home": "Czechia", "away": "Spain", "provider": "API-Sport"},
        }}
        history = {"matches": []}
        bets = [{"signal_id": "s1", "fixture_id": "ucl-1", "league_code": "UCL",
                 "paper_only": True, "decision": "BET", "market": "btts", "outcome": "yes",
                 "price": 1.8, "model_probability": 0.65, "edge": 0.08, "expected_value": 0.17}]
        settlements = [{"signal_id": "s1", "result": "LOSS", "profit_units": -1.0,
                        "home_score": 2, "away_score": 0}]
        feed = build_feed(cache, history, [], bets, settlements, predictions={})
        self.assertEqual([c["code"] for c in feed["competitions"]], ["UCL", "UEL", "UECL"])
        ucl_match = feed["competitions"][0]["matches"][0]
        self.assertEqual(ucl_match["paperSignals"][0]["result"], "LOSS")
        self.assertEqual(ucl_match["status"], "FINISHED")
        self.assertEqual(feed["competitions"][1]["matches"], [])
        self.assertEqual(feed["competitions"][2]["matches"][0]["home"], "E")
        self.assertIsNone(feed["competitions"][2]["matches"][0]["prediction"])
        self.assertNotIn("UNL", [c["code"] for c in feed["competitions"]])


if __name__ == "__main__":
    unittest.main()
