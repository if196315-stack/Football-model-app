from datetime import datetime, timezone, timedelta
import unittest

from build_uefa_app_feed import build_feed, build_odds


class BuildUefaAppFeedTests(unittest.TestCase):
    def test_build_odds_uses_only_fresh_full_time_melbet_rows(self):
        rows = [
            {"captured_at": "2026-11-10T10:00:20+00:00", "bookmaker_updated_at": "2026-11-10T10:00:00+00:00", "freshness_status": "fresh", "prematch_active": True, "bookmaker": "MELBET", "source": "API-Sport",
             "bookmaker_update_age_seconds": 20, "market_parser_version": "melbet-fulltime-v3",
             "market": "team_total_over", "team": "home", "point": 1.5, "side": "over", "price": 1.9},
            {"captured_at": "2026-11-10T10:00:20+00:00", "bookmaker_updated_at": "2026-11-09T21:00:00+00:00", "freshness_status": "stale", "prematch_active": True, "bookmaker": "MELBET", "source": "API-Sport",
             "bookmaker_update_age_seconds": 46800, "market_parser_version": "melbet-fulltime-v2",
             "market": "btts", "outcome": "yes", "price": 1.8},
            {"captured_at": "2026-11-10T09:00:20+00:00", "bookmaker_updated_at": "2026-11-10T09:00:00+00:00", "freshness_status": "fresh", "prematch_active": True, "bookmaker": "MELBET", "source": "API-Sport",
             "bookmaker_update_age_seconds": 10, "market_parser_version": "melbet-fulltime-v2",
             "market": "btts", "outcome": "yes", "price": 2.0},
        ]
        odds = build_odds(rows, datetime(2026, 11, 10, 10, 0, 20, tzinfo=timezone.utc))
        self.assertIsNotNone(odds)
        self.assertEqual(odds["bookmaker"], "MELBET")
        self.assertEqual(odds["best"]["teamTotals"][0]["price"], 1.9)
        self.assertEqual(odds["best"]["btts"], {})
        within_twelve_hours = build_odds(
            [rows[0]], datetime(2026, 11, 10, 10, 10, 0, tzinfo=timezone.utc)
        )
        self.assertIsNotNone(within_twelve_hours)
        stale = build_odds(
            [rows[0]], datetime(2026, 11, 10, 23, 0, 0, tzinfo=timezone.utc)
        )
        self.assertIsNone(stale)

    def test_fixture_only_refresh_preserves_recent_published_odds_and_expires_them(self):
        now = datetime(2026, 11, 10, 10, 0, tzinfo=timezone.utc)
        cache = {"matches": {
            "uecl-preserve-1": {"leagueCode": "UECL", "status": "scheduled",
                                "date": "2026-11-20T18:00:00Z", "home": "Alpha", "away": "Beta"}
        }}
        rows = [{
            "fixture_id": "uecl-preserve-1", "league_code": "UECL",
            "captured_at": now.isoformat(), "bookmaker_updated_at": now.isoformat(),
            "freshness_status": "fresh", "prematch_active": True,
            "bookmaker": "MELBET", "source": "API-Sport",
            "market_parser_version": "melbet-fulltime-v3",
            "market": "double_chance", "outcome": "1x", "price": 1.85,
        }]
        first = build_feed(cache, {"matches": []}, rows, [], [],
                           predictions={}, now=now)
        first_match = first["competitions"][2]["matches"][0]
        self.assertEqual(first_match["odds"]["best"]["doubleChance"]["1x"], 1.85)

        refreshed = build_feed(cache, {"matches": []}, [], [], [],
                              predictions={}, now=now + timedelta(hours=2),
                              previous_feed=first)
        refreshed_match = refreshed["competitions"][2]["matches"][0]
        self.assertEqual(refreshed_match["odds"]["best"]["doubleChance"]["1x"], 1.85)
        self.assertEqual(refreshed_match["odds"]["bookmakerUpdateAgeSeconds"], 7200)

        expired = build_feed(cache, {"matches": []}, [], [], [],
                            predictions={}, now=now + timedelta(hours=13),
                            previous_feed=first)
        self.assertIsNone(expired["competitions"][2]["matches"][0]["odds"])

        inactive = [{
            "fixture_id": "uecl-preserve-1", "league_code": "UECL",
            "captured_at": (now + timedelta(hours=3)).isoformat(),
            "bookmaker_updated_at": (now + timedelta(hours=3)).isoformat(),
            "freshness_status": "fresh", "prematch_active": False,
            "bookmaker": "MELBET", "source": "API-Sport",
            "market_parser_version": "melbet-fulltime-v3",
            "market": "board_status", "price": None,
        }]
        disabled = build_feed(cache, {"matches": []}, inactive, [], [],
                              predictions={}, now=now + timedelta(hours=3),
                              previous_feed=first)
        self.assertIsNone(disabled["competitions"][2]["matches"][0]["odds"])

    def test_upcoming_top_excludes_unsupported_pending_market_lines(self):
        cache = {"matches": {
            "top-market-filter": {
                "leagueCode": "UCL", "status": "scheduled",
                "date": "2026-12-20T18:00:00Z", "home": "Alpha", "away": "Beta"
            }
        }}
        bets = [
            {"signal_id": "team-25", "fixture_id": "top-market-filter",
             "paper_only": True, "decision": "BET", "market": "team_total_over",
             "point": 2.5, "team": "home"},
            {"signal_id": "match-35", "fixture_id": "top-market-filter",
             "paper_only": True, "decision": "BET", "market": "match_total_over",
             "point": 3.5},
            {"signal_id": "btts-yes", "fixture_id": "top-market-filter",
             "paper_only": True, "decision": "BET", "market": "btts",
             "outcome": "yes"},
        ]
        feed = build_feed(cache, {"matches": []}, [], bets, [], predictions={})
        match = feed["competitions"][0]["matches"][0]
        self.assertEqual([signal["signal_id"] for signal in match["paperSignals"]],
                         ["btts-yes"])

    def test_retired_signal_ids_are_never_published_even_if_settled(self):
        cache = {"matches": {
            "m_1NQ9Kt": {
                "leagueCode": "UEL", "status": "scheduled",
                "date": "2026-11-26T18:00:00Z",
                "home": "Union St.Gilloise", "away": "Lech Poznań"
            }
        }}
        bets = [
            {"signal_id": "c3b279f109074fcbbf0c", "fixture_id": "m_1NQ9Kt",
             "paper_only": True, "decision": "BET", "market": "match_total_over",
             "point": 3.5},
            {"signal_id": "28ecfccda8f24626566b", "fixture_id": "m_1NQ9Kt",
             "paper_only": True, "decision": "BET", "market": "team_total_over",
             "point": 2.5, "team": "home"},
        ]
        settlements = [
            {"signal_id": "c3b279f109074fcbbf0c", "result": "WIN"},
            {"signal_id": "28ecfccda8f24626566b", "result": "WIN"},
        ]
        feed = build_feed(cache, {"matches": []}, [], bets, settlements,
                          predictions={})
        all_signals = [
            signal
            for competition in feed["competitions"]
            for match in competition["matches"]
            for signal in match.get("paperSignals", [])
        ]
        self.assertEqual(all_signals, [])

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



    def test_uecl_calendar_does_not_bypass_strict_market_publication_filter(self):
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
            "freshness_status": "fresh", "prematch_active": True, "bookmaker": "MELBET", "source": "API-Sport", "market_parser_version": "melbet-fulltime-v2",
            "market": "btts", "outcome": "yes", "price": 1.9,
        }]

        feed = build_feed(
            {"matches": fixtures}, {"matches": []}, odds_rows, [], [],
            predictions={"uecl-1": prediction}, updated_at=captured_at,
        )
        matches = feed["competitions"][2]["matches"]
        # The feed retains the calendar, but the Android publication gate
        # must not expose a scheduled match unless every strategy market is quoted.
        self.assertEqual(len(matches), 3)
        by_id = {match["id"]: match for match in matches}
        self.assertIsNotNone(by_id["uecl-1"]["odds"])
        self.assertIsNone(by_id["uecl-2"]["odds"])
        self.assertIsNone(by_id["uecl-3"]["odds"])

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

    def test_archived_scheduled_fixture_survives_after_kickoff_day_until_finished(self):
        kickoff = "2026-11-24T17:45:00Z"
        key = "UCL:ucl-delayed"
        frozen_prediction = {"over25": 0.61, "modelVersion": "frozen"}
        previous_archive = {"version": 1, "matches": {
            key: {"id": "ucl-delayed", "leagueCode": "UCL", "status": "SCHEDULED",
                  "prediction": frozen_prediction, "home": "Galatasaray",
                  "away": "Aston Villa", "utcDate": kickoff}
        }}
        cache = {"matches": {
            "ucl-delayed": {"leagueCode": "UCL", "status": "scheduled",
                            "date": kickoff, "home": "Galatasaray",
                            "away": "Aston Villa"}
        }}
        feed = build_feed(
            cache, {"matches": []}, [], [], [], predictions={},
            previous_archive=previous_archive,
            now=datetime(2026, 11, 25, 12, 0, tzinfo=timezone.utc),
        )
        match = feed["competitions"][0]["matches"][0]
        self.assertEqual(match["id"], "ucl-delayed")
        self.assertEqual(match["status"], "SCHEDULED")
        self.assertEqual(match["prediction"], frozen_prediction)

    def test_pitchapi_in_progress_aliases_are_normalized_to_live(self):
        from build_uefa_app_feed import app_match_status
        for raw in ("in_progress", "inprogress", "in progress", "in-play", "1h", "2h",
                    "first_half", "second_half", "in_play_first_half", "in_play_second_half"):
            self.assertEqual(app_match_status(raw), "IN_PLAY", raw)
        for raw in ("paused", "halftime", "half_time", "break"):
            self.assertEqual(app_match_status(raw), "PAUSED", raw)

    def test_live_feed_preserves_elapsed_and_added_minutes(self):
        cache = {"matches": {
            "ucl-clock": {"leagueCode": "UCL", "status": "in_play",
                          "date": "2026-11-24T19:00:00Z", "home": "Alpha",
                          "away": "Beta", "homeScore": 2, "awayScore": 1,
                          "elapsed": 67, "elapsedExtra": 3},
        }}
        feed = build_feed(cache, {"matches": []}, [], [], [], predictions={})
        match = feed["competitions"][0]["matches"][0]
        self.assertEqual(match["elapsed"], 67)
        self.assertEqual(match["elapsedExtra"], 3)

    def test_live_and_paused_fixtures_are_preserved_in_uefa_feed_without_paper_bets(self):
        cache = {"matches": {
            "ucl-live": {"leagueCode": "UCL", "status": "in_play",
                         "date": "2026-11-24T19:00:00Z", "home": "Alpha",
                         "away": "Beta", "homeScore": 2, "awayScore": 1},
            "uel-break": {"leagueCode": "UEL", "status": "paused",
                          "date": "2026-11-24T19:00:00Z", "home": "Gamma",
                          "away": "Delta", "homeScore": 0, "awayScore": 0},
            "uecl-future": {"leagueCode": "UECL", "status": "not_started",
                            "date": "2026-11-25T19:00:00Z", "home": "Epsilon",
                            "away": "Zeta"},
        }}
        feed = build_feed(cache, {"matches": []}, [], [], [], predictions={})
        by_code = {c["code"]: c["matches"] for c in feed["competitions"]}
        self.assertEqual(len(by_code["UCL"]), 1)
        self.assertEqual(by_code["UCL"][0]["status"], "IN_PLAY")
        self.assertEqual(by_code["UCL"][0]["homeScore"], 2)
        self.assertEqual(by_code["UCL"][0]["awayScore"], 1)
        self.assertEqual(len(by_code["UEL"]), 1)
        self.assertEqual(by_code["UEL"][0]["status"], "PAUSED")
        self.assertEqual(len(by_code["UECL"]), 1)
        self.assertEqual(by_code["UECL"][0]["status"], "SCHEDULED")
        self.assertIsNone(by_code["UECL"][0]["prediction"])

    def test_new_inactive_board_marker_invalidates_older_active_snapshot(self):
        rows = [
            {"captured_at": "2026-11-10T09:00:00+00:00",
             "bookmaker_updated_at": "2026-11-10T08:59:00+00:00",
             "freshness_status": "fresh", "prematch_active": True, "bookmaker": "MELBET", "source": "API-Sport",
             "market_parser_version": "melbet-fulltime-v2",
             "market": "double_chance", "outcome": "1x", "price": 1.8},
            {"captured_at": "2026-11-10T10:00:00+00:00",
             "bookmaker_updated_at": "2026-11-10T09:59:00+00:00",
             "freshness_status": "fresh", "prematch_active": False, "bookmaker": "MELBET", "source": "API-Sport",
             "market_parser_version": "melbet-fulltime-v2",
             "market": "board_status", "price": None},
        ]
        odds = build_odds(rows, datetime(2026, 11, 10, 10, 0, 0, tzinfo=timezone.utc))
        self.assertIsNone(odds)


if __name__ == "__main__":
    unittest.main()
