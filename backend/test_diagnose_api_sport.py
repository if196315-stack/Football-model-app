import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diagnose_api_sport import market_summary, quota_summary, response_items


class DiagnosticHelpersTests(unittest.TestCase):
    def test_quota_summary_reads_documented_fields(self):
        result = quota_summary({
            "plan": "Pro",
            "dailyRequestLimit": 50000,
            "usage": {"today": 123, "remaining": 49877, "resetAt": "2026-11-10T00:00:00Z"},
        })
        self.assertEqual(result["dailyRequestLimit"], 50000)
        self.assertEqual(result["today"], 123)
        self.assertEqual(result["remaining"], 49877)

    def test_response_items_supports_list_and_matches_envelope(self):
        self.assertEqual(response_items([{"id": 1}]), [{"id": 1}])
        self.assertEqual(response_items({"matches": [{"id": 2}]}), [{"id": 2}])
        self.assertEqual(response_items({"unexpected": []}), [])

    def test_market_summary_preserves_market_lines_and_status(self):
        result = market_summary({"markets": {
            "total": {"name": {"en": "Total"}, "status": "open", "stage": "prematch", "isBettingActiveByStage": {"prematch": True},
                      "stakes": {"over": {"lines": [
                          {"argument": 2.5, "factor": 1.91, "suspended": False}
                      ]}}}
        }})
        self.assertEqual(result[0]["slug"], "total")
        self.assertEqual(result[0]["status"], "open")
        self.assertEqual(result[0]["stage"], "prematch")
        self.assertEqual(result[0]["isBettingActiveByStage"], {"prematch": True})
        self.assertEqual(result[0]["outcomes"]["over"][0]["argument"], 2.5)
        self.assertEqual(result[0]["outcomes"]["over"][0]["factor"], 1.91)


if __name__ == "__main__":
    unittest.main()
