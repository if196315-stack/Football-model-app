import unittest
from unittest.mock import Mock

from soccer_data_match_spot_check import (
    COMPETITIONS, audit_competition, valid_xg_pair,
)


class MatchXgSpotCheckTests(unittest.TestCase):
    def test_valid_xg_pair(self):
        self.assertTrue(valid_xg_pair({"home": 1.2, "away": 0}))
        self.assertFalse(valid_xg_pair({"home": 1.2, "away": None}))
        self.assertFalse(valid_xg_pair({"home": True, "away": 0.2}))
        self.assertFalse(valid_xg_pair({"home": float("nan"), "away": 0.2}))
        self.assertFalse(valid_xg_pair({"home": 21, "away": 0.2}))

    def test_match_flag_is_checked_against_actual_stats(self):
        client = Mock()
        client.get.side_effect = [
            {
                "data": [{
                    "id": "match_abcdefgh1234",
                    "status": "finished",
                    "xg_available": True,
                    "xg_quality": "full",
                    "utc_date": "2026-05-10T15:00:00Z",
                    "home_team": {"name": "A"},
                    "away_team": {"name": "B"},
                    "score": {"home": 2, "away": 1},
                }],
                "meta": {"total": 380},
            },
            {"data": {"overview": {
                "expected_goals": {"all": {"home": 1.7, "away": 0.8}}
            }}},
        ]
        country, (name, competition_id) = next(iter(COMPETITIONS.items()))
        result = audit_competition(client, country, name, competition_id)
        self.assertEqual(result["result"], "PASS")
        self.assertEqual(result["xg"], {"home": 1.7, "away": 0.8})
        self.assertEqual(client.get.call_count, 2)

    def test_no_xg_candidate_does_not_make_stats_request(self):
        client = Mock()
        client.get.return_value = {
            "data": [{"id": "match_abcdefgh1234", "status": "finished",
                      "xg_available": False}],
            "meta": {"total": 380},
        }
        country, (name, competition_id) = next(iter(COMPETITIONS.items()))
        result = audit_competition(client, country, name, competition_id)
        self.assertEqual(result["result"], "NO_XG_MATCH_IN_FIRST_PAGE")
        self.assertEqual(client.get.call_count, 1)


if __name__ == "__main__":
    unittest.main()
