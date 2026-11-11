import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_all_competitions import build_all

class EightCompetitionAuditTests(unittest.TestCase):
    def fixture(self, code, id_value):
        return {"id": id_value, "status": "SCHEDULED", "utcDate": "2099-01-01",
                "home": "Alpha", "away": "Beta",
                "prediction": {"1x": 0.60},
                "odds": {"bookmaker": "MELBET", "source": "API-Sport / MelBet",
                         "best": {"doubleChance": {"1x": 2.0}}}}

    def test_combines_five_domestic_and_three_uefa_competitions(self):
        domestic = {"competitions": []}
        for code in ("PL", "PD", "SA", "BL1", "FL1"):
            domestic["competitions"].append({"code": code, "matches": [self.fixture(code, "same-id")]})
        uefa = {"competitions": []}
        for code in ("UCL", "UEL", "UECL"):
            uefa["competitions"].append({"id": code, "matches": [self.fixture(code, "same-id")]})
        report = build_all(domestic, uefa, today=date(2098, 1, 1))
        self.assertEqual(list(report["leagues"]), ["ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL"])
        self.assertEqual(report["totals"]["scheduled_future"], 8)
        self.assertEqual(report["totals"]["with_line_leader"], 8)
        self.assertEqual(report["totals"]["qualifying_matches"], 8)
        self.assertEqual(report["totals"]["priced_market_quotes"], 8)

    def test_missing_uefa_matches_are_explicitly_zero_not_counted_as_domestic(self):
        domestic = {"competitions": [{"code": "PL", "matches": [self.fixture("PL", "one")]}]}
        report = build_all(domestic, {"competitions": []}, today=date(2098, 1, 1))
        self.assertEqual(report["leagues"]["ENG"]["counts"].get("with_line_leader", 0), 1)
        for code in ("UCL", "UEL", "UECL"):
            self.assertEqual(report["leagues"][code]["counts"].get("priced_market_quotes", 0), 0)

if __name__ == "__main__":
    unittest.main()
