import unittest
from collections import Counter

from probe_pitchapi_coverage import choose_samples, season_start, norm, flatten_shots, match_integrity


class PitchApiProbeTests(unittest.TestCase):
    def test_season_start_filters_before_2021(self):
        self.assertEqual(season_start("2021/2022"), 2021)
        self.assertEqual(season_start("2025/2026"), 2025)
        self.assertIsNone(season_start("2020/2021"))
        self.assertIsNone(season_start("unknown"))

    def test_sample_selection_only_finished_with_ids(self):
        matches = [
            {"id": "a", "status": "finished"},
            {"id": "b", "status": "scheduled"},
            {"status": "finished"},
            {"id": "c", "status": "finished"},
        ]
        self.assertEqual([m["id"] for m in choose_samples(matches)], ["a", "c"])

    def test_sample_selection_spreads_across_history(self):
        matches = [{"id": str(i), "status": "finished"} for i in range(10)]
        selected = choose_samples(matches)
        self.assertEqual([m["id"] for m in selected], ["0", "2", "4", "7", "9"])
        self.assertLessEqual(len(selected), 5)

    def test_sample_selection_accepts_larger_limit(self):
        matches = [{"id": str(i), "status": "finished"} for i in range(30)]
        selected = choose_samples(matches, 20)
        self.assertEqual(len(selected), 20)
        self.assertEqual(selected[0]["id"], "0")
        self.assertEqual(selected[-1]["id"], "29")

    def test_flatten_shots_from_periods(self):
        data = {"periods": [
            {"period": "FirstHalf", "shots": [{"id": "s1"}, {"id": "s2"}]},
            {"period": "SecondHalf", "shots": [{"id": "s3"}]},
        ]}
        self.assertEqual([x["id"] for x in flatten_shots(data)], ["s1", "s2", "s3"])

    def test_flatten_shots_empty_periods(self):
        self.assertEqual(flatten_shots({"periods": []}), [])

    def test_normalise(self):
        self.assertEqual(norm("La-Liga"), "la liga")


    def test_match_integrity_detects_duplicate_ids_and_fixtures(self):
        matches = [
            {"id": "1", "date": "2024-01-01T12:00:00Z",
             "home_team": {"id": 10}, "away_team": {"id": 20}},
            {"id": "1", "date": "2024-01-01T18:00:00Z",
             "home_team": {"id": 20}, "away_team": {"id": 10}},
            {"id": "2", "date": "2024-01-02T12:00:00Z",
             "home_team": {"id": 30}, "away_team": {"id": 40}},
        ]
        result = match_integrity(matches)
        self.assertEqual(result["uniqueMatchIds"], 2)
        self.assertEqual(result["duplicateMatchIds"], ["1"])
        self.assertEqual(result["uniqueFixtures"], 2)
        self.assertEqual(result["duplicateFixtures"], [
            {"date": "2024-01-01", "teamIds": ["10", "20"]}
        ])


    def test_match_integrity_reports_per_team_appearance_counts(self):
        matches = [
            {"id": "1", "date": "2024-01-01", "home_team": {"id": 1, "name": "A"},
             "away_team": {"id": 2, "name": "B"}},
            {"id": "2", "date": "2024-01-02", "home_team": {"id": 2, "name": "B"},
             "away_team": {"id": 3, "name": "C"}},
            {"id": "3", "date": "2024-01-03", "home_team": {"id": 3, "name": "C"},
             "away_team": {"id": 1, "name": "A"}},
        ]
        result = match_integrity(matches)
        self.assertEqual(result["teamCount"], 3)
        self.assertEqual(result["expectedMatchesPerTeam"], 4)
        self.assertEqual(
            {row["team"]: row["matches"] for row in result["teamMatchCounts"]},
            {"A": 2, "B": 2, "C": 2},
        )
        self.assertEqual(len(result["teamCountOutliers"]), 3)
        self.assertEqual(len(result["teamFixtureLists"]["1"]), 2)
        self.assertEqual(result["teamFixtureLists"]["1"][0]["home"], "A")


    def test_match_integrity_excludes_canceled_fixtures(self):
        matches = [
            {"id": "1", "date": "2023-08-01", "status": "finished",
             "home_team": {"id": 1, "name": "A"}, "away_team": {"id": 2, "name": "B"}},
            {"id": "2", "date": "2023-08-02", "status": "canceled",
             "home_team": {"id": 1, "name": "A"}, "away_team": {"id": 2, "name": "B"}},
        ]
        result = match_integrity(matches, "2023/2024")
        self.assertEqual(result["teamMatchCounts"][0]["matches"], 1)
        self.assertEqual(result["teamMatchCounts"][1]["matches"], 1)
        self.assertEqual(len(result["teamFixtureLists"]["1"]), 1)
        self.assertEqual(len(result["teamFixtureLists"]["2"]), 1)
        self.assertTrue(all(
            fixture["status"] != "canceled"
            for fixtures in result["teamFixtureLists"].values()
            for fixture in fixtures
        ))

    def test_invalid_sample_limit_is_rejected_before_network_access(self):
        from probe_pitchapi_coverage import run_audit

        class NoNetwork:
            def get(self, *args, **kwargs):
                raise AssertionError("network must not be called")

        for invalid in (0, 51, True, 1.5):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    run_audit("not-a-real-key", session=NoNetwork(), sample_limit=invalid)

    def test_inspect_shots_records_request_failure_without_zero_xg(self):
        import requests
        from probe_pitchapi_coverage import inspect_shots

        class BrokenSession:
            def get(self, *args, **kwargs):
                raise requests.ConnectionError("temporary outage")

        match = {
            "id": "m1", "status": "finished", "date": "2024-01-01",
            "home_team": {"id": 1, "name": "A"},
            "away_team": {"id": 2, "name": "B"},
        }
        result = inspect_shots(BrokenSession(), match, "unused")
        self.assertFalse(result["pairedXgPresent"])
        self.assertIsNone(result["homeXgFromShots"])
        self.assertIsNone(result["awayXgFromShots"])
        self.assertEqual(result["xgFetchError"], "ConnectionError")

    def test_active_season_team_counts_are_not_flagged(self):
        matches = [
            {"id": "1", "date": "2026-08-01", "status": "finished",
             "home_team": {"id": 1, "name": "A"}, "away_team": {"id": 2, "name": "B"}},
        ]
        result = match_integrity(matches, "2026/2027")
        self.assertIsNone(result["expectedMatchesPerTeam"])
        self.assertFalse(result["teamCountsComparable"])
        self.assertEqual(result["teamCountOutliers"], [])


if __name__ == "__main__":
    unittest.main()
