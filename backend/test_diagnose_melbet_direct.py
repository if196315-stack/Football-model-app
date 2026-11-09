import unittest

from diagnose_melbet_direct import (
    collect_market_rows,
    extract_champ_fixtures,
    extract_football_leagues,
    find_champ_matches,
    market_group_summary,
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

    def test_market_group_summary_keeps_all_groups_and_1x2(self):
        payload = {"E": [
            {"G": 1, "T": 1, "C": 1.5},
            {"G": 1, "T": 2, "C": 5.0},
            {"G": 1, "T": 3, "C": 6.0},
            {"G": 8, "GS": 2, "T": 4, "C": 1.2},
            {"G": 17, "GS": 4, "T": 9, "P": 2.5, "C": 1.9},
        ]}
        result = market_group_summary(payload)
        self.assertEqual(result["oneXTwo"], {"home": 1.5, "draw": 5.0, "away": 6.0})
        self.assertEqual(result["marketGroupCounts"], {"1": 3, "8": 1, "17": 1})

    def test_extract_football_leagues_filters_and_sorts(self):
        payload = {"Value": [
            {"I": 1, "L": [
                {"LI": 100, "L": "Premier League", "GC": 20},
                {"LI": 101, "L": "Championship", "GC": 5},
                {"LI": 102, "L": "Empty league", "GC": 0},
            ]},
            {"I": 3, "L": [{"LI": 200, "L": "Basketball", "GC": 99}]},
        ]}
        self.assertEqual(extract_football_leagues(payload), [
            {"id": 100, "name": "Premier League", "games": 20},
            {"id": 101, "name": "Championship", "games": 5},
        ])

    def test_extract_champ_fixtures_reads_value_games(self):
        payload = {"Value": {"G": [
            {"I": 1, "O1": "Home", "O2": "Away", "S": 123, "LI": 100, "L": "League", "E": [1]},
            {"I": 2, "O1": "Other", "O2": "Side", "S": 456},
        ]}}
        count, sample = extract_champ_fixtures(payload)
        self.assertEqual(count, 2)
        self.assertEqual(sample[0]["id"], 1)
        self.assertEqual(sample[0]["home"], "Home")
        self.assertTrue(sample[0]["hasMarkets"])

    def test_find_champ_matches_maps_model_fixtures_to_discovered_ids(self):
        payload = {"Value": {"G": [
            {"I": 766922200, "O1": "Manchester City", "O2": "Fulham", "S": 1795264200},
            {"I": 1, "O1": "Other", "O2": "Side", "S": 123},
        ]}}
        targets = [{"id": 385744564, "home": "Manchester City", "away": "Fulham"}]
        self.assertEqual(find_champ_matches(payload, targets), [{
            "id": 766922200, "home": "Manchester City", "away": "Fulham",
            "start": 1795264200, "leagueId": None,
        }])

    def test_summarize_response_reports_event_and_market_data(self):
        event = {"id": 123, "home": "Home FC", "away": "Away FC"}
        payload = {"Success": True, "Value": [{
            "I": 123, "O1": "Home FC", "O2": "Away FC",
            "E": [{"G": 1, "T": 1, "C": 1.5}],
        }]}
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
