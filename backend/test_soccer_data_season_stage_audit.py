import unittest
from unittest.mock import Mock

from soccer_data_season_stage_audit import _season_id, _stage_total


class SeasonStageAuditTests(unittest.TestCase):
    def test_resolves_season_by_exact_year(self):
        client = Mock()
        client.get.return_value = {"data": [
            {"id": "season_old", "year": "21/22"},
            {"id": "season_target", "year": "23/24"},
        ]}
        self.assertEqual(_season_id(client, "competition_x", "23/24"), "season_target")

    def test_rejects_missing_or_ambiguous_season(self):
        client = Mock()
        client.get.return_value = {"data": [
            {"id": "season_a", "year": "23/24"},
            {"id": "season_b", "year": "23/24"},
        ]}
        with self.assertRaises(ValueError):
            _season_id(client, "competition_x", "23/24")

    def test_reads_finished_stage_total_without_downloading_history(self):
        client = Mock()
        client.get.return_value = {"data": [], "meta": {"total": 4}}
        self.assertEqual(_stage_total(client, "competition_x", "season_x", "playoff"), 4)
        self.assertEqual(client.get.call_args.args[0], "/matches")
        self.assertEqual(client.get.call_args.kwargs["params"]["per_page"], 1)
        self.assertEqual(client.get.call_args.kwargs["params"]["stage"], "playoff")


if __name__ == "__main__":
    unittest.main()
