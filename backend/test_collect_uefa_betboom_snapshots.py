import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_uefa_betboom_snapshots import snapshot_rows


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
        self.assertEqual(len(rows), 2 * 3 + 2 * 3 + 2 * 3 + 2 * 2)  # DC, totals, ITB, BTTS for two UEFA fixtures
        self.assertEqual({row["league_code"] for row in rows}, {"UCL", "UEL"})
        self.assertEqual({row["market"] for row in rows},
                         {"double_chance", "match_total_over", "match_total_under_reference",
                          "team_total_over", "team_total_under_reference", "btts"})
        self.assertTrue(all(row["bookmaker"] == "BETBOOM" for row in rows))
        self.assertTrue(all(row["model_probability"] is None for row in rows))
        self.assertTrue(all(row["captured_at"] == "2026-11-10T12:00:00+00:00" for row in rows))
        self.assertTrue(all(row["freshness_status"] == "fresh" for row in rows))
        self.assertTrue(all(row["bookmaker_update_age_seconds"] == 120.0 for row in rows))
        dc_12 = [row for row in rows if row.get("outcome") == "12"]
        under_refs = [row for row in rows if row.get("reference_only") and row.get("side") == "under"]
        self.assertEqual(len(dc_12), 2)
        self.assertTrue(all(not row["candidate_market"] for row in dc_12))
        self.assertTrue(all(not row["candidate_market"] and row["side"] == "under" for row in under_refs))
        self.assertTrue(any(row["market"] == "match_total_under_reference" for row in under_refs))
        self.assertTrue(any(row["market"] == "team_total_under_reference" for row in under_refs))
        self.assertFalse(any(row.get("point") == 2.75 for row in rows))
        self.assertEqual(diagnostics["eligible_uefa_upcoming_fixtures_in_cache"], 2)
        self.assertEqual(diagnostics["api_sport_provider_fixtures"], 1)
        self.assertEqual(diagnostics["matched_pitchapi_fixtures"], 2)
        self.assertEqual(diagnostics["matches_with_betboom_board"], 2)
        self.assertEqual(diagnostics["matches_with_active_prematch_board"], 2)
        self.assertEqual(diagnostics["matches_with_selected_markets"], 2)

    def test_empty_provider_data_produces_no_snapshots(self):
        cache = {"matches": {"1": {
            "leagueCode": "UCL", "status": "not_started",
            "date": "2026-11-21", "home": "Arsenal", "away": "Chelsea",
        }}}
        self.assertEqual(snapshot_rows(cache, [], "2026-11-10T12:00:00Z"), [])


if __name__ == "__main__":
    unittest.main()
