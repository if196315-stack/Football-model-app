import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apply_melbet_snapshots import apply_snapshots


class ApplyMelBetSnapshotsTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 11, 22, 12, 0, tzinfo=timezone.utc)

    def prediction(self):
        return {"1x": 0.72, "x2": 0.61}

    def snapshot(self, fixture_id, *, age_seconds=60, active=True, market="double_chance",
                 outcome="1x", price=1.8, league="ENG"):
        captured = self.now - timedelta(seconds=10)
        updated = self.now - timedelta(seconds=age_seconds)
        return {
            "schema_version": 1,
            "captured_at": captured.isoformat(),
            "bookmaker_updated_at": updated.isoformat(),
            "bookmaker_update_age_seconds": age_seconds,
            "freshness_status": "fresh" if age_seconds <= 12 * 60 * 60 else "stale",
            "prematch_active": active,
            "source": "API-Sport",
            "bookmaker": "MELBET",
            "market_parser_version": "melbet-fulltime-v2",
            "league_code": league,
            "fixture_namespace": "DOM",
            "fixture_id": fixture_id,
            "market": market,
            "outcome": outcome,
            "price": price,
        }

    def test_only_complete_prediction_with_matching_fresh_active_quote_survives(self):
        data = {"competitions": [{"code": "PL", "matches": [
            {"id": "eligible", "status": "SCHEDULED", "prediction": self.prediction()},
            {"id": "no-model", "status": "SCHEDULED", "prediction": None},
            {"id": "stale", "status": "SCHEDULED", "prediction": self.prediction()},
            {"id": "inactive", "status": "SCHEDULED", "prediction": self.prediction()},
            {"id": "wrong-line", "status": "SCHEDULED", "prediction": self.prediction()},
        ]}]}
        rows = [
            self.snapshot("eligible"),
            self.snapshot("stale", age_seconds=13 * 60 * 60),
            self.snapshot("inactive", active=False),
            {**self.snapshot("wrong-line", market="team_total_over", outcome="over",
               price=1.7), "team": "home", "point": 3.5, "side": "over"},
        ]
        result = apply_snapshots(data, rows, self.now)
        matches = result["competitions"][0]["matches"]
        self.assertEqual([row["id"] for row in matches], ["eligible"])
        self.assertTrue(matches[0]["odds"]["prematchActive"])
        self.assertEqual(result["oddsCoverage"]["byLeague"]["PL"], {
            "eligible": 5, "visible": 1, "noCompletePrediction": 1,
            "noFreshActiveMelBet": 2, "noMatchingStrategyLine": 1,
        })

    def test_over15_is_not_an_active_strategy_market(self):
        data = {"competitions": [{"code": "PL", "matches": [
            {"id": "only-over15", "status": "SCHEDULED",
             "prediction": {"over15": 0.9}},
        ]}]}
        rows = [{
            **self.snapshot("only-over15", market="match_total_over", outcome="over"),
            "point": 1.5, "side": "over",
        }]
        result = apply_snapshots(data, rows, self.now)
        self.assertEqual(result["competitions"][0]["matches"], [])
        self.assertEqual(result["oddsCoverage"]["byLeague"]["PL"]["noMatchingStrategyLine"], 1)

    def test_does_not_mix_snapshots_from_another_league_or_namespace(self):
        data = {"competitions": [{"code": "PL", "matches": [
            {"id": "same-id", "status": "TIMED", "prediction": self.prediction()}
        ]}]}
        rows = [
            self.snapshot("same-id", league="ESP"),
            {**self.snapshot("same-id"), "fixture_namespace": "UEFA"},
        ]
        result = apply_snapshots(data, rows, self.now)
        self.assertEqual(result["competitions"][0]["matches"], [])
        self.assertEqual(result["oddsCoverage"]["excluded"], 1)


if __name__ == "__main__":
    unittest.main()
