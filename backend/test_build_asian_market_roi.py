import unittest
from build_asian_market_roi import market_key, market_result, select_quotes, build


class AsianMarketRoiTests(unittest.TestCase):
    def snap(self, market, team, point, **extra):
        return {"bookmaker": "MELBET", "source": "API-Sport", "prematch_active": True,
                "freshness_status": "fresh", "market_parser_version": "melbet-fulltime-v3",
                "league_code": "ENG", "fixture_id": "101", "fixture_namespace": "DOM",
                "kickoff_utc": "2026-11-20T19:00:00Z", "home_team": "Alpha", "away_team": "Beta",
                "captured_at": "2026-11-10T12:00:00+00:00",
                "model_prediction_time": "2026-11-10T12:00:00+00:00",
                "model_version": "xg-poisson-v2", "model_history_max_date": "2026-11-09",
                "price": 1.9, "model_probability": 0.5, "model_push_probability": 0.2,
                "model_loss_probability": 0.3, "market": market, "team": team,
                "point": point, "side": "over", **extra}

    def test_market_mapping_and_results(self):
        self.assertEqual(market_key(self.snap("team_total_over", "home", 1.0)), "home_itb10")
        self.assertEqual(market_key(self.snap("team_total_over", "away", 1.0)), "away_itb10")
        self.assertEqual(market_key(self.snap("handicap", "home", 0.0)), "home_f0")
        self.assertEqual(market_key(self.snap("handicap", "away", 0.0)), "away_f0")
        self.assertEqual(market_key(self.snap("handicap", "home", 1.0)), "home_fplus1")
        self.assertEqual(market_key(self.snap("handicap", "away", 1.0)), "away_fplus1")
        self.assertEqual(market_result({"market_key": "home_itb10"}, 1, 0), "PUSH")
        self.assertEqual(market_result({"market_key": "away_itb10"}, 0, 2), "WIN")
        self.assertEqual(market_result({"market_key": "home_f0"}, 1, 1), "PUSH")
        self.assertEqual(market_result({"market_key": "away_f0"}, 2, 1), "LOSS")
        self.assertEqual(market_result({"market_key": "home_fplus1"}, 0, 2), "PUSH")
        self.assertEqual(market_result({"market_key": "away_fplus1"}, 0, 2), "WIN")

    def test_freezes_melbet_quotes_and_settles_push_as_zero_profit(self):
        snapshots = [self.snap("team_total_over", "home", 1.0)]
        domestic = {"evaluationMatches": [{"id": "101", "status": "FINISHED",
                                          "homeScore": 1, "awayScore": 0}]}
        report, quotes = build(domestic, {}, snapshots, [])
        self.assertEqual(len(quotes), 1)
        self.assertEqual(report["records"][0]["result"], "PUSH")
        self.assertEqual(report["records"][0]["profit_units"], 0.0)
        self.assertEqual(report["markets"]["home_itb10"]["pushes"], 1)
        self.assertEqual(report["markets"]["home_itb10"]["roi"], 0.0)

    def test_ignores_non_melbet_stale_and_incomplete_probability_rows(self):
        good = self.snap("handicap", "home", 1.0)
        other = {**good, "bookmaker": "OTHER"}
        stale = {**good, "freshness_status": "stale"}
        bad_probs = {**good, "model_loss_probability": None}
        self.assertEqual(select_quotes([other, stale, bad_probs], []), [])


if __name__ == "__main__":
    unittest.main()
