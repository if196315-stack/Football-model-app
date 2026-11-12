import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_odds_snapshots import snapshot_rows
from evaluate_market_observations import evaluate_rows, market_result


class MarketObservationTests(unittest.TestCase):
    def test_snapshot_keeps_model_probability_for_reference_under(self):
        data = {"modelVersion": "test-model", "competitions": [{
            "code": "PL", "matches": [{
                "id": "fixture-1", "utcDate": "2026-11-12T19:00:00Z",
                "home": "Arsenal", "away": "Chelsea",
                "prediction": {"under25": 0.42, "over25": 0.58},
                "odds": {"bookmaker": "MELBET", "updatedAt": "2026-11-10T10:00:00Z",
                         "bookmakerUpdatedAt": "2026-11-10T10:00:00Z",
                         "freshnessStatus": "fresh",
                         "bookmakers": [{"key": "melbet", "title": "MELBET",
                                         "totals": [{"side": "under", "point": 2.5, "price": 1.8}]}]}
            }]
        }]}
        rows = list(snapshot_rows(data, "2026-11-10T10:01:00Z"))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["market"], "match_total")
        self.assertEqual(rows[0]["side"], "under")
        self.assertFalse(rows[0]["candidate_market"])
        self.assertEqual(rows[0]["model_probability"], 0.42)
        self.assertEqual(rows[0]["model_version"], "test-model")

    def test_result_evaluation_is_deduplicated_and_uses_snapshot_price(self):
        snapshot = {"captured_at": "2026-11-10T10:00:00Z", "fixture_id": "f1",
                    "market": "btts", "outcome": "no", "price": 2.0,
                    "league_code": "ENG", "home_team": "A", "away_team": "B",
                    "model_probability": 0.6}
        archive = {"matches": {"f1": {"status": "FINISHED", "homeScore": 1, "awayScore": 0}}}
        rows = evaluate_rows([snapshot], archive, [])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["result"], "WIN")
        self.assertEqual(rows[0]["profit_units"], 1.0)
        self.assertEqual(evaluate_rows([snapshot], archive, rows), [])

    def test_market_results_cover_under_and_zero_handicap_push(self):
        self.assertEqual(market_result({"market": "match_total", "side": "under", "point": 2.5}, 1, 1), "WIN")
        self.assertEqual(market_result({"market": "handicap", "team": "home", "point": 0}, 2, 2), "PUSH")


    def test_dnb_settlement_is_win_loss_or_push_for_both_teams(self):
        home = {"market": "handicap", "team": "home", "point": 0}
        away = {"market": "handicap", "team": "away", "point": 0}
        self.assertEqual(market_result(home, 2, 1), "WIN")
        self.assertEqual(market_result(home, 1, 2), "LOSS")
        self.assertEqual(market_result(home, 1, 1), "PUSH")
        self.assertEqual(market_result(away, 1, 2), "WIN")
        self.assertEqual(market_result(away, 2, 1), "LOSS")
        self.assertEqual(market_result(away, 1, 1), "PUSH")


if __name__ == "__main__":
    unittest.main()
