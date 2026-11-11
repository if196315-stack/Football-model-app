import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_uefa_betboom_snapshots import fetch_provider_fixtures, snapshot_rows


class UefaBetBoomSnapshotTests(unittest.TestCase):
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
            "oddsBk": {"betboom": {
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
        self.assertTrue(all(row["bookmaker"] == "BETBOOM" for row in rows))
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
        self.assertEqual(diagnostics["matches_with_betboom_board"], 2)
        self.assertEqual(diagnostics["matches_with_active_prematch_board"], 2)
        self.assertEqual(diagnostics["matches_with_selected_markets"], 2)

    def test_attaches_model_probabilities_to_matching_quote_rows(self):
        source = {
            "id": 9001,
            "startTimestamp": 1795287600000,
            "homeTeam": {"name": "Arsenal"},
            "awayTeam": {"name": "Chelsea"},
            "oddsBk": {"betboom": {
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
        self.assertTrue(all(row["market_parser_version"] == "betboom-fulltime-v2" for row in rows))
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
        self.assertEqual({params["tournament_id"] for params in tournament_calls}, {"7", "679", "17015"})
        self.assertEqual(len(tournament_calls), 18)
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


if __name__ == "__main__":
    unittest.main()
