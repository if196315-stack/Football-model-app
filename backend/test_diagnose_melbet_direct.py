import unittest

from diagnose_melbet_direct import collect_market_rows, summarize_response


class MelbetDirectDiagnosticTests(unittest.TestCase):
    def test_collect_market_rows_finds_nested_markets(self):
        payload = {
            "E": [{"G": 1, "T": 1, "C": 1.45}, {"G": 1, "T": 2, "C": 4.8}],
            "AE": [{"ME": [{"G": 3, "T": 9, "P": 2.5, "C": 1.9}]}],
        }
        count, sample = collect_market_rows(payload)
        self.assertEqual(count, 3)
        self.assertEqual(sample[0]["C"], 1.45)
        self.assertEqual(sample[2]["P"], 2.5)

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
