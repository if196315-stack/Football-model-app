"""Regression tests for the audit-only API-Sport alias registry."""
import unittest

from backend.api_sport_alias_review import ALIASES, resolve_reviewed_team_id


class ApiSportAliasReviewTests(unittest.TestCase):
    def test_registry_has_unique_team_ids(self):
        ids = [team_id for _, team_id in ALIASES.values()]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len(ALIASES), 16)

    def test_every_reviewed_alias_resolves_only_with_exact_evidence(self):
        for (pitchapi_name, tournament_id), (observed_name, team_id) in ALIASES.items():
            with self.subTest(team=pitchapi_name):
                self.assertEqual(
                    resolve_reviewed_team_id(
                        pitchapi_name, tournament_id, observed_name, team_id
                    ),
                    team_id,
                )

    def test_every_alias_rejects_wrong_tournament(self):
        for (pitchapi_name, tournament_id), (observed_name, team_id) in ALIASES.items():
            wrong_tournament = tournament_id + 1
            with self.subTest(team=pitchapi_name, wrong_tournament=wrong_tournament):
                self.assertIsNone(
                    resolve_reviewed_team_id(
                        pitchapi_name, wrong_tournament, observed_name, team_id
                    )
                )

    def test_wrong_team_id_is_rejected(self):
        self.assertIsNone(
            resolve_reviewed_team_id("Bayern München", 7, "FC Bayern München", "999")
        )

    def test_wrong_observed_name_is_rejected(self):
        self.assertIsNone(
            resolve_reviewed_team_id(
                "Borussia Mönchengladbach", 35, "Borussia U19", "2527"
            )
        )

    def test_unreviewed_slavia_name_is_not_resolved(self):
        self.assertIsNone(
            resolve_reviewed_team_id("Slavia Prague", 679, "Slavia Praha U19", "123")
        )

    def test_reserve_side_cannot_replace_first_team(self):
        self.assertIsNone(
            resolve_reviewed_team_id("Bayern München", 7, "FC Bayern II", "2672")
        )

    def test_unknown_tournament_id_is_not_resolved(self):
        self.assertIsNone(
            resolve_reviewed_team_id(
                "Ararat Armenia", 35, "FC Ararat-Armenia", "262229"
            )
        )

    def test_unknown_name_is_not_resolved(self):
        self.assertIsNone(
            resolve_reviewed_team_id("Unknown FC", 23, "AS Roma", "2702")
        )


if __name__ == "__main__":
    unittest.main()
