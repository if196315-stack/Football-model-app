import unittest
from collections import Counter

from probe_pitchapi_coverage import choose_samples, season_start, norm, flatten_shots


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


if __name__ == "__main__":
    unittest.main()
