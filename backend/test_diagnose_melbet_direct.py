import unittest

from diagnose_melbet_direct import (
    collect_market_rows,
    supported_market_summary,
    summarize_response,
)


class MelbetDirectDiagnosticTests(unittest.TestCase):
    def test_collect_market_rows_finds_nested_markets(self):
        payload = {
            "E": [{"G": 1, "T": 1, "C": 1.45}, {"G": 1, "T": 2, "C": 4.8}],
            "AE": [{"ME": [{"G": 17, "GS": 4, "T": 9, "P": 2.5, "C": 1.9}]}],
        }
        count, sample = collect_market_rows(payload)
        self.assertEqual(count, 3)
        self.assertEqual(sample[0]["C"], 1.45)
        self.assertEqual(sample[2]["P"], 2.5)

    def test_supported_market_summary_extracts_1x2_and_market_groups(self):
        payload = {
            "E": [
                {"G": 1, "T": 1, "C": 1.5},
                {"G": 1, "T": 2, "C": 5.0},
                {"G": 1, "T": 3, "C": 6.0},
                {"G": 6, "T": 13, "C": 1.2},
                {"G": 17, "GS": 4, "T": 9, "P": 2.5, "C": 1.9},
            ],
        }
        result = supported_market_summary(payload)
        self.assertEqual(result["oneXTwo"], {"home": 1.5, "draw": 5.0, "away": 6.0})
        self.assertEqual(result["supportedMarketGroupCounts"], {"17": 1, "6": 1})
        self.assertEqual(len(result["supportedMarketSample"]), 2)

    def test_summarize_response_reports_event_and_market_data(self):
        event = {"id": 123, "home": "Home FC", "away": "Away FC"}
        payload = {
            "Success": True,
            "Value": [{"I": 123, "O1": "Home FC", "O2": "Away FC",
                       "E": [{"G": 1, "T": 1, "C": 1.5}]}],
        }
        result = summarize_response(event, 200, "application/json", payload)
        self.assertEqual(result["sourceHome"], "Home FC")
        self.assertEqual(result["sourceAway"], "Away FC")
        self.assertEqual(result["marketRecordsFound"], 1)
        self.assertTrue(result["success"])

    def test_summarize_non_json_response_keeps_short_preview(self):
        event = {"id": 123, "home": "Home FC", "away": "Away FC"}
        result = summarize_response(event, 406, "text/html",
                                    payload=None, text_body="<html>blocked</html>")
        self.assertEqual(result["httpStatus"], 406)
        self.assertEqual(result["bodyPreview"], "<html>blocked</html>")
        self.assertEqual(result["marketRecordsFound"], 0)


if __name__ == "__main__":
    unittest.main()
