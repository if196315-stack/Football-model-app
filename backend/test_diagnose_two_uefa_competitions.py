import unittest

from diagnose_two_uefa_competitions import (
    discover_pitchapi_competitions, fixture_target, norm, response_items,
)


class AdditionalUefaDiscoveryTests(unittest.TestCase):
    def test_exact_competition_aliases(self):
        rows = [
            {"id": 1, "name": "UEFA Nations League"},
            {"id": 2, "name": "UEFA Europa Conference League"},
            {"id": 3, "name": "UEFA Women's Nations League"},
            {"id": 4, "name": "UEFA Youth League"},
        ]
        found = discover_pitchapi_competitions(rows)
        self.assertEqual(set(found), {"UNL", "UECL"})
        self.assertEqual(found["UNL"]["id"], 1)
        self.assertEqual(found["UECL"]["id"], 2)

    def test_normalization(self):
        self.assertEqual(norm("UEFA Europa-Conference League"), "uefa europa conference league")

    def test_fixture_target_requires_identity_fields(self):
        raw = {"id": 123, "date": "2026-11-20T18:00:00Z",
               "home_team": {"name": "Home"}, "away_team": {"name": "Away"}}
        target = fixture_target(raw, "UECL")
        self.assertEqual(target["competition"], "UECL")
        self.assertEqual(target["date"], "2026-11-20")
        self.assertIsNone(fixture_target({"id": 1}, "UECL"))

    def test_response_items_accepts_provider_shapes(self):
        self.assertEqual(response_items({"matches": [{"id": 1}]}), [{"id": 1}])
        self.assertEqual(response_items([{"id": 2}, None]), [{"id": 2}])


if __name__ == "__main__":
    unittest.main()
