import unittest

from soccer365_coverage import summarize_historical_coverage


def entry(**overrides):
    value = {
        "country": "England",
        "competitionId": "eng-prem",
        "competitionName": "Premier League",
        "competitionType": "league",
        "season": "2026-2027",
        "url": "https://soccer365.net/verified/premier/",
    }
    value.update(overrides)
    return value


def match(match_id, **overrides):
    value = {
        "id": match_id,
        "country": "England",
        "competitionId": "eng-prem",
        "competitionType": "league",
        "season": "2026-2027",
        "home": "Alpha FC",
        "away": "Beta FC",
        "homeTeamId": "team-a",
        "awayTeamId": "team-b",
        "utcDate": "2026-08-01T15:00:00+00:00",
        "status": "FINISHED",
        "homeScore": 2,
        "awayScore": 1,
        "statistics": {"homeXg": 1.2, "awayXg": 0.8},
    }
    value.update(overrides)
    return value


class Soccer365CoverageTests(unittest.TestCase):
    def test_reports_country_competition_season_and_xg_coverage(self):
        rows = [match(f"m-{i}") for i in range(35)]
        report = summarize_historical_coverage(
            [entry(expectedFinishedMatches=38)], rows,
            min_finished_matches=30, min_paired_xg_matches=30,
        )
        item = report["competitions"][0]
        self.assertEqual(item["country"], "England")
        self.assertEqual(item["competitionId"], "eng-prem")
        self.assertEqual(item["season"], "2026-2027")
        self.assertEqual(item["finishedWithScore"], 35)
        self.assertEqual(item["finishedWithPairedXg"], 35)
        self.assertTrue(item["baselineReady"])
        self.assertFalse(item["sourceHistoryComplete"])
        self.assertTrue(item["teams"][0]["xgSampleReady"])

    def test_more_finished_matches_than_verified_count_is_not_marked_complete(self):
        report = summarize_historical_coverage(
            [entry(expectedFinishedMatches=1)], [match("m-1"), match("m-2")]
        )
        item = report["competitions"][0]
        self.assertFalse(item["sourceHistoryComplete"])
        self.assertEqual(item["finishedCountDelta"], 1)

    def test_without_verified_expected_count_completeness_is_unknown(self):
        report = summarize_historical_coverage([entry()], [match("m-1")])
        item = report["competitions"][0]
        self.assertIsNone(item["sourceHistoryComplete"])
        self.assertFalse(item["expectedCountVerified"])

    def test_missing_xg_is_counted_not_zero_filled(self):
        row = match("m-1", statistics={"homeXg": None, "awayXg": None})
        report = summarize_historical_coverage([entry()], [row])
        item = report["competitions"][0]
        self.assertEqual(item["finishedMissingXg"], 1)
        self.assertEqual(item["finishedWithPairedXg"], 0)
        self.assertIsNone(row["statistics"]["homeXg"])

    def test_duplicate_ids_not_counted_twice(self):
        row = match("m-1")
        report = summarize_historical_coverage([entry()], [row, dict(row)])
        item = report["competitions"][0]
        self.assertEqual(item["totalRecords"], 1)
        self.assertEqual(item["duplicateIds"], 1)

    def test_unmatched_competition_season_is_reported(self):
        report = summarize_historical_coverage([entry()], [
            match("m-1", season="2025-2026")
        ])
        self.assertEqual(report["unmatchedRecordCount"], 1)
        self.assertEqual(report["competitions"][0]["totalRecords"], 0)

    def test_invalid_expected_count_rejected_by_manifest(self):
        report = summarize_historical_coverage(
            [entry(expectedFinishedMatches=-1)], []
        )
        self.assertFalse(report["valid"])
        self.assertIn(
            "invalid_expected_finished_matches",
            [issue["code"] for issue in report["manifestValidation"]["issues"]],
        )

    def test_competition_types_and_seasons_stay_separate(self):
        cup = entry(
            competitionId="eng-cup",
            competitionName="FA Cup",
            competitionType="domestic_cup",
            url="https://soccer365.net/verified/fa-cup/",
        )
        next_season = entry(
            season="2025-2026",
            url="https://soccer365.net/verified/prem-previous/",
        )
        report = summarize_historical_coverage([entry(), cup, next_season], [
            match("m1"),
            match("m2", competitionId="eng-cup", competitionType="domestic_cup"),
            match("m3", season="2025-2026"),
        ])
        self.assertEqual(len(report["competitions"]), 3)
        self.assertEqual([x["totalRecords"] for x in report["competitions"]], [1, 1, 1])


if __name__ == "__main__":
    unittest.main()
