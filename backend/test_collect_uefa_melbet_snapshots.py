import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_uefa_melbet_snapshots import (combine_pitchapi_sources, fetch_provider_fixtures, merge_duplicate_fixture_rows, snapshot_rows)


class UefaMelBetSnapshotTests(unittest.TestCase):
    def test_archives_only_selected_markets_for_ucl_and_uel(self):
        def fixture(match_id, league):
            return {
                "id": match_id, "leagueCode": league, "season": "2026/27",
                "date": "2026-11-21T19:00:00Z", "status": "not_started",
                "home": "Arsenal", "away": "Chelsea",
            }

        source = {
            "id": 9001,
            "startTimestamp": 1795287600000,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"melbet": {
                "updatedAt": 1794311880000,
                "updatedAtByStage": {"prematch": 1794311880000},
                "isBettingActiveByStage": {"prematch": True},
                "markets": {
                "double_chance": {"status": "open", "stage": "prematch",
                                  "stakes": {"1x": {"factor": 1.3}, "x2": {"factor": 2.7},
                                             "12": {"factor": 1.4}}},
                "total_goals": {"status": "open", "stage": "prematch",
                                "stakes": {"over": {"lines": [
                                    {"argument": 1.5, "factor": 1.2},
                                    {"argument": 2.5, "factor": 1.9},
                                    {"argument": 2.75, "factor": 2.0}]},
                                           "under": {"lines": [{"argument": 2.5, "factor": 1.9}]}}},
                "total_team_1": {"status": "open", "stage": "prematch",
                                 "stakes": {
                                     "over": {"lines": [
                                         {"argument": 0.5, "factor": 1.4},
                                         {"argument": 1.5, "factor": 1.8}]},
                                     "under": {"lines": [{"argument": 1.5, "factor": 2.0}]}
                                 }},
                "both_teams_to_score": {"status": "open", "stage": "prematch",
                                        "stakes": {"yes": {"factor": 1.75},
                                                   "no": {"factor": 2.05}}},
            }}},
        }
        cache = {"matches": {
            "ucl-1": fixture("ucl-1", "UCL"),
            "uel-1": fixture("uel-1", "UEL"),
            "pl-1": fixture("pl-1", "PL"),
            "ucl-finished": {**fixture("ucl-finished", "UCL"), "status": "finished"},
        }}
        diagnostics = {}
        rows = snapshot_rows(cache, [source], "2026-11-10T12:00:00+00:00", diagnostics)
        # Per fixture: 1X/X2, two match-total overs, two team-total overs, BTTS yes.
        self.assertEqual(len(rows), 2 * 7)
        self.assertEqual({row["league_code"] for row in rows}, {"UCL", "UEL"})
        self.assertEqual({row["market"] for row in rows},
                         {"double_chance", "match_total_over", "team_total_over", "btts"})
        self.assertTrue(all(row["bookmaker"] == "MELBET" for row in rows))
        self.assertTrue(all(row["model_probability"] is None for row in rows))
        self.assertTrue(all(row["captured_at"] == "2026-11-10T12:00:00+00:00" for row in rows))
        self.assertTrue(all(row["freshness_status"] == "fresh" for row in rows))
        self.assertTrue(all(row["bookmaker_update_age_seconds"] == 120.0 for row in rows))
        self.assertFalse(any(row.get("outcome") == "12" for row in rows))
        self.assertFalse(any(row.get("outcome") == "no" for row in rows))
        self.assertFalse(any(row.get("side") == "under" for row in rows))
        self.assertTrue(all(row["candidate_market"] and not row["reference_only"] for row in rows))
        self.assertFalse(any(row.get("point") == 2.75 for row in rows))
        self.assertEqual(diagnostics["eligible_uefa_upcoming_fixtures_in_cache"], 2)
        self.assertEqual(diagnostics["api_sport_provider_fixtures"], 1)
        self.assertEqual(diagnostics["matched_pitchapi_fixtures"], 2)
        self.assertEqual(diagnostics["matches_with_melbet_board"], 2)
        self.assertEqual(diagnostics["matches_with_active_prematch_board"], 2)
        self.assertEqual(diagnostics["matches_with_selected_markets"], 2)

    def test_attaches_model_probabilities_to_matching_quote_rows(self):
        source = {
            "id": 9001,
            "startTimestamp": 1795287600000,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"melbet": {
                "updatedAt": 1794311880000,
                "updatedAtByStage": {"prematch": 1794311880000},
                "isBettingActiveByStage": {"prematch": True},
                "markets": {
                    "double_chance": {"status": "open", "stage": "prematch",
                                      "stakes": {"1x": {"factor": 1.3}, "x2": {"factor": 2.7},
                                                 "12": {"factor": 1.4}}},
                    "total_goals": {"status": "open", "stage": "prematch",
                                    "stakes": {"over": {"lines": [
                                        {"argument": 1.5, "factor": 1.2},
                                        {"argument": 2.5, "factor": 1.9}]},
                                               "under": {"lines": [{"argument": 2.5, "factor": 1.9}]}}},
                    "total_team_1": {"status": "open", "stage": "prematch",
                                     "stakes": {"over": {"lines": [
                                         {"argument": 0.5, "factor": 1.4}]}}},
                    "both_teams_to_score": {"status": "open", "stage": "prematch",
                                            "stakes": {"yes": {"factor": 1.75},
                                                       "no": {"factor": 2.05}}},
                }},
        }}
        cache = {"matches": {
            "ucl-1": {"id": "ucl-1", "leagueCode": "UCL", "season": "2026/2027",
                      "date": "2026-11-21T19:00:00Z", "status": "not_started",
                      "home": "Arsenal", "away": "Chelsea"},
        }}
        markets = {
            "double_chance": {"1x": 0.65, "x2": 0.60, "12": 0.55},
            "match_total": {"over": {"1.5": 0.80, "2.5": 0.70},
                            "under": {"2.5": 0.30}},
            "team_total": {"home": {"over": {"0.5": 0.90}, "under": {}},
                           "away": {"over": {}, "under": {}}},
            "btts": {"yes": 0.60, "no": 0.40},
        }
        predictions = {"ucl-1": {
            "model_version": "uefa-xg-poisson-v1",
            "predicted_at": "2026-11-10T12:00:00+00:00",
            "history_max_date": "2026-11-05",
            "markets": markets,
        }}
        diagnostics = {}
        rows = snapshot_rows(cache, [source], "2026-11-10T12:00:00+00:00",
                             diagnostics, predictions)
        self.assertTrue(rows)
        self.assertTrue(all(row["model_probability_status"] == "available" for row in rows))
        self.assertTrue(all(row["model_version"] == "uefa-xg-poisson-v1" for row in rows))
        self.assertTrue(all(row["market_parser_version"] == "melbet-fulltime-v3" for row in rows))
        dc = next(row for row in rows if row["market"] == "double_chance" and row["outcome"] == "1x")
        self.assertEqual(dc["model_probability"], 0.65)
        over = next(row for row in rows if row["market"] == "match_total_over" and row["point"] == 2.5)
        self.assertEqual(over["model_probability"], 0.70)
        self.assertEqual(over["provider_market_group"], "total_goals")
        self.assertEqual(over["provider_market_slug"], "total_goals")
        self.assertEqual(diagnostics["matches_with_model_prediction"], 1)

    def test_excludes_archived_nations_league_from_active_odds_snapshots(self):
        cache = {"matches": {
            "UNL:9002": {
                "id": "UNL:9002", "provider": "API-Sport", "providerEventId": "9002",
                "leagueCode": "UNL", "season": "2026/2027",
                "date": "2026-11-10T12:00:00+00:00", "status": "not_started",
                "home": "Czechia", "away": "Spain",
            },
        }}
        diagnostics = {}
        rows = snapshot_rows(cache, [], "2026-11-10T12:02:00+00:00", diagnostics)
        self.assertEqual(rows, [])
        self.assertEqual(diagnostics["eligible_uefa_upcoming_fixtures_in_cache"], 0)

    def test_fetch_provider_fixtures_queries_both_uefa_tournaments_for_180_days(self):
        from datetime import date

        class Response:
            def __init__(self, payload):
                self.payload = payload
            def raise_for_status(self):
                pass
            def json(self):
                return self.payload

        class FakeSession:
            def __init__(self):
                self.calls = []
            def get(self, url, params=None, **kwargs):
                self.calls.append(params)
                event_id = len(self.calls)
                return Response({"matches": [{"id": event_id}], "totalPages": 1})

        session = FakeSession()
        rows = fetch_provider_fixtures("test-key", session=session, today=date(2026, 11, 11))
        tournament_calls = [params for params in session.calls if "tournament_id" in params]
        generic_calls = [params for params in session.calls if "tournament_id" not in params]
        self.assertEqual({params["tournament_id"] for params in tournament_calls}, {"7", "679", "17015"})
        self.assertEqual(len(tournament_calls), 18)
        self.assertEqual(len(generic_calls), 6)
        for params in generic_calls:
            self.assertLessEqual(
                (date.fromisoformat(params["date_to"]) -
                 date.fromisoformat(params["date_from"])).days, 30
            )
        for params in tournament_calls:
            self.assertLessEqual(
                (date.fromisoformat(params["date_to"]) -
                 date.fromisoformat(params["date_from"])).days, 30
            )
        self.assertEqual(len(rows), len({str(row["id"]) for row in rows}))

    def test_empty_provider_data_produces_no_snapshots(self):
        cache = {"matches": {"1": {
            "leagueCode": "UCL", "status": "not_started",
            "date": "2026-11-21", "home": "Arsenal", "away": "Chelsea",
        }}}
        self.assertEqual(snapshot_rows(cache, [], "2026-11-10T12:00:00Z"), [])


    def test_duplicate_fixture_keeps_richer_melbet_board(self):
        richer = {
            "id": 9010,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"melbet": {
                "markets": {
                    "double_chance": {
                        "status": "open", "stage": "prematch",
                        "stakes": {"1x": {"factor": 1.25}, "x2": {"factor": 3.2}},
                    },
                    "total_goals": {
                        "status": "open", "stage": "prematch",
                        "stakes": {"over": {"lines": [
                            {"argument": 1.5, "factor": 1.12},
                            {"argument": 2.5, "factor": 1.8},
                        ]}},
                    },
                },
            }},
        }
        poorer_duplicate = {
            "id": 9010,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"melbet": {"markets": {}}},
        }
        merged = merge_duplicate_fixture_rows(richer, poorer_duplicate)
        self.assertEqual(
            set(merged["oddsBk"]["melbet"]["markets"]),
            {"double_chance", "total_goals"},
        )
        self.assertEqual(
            merged["oddsBk"]["melbet"]["markets"]["total_goals"]["stakes"]["over"]["lines"][1]["factor"],
            1.8,
        )

    def test_duplicate_fixture_uses_richer_incoming_board(self):
        older = {
            "id": 9011,
            "oddsBk": {"melbet": {"markets": {
                "btts": {"stakes": {"yes": {"factor": 1.7}}},
            }}},
        }
        newer = {
            "id": 9011,
            "oddsBk": {"melbet": {"markets": {
                "btts": {"stakes": {"yes": {"factor": 1.8}}},
                "total_goals": {"stakes": {"over": {"factor": 1.4}}},
            }}},
        }
        merged = merge_duplicate_fixture_rows(older, newer)
        self.assertEqual(set(merged["oddsBk"]["melbet"]["markets"]), {"btts", "total_goals"})
        self.assertEqual(merged["oddsBk"]["melbet"]["markets"]["btts"]["stakes"]["yes"]["factor"], 1.8)


    def test_snapshot_collector_accepts_all_eight_competitions_and_requires_active_board(self):
        leagues = ("ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL")
        cache = {"matches": {}}
        provider = []
        for index, league in enumerate(leagues):
            fixture_id = f"{league}-fixture"
            cache["matches"][fixture_id] = {
                "id": fixture_id, "leagueCode": league, "season": "2026/27",
                "date": "2026-11-21T19:00:00Z", "status": "not_started",
                "home": f"Home {index}", "away": f"Away {index}",
            }
            provider.append({
                "id": 9000 + index,
                "startTimestamp": 1795287600000,
                "homeTeam": {"name": f"Home {index}"},
                "awayTeam": {"name": f"Away {index}"},
                "oddsBk": {"melbet": {
                    "updatedAt": 1794311880000,
                    "updatedAtByStage": {"prematch": 1794311880000},
                    "isBettingActiveByStage": {"prematch": index != 0},
                    "markets": {
                        "double_chance": {"status": "open", "stage": "prematch",
                                          "stakes": {"1x": {"factor": 1.8}}}
                    }
                }}
            })
        diagnostics = {}
        rows = snapshot_rows(cache, provider, "2026-11-10T12:00:00+00:00", diagnostics, {})
        priced_rows = [row for row in rows if row["market"] != "board_status"]
        self.assertEqual({row["league_code"] for row in priced_rows}, set(leagues[1:]))
        inactive_markers = [row for row in rows if row["market"] == "board_status"]
        self.assertEqual(len(inactive_markers), 1)
        self.assertFalse(inactive_markers[0]["prematch_active"])
        self.assertEqual(diagnostics["eligible_supported_upcoming_fixtures_in_cache"], 8)
        self.assertEqual(diagnostics["matches_with_active_prematch_board"], 7)



    def test_combines_domestic_and_uefa_caches_without_admitting_unpaired_xg(self):
        uefa_cache = {"matches": {
            "u1": {"id": "u1", "leagueCode": "UCL", "status": "not_started",
                   "date": "2026-12-01", "home": "A", "away": "B"}
        }}
        domestic_cache = {"matches": {
            "d1": {"id": "d1", "leagueCode": "ENG", "status": "finished",
                   "date": "2026-10-01", "home": "C", "away": "D",
                   "homeScore": 2, "awayScore": 1, "homeXg": 1.7, "awayXg": 0.8},
            "d2": {"id": "d2", "leagueCode": "ENG", "status": "finished",
                   "date": "2026-10-02", "home": "C", "away": "E",
                   "homeScore": 1, "awayScore": 0, "homeXg": 1.1, "awayXg": None},
            "d3": {"id": "d3", "leagueCode": "ENG", "status": "finished",
                   "date": "2026-10-03", "home": "C", "away": "F",
                   "homeScore": 1, "awayScore": 0, "homeXg": 1.0, "awayXg": 0.2,
                   "hasPlayoff": True},
        }}
        uefa_history = {"matches": [{
            "id": "u-old", "leagueCode": "UEL", "date": "2026-09-01",
            "home": "G", "away": "H", "homeXg": 1.4, "awayXg": 0.7,
            "pairedXg": True,
        }]}
        fixtures, history = combine_pitchapi_sources(uefa_cache, domestic_cache, uefa_history)
        self.assertIn("UEFA:u1", fixtures["matches"])
        self.assertIn("DOM:d1", fixtures["matches"])
        self.assertEqual({row["id"] for row in history["matches"]}, {"DOM:d1", "u-old"})
        self.assertTrue(all(row["pairedXg"] is True for row in history["matches"]))


    def test_newer_inactive_melbet_board_wins_over_richer_older_duplicate(self):
        older = {"id": 9100, "oddsBk": {"melbet": {
            "updatedAtByStage": {"prematch": 1794311880000},
            "isBettingActiveByStage": {"prematch": True},
            "markets": {"double_chance": {"stakes": {"1x": {"factor": 1.8}, "x2": {"factor": 2.0}}}}
        }}}
        newer = {"id": 9100, "oddsBk": {"melbet": {
            "updatedAtByStage": {"prematch": 1794315480000},
            "isBettingActiveByStage": {"prematch": False},
            "markets": {}
        }}}
        merged = merge_duplicate_fixture_rows(older, newer)
        board = merged["oddsBk"]["melbet"]
        self.assertFalse(board["isBettingActiveByStage"]["prematch"])
        self.assertEqual(board["markets"], {})


if __name__ == "__main__":
    unittest.main()
