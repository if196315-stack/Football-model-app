from datetime import datetime, timezone
import unittest

from build_uefa_app_feed import build_feed, build_odds


class BuildUefaAppFeedTests(unittest.TestCase):
    def test_build_odds_uses_only_fresh_full_time_melbet_rows(self):
        rows = [
            {"captured_at": "2026-11-10T10:00:20+00:00", "bookmaker_updated_at": "2026-11-10T10:00:00+00:00", "freshness_status": "fresh",
             "bookmaker_update_age_seconds": 20, "market_parser_version": "melbet-fulltime-v2",
             "market": "team_total_over", "team": "home", "point": 1.5, "side": "over", "price": 1.9},
            {"captured_at": "2026-11-10T10:00:20+00:00", "bookmaker_updated_at": "2026-11-10T09:45:00+00:00", "freshness_status": "stale",
             "bookmaker_update_age_seconds": 900, "market_parser_version": "melbet-fulltime-v2",
             "market": "btts", "outcome": "yes", "price": 1.8},
            {"captured_at": "2026-11-10T09:00:20+00:00", "bookmaker_updated_at": "2026-11-10T09:00:00+00:00", "freshness_status": "fresh",
             "bookmaker_update_age_seconds": 10, "market_parser_version": "melbet-fulltime-v2",
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



    def test_uecl_calendar_keeps_all_fixtures_when_melbet_covers_only_subset(self):
        now = datetime.now(timezone.utc)
        captured_at = now.isoformat()
        fixtures = {
            f"uecl-{i}": {
                "leagueCode": "UECL", "status": "not_started",
                "date": f"2026-12-{20 + i:02d}T18:00:00Z",
                "home": f"Home {i}", "away": f"Away {i}",
            }
            for i in range(1, 4)
        }
        prediction = {
            "markets": {
                "double_chance": {"1x": 0.7, "x2": 0.6},
                "match_total": {"over": {"1.5": 0.8, "2.5": 0.6, "3.5": 0.4}},
                "team_total": {
                    "home": {"over": {"0.5": 0.8, "1.5": 0.5, "2.5": 0.2, "3.5": 0.1}},
                    "away": {"over": {"0.5": 0.7, "1.5": 0.4, "2.5": 0.2, "3.5": 0.1}},
                },
                "btts": {"yes": 0.55, "no": 0.45},
            },
            "home_lambda": 1.4, "away_lambda": 1.1, "model_version": "test",
        }
        odds_rows = [{
            "fixture_id": "uecl-1", "league_code": "UECL",
            "captured_at": captured_at, "bookmaker_updated_at": captured_at,
            "freshness_status": "fresh", "market_parser_version": "melbet-fulltime-v2",
            "market": "btts", "outcome": "yes", "price": 1.9,
        }]

        feed = build_feed(
            {"matches": fixtures}, {"matches": []}, odds_rows, [], [],
            predictions={"uecl-1": prediction}, updated_at=captured_at,
        )
        matches = feed["competitions"][2]["matches"]
        self.assertEqual(len(matches), 3)
        by_id = {match["id"]: match for match in matches}
        self.assertIsNotNone(by_id["uecl-1"]["prediction"])
        self.assertIsNotNone(by_id["uecl-1"]["odds"])
        self.assertIsNone(by_id["uecl-2"]["odds"])
        self.assertIsNone(by_id["uecl-3"]["odds"])
        self.assertIsNone(by_id["uecl-2"]["prediction"])
        self.assertIsNone(by_id["uecl-3"]["prediction"])

    def test_freezes_pre_kickoff_prediction_and_adds_final_result_without_rewriting(self):
        kickoff = "2026-11-20T18:00:00Z"
        now_before = datetime(2026, 11, 19, 12, 0, tzinfo=timezone.utc)
        prediction = {
            "markets": {
                "double_chance": {"1x": 0.71, "x2": 0.62},
                "match_total": {"over": {"1.5": 0.80, "2.5": 0.61, "3.5": 0.39}},
                "team_total": {
                    "home": {"over": {"0.5": 0.81, "1.5": 0.56, "2.5": 0.27, "3.5": 0.10}},
                    "away": {"over": {"0.5": 0.72, "1.5": 0.43, "2.5": 0.19, "3.5": 0.07}},
                },
                "btts": {"yes": 0.58, "no": 0.42},
            },
            "home_lambda": 1.5, "away_lambda": 1.0, "model_version": "test",
            "history_max_date": "2026-11-18",
        }
        upcoming_cache = {"matches": {
            "uecl-archive-1": {"leagueCode": "UECL", "status": "scheduled",
                               "date": kickoff, "home": "Alpha", "away": "Beta"}
        }}
        first = build_feed(
            upcoming_cache, {"matches": []}, [], [], [],
            predictions={"uecl-archive-1": prediction},
            now=now_before,
        )
        key = "UECL:uecl-archive-1"
        frozen = first["predictionArchive"]["matches"][key]["prediction"]
        self.assertEqual(frozen["over25"], 0.61)
        self.assertEqual(first["evaluationMatches"], [])

        finished_cache = {"matches": {
            "uecl-archive-1": {"leagueCode": "UECL", "status": "finished",
                               "date": kickoff, "home": "Alpha", "away": "Beta",
                               "homeScore": 2, "awayScore": 1}
        }}
        second = build_feed(
            finished_cache, {"matches": []}, [], [], [],
            predictions={}, previous_archive=first["predictionArchive"],
            now=datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc),
        )
        self.assertEqual(second["predictionArchive"]["matches"][key]["prediction"], frozen)
        self.assertEqual(len(second["evaluationMatches"]), 1)
        evaluated = second["evaluationMatches"][0]
        self.assertEqual(evaluated["homeScore"], 2)
        self.assertEqual(evaluated["awayScore"], 1)
        self.assertEqual(evaluated["leagueCode"], "UECL")

if __name__ == "__main__":
    unittest.main()
