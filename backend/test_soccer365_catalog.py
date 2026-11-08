import unittest

from soccer365_catalog import validate_competition_catalog


def entry(**overrides):
    value = {
        "country": "England",
        "competitionId": "eng-prem",
        "competitionName": "Premier League",
        "competitionType": "league",
        "season": "2026-2027",
        "url": "https://soccer365.net/competitions/eng-prem/",
    }
    value.update(overrides)
    return value


class Soccer365CatalogTests(unittest.TestCase):
    def test_explicit_catalog_entry_is_valid(self):
        result = validate_competition_catalog([entry()])
        self.assertTrue(result["valid"])
        self.assertEqual(result["errorCount"], 0)

    def test_missing_season_is_rejected(self):
        result = validate_competition_catalog([entry(season="")])
        self.assertIn("missing_season", [issue["code"] for issue in result["issues"]])

    def test_unknown_competition_type_is_rejected(self):
        result = validate_competition_catalog([entry(competitionType="super_league")])
        self.assertIn("invalid_competition_type", [issue["code"] for issue in result["issues"]])

    def test_non_soccer365_host_is_rejected(self):
        result = validate_competition_catalog([entry(url="https://soccer365.example/competition/")])
        self.assertIn("untrusted_catalog_url", [issue["code"] for issue in result["issues"]])

    def test_http_and_nonstandard_port_are_rejected(self):
        for url in ("http://soccer365.net/competition/", "https://soccer365.net:8443/competition/"):
            with self.subTest(url=url):
                result = validate_competition_catalog([entry(url=url)])
                self.assertIn("untrusted_catalog_url", [issue["code"] for issue in result["issues"]])

    def test_malformed_port_is_rejected_without_crashing(self):
        result = validate_competition_catalog([entry(url="https://soccer365.net:abc/competition/")])
        self.assertIn("untrusted_catalog_url", [issue["code"] for issue in result["issues"]])

    def test_url_query_and_fragment_are_rejected(self):
        result = validate_competition_catalog([entry(url="https://soccer365.net/competition/?season=2026#top")])
        self.assertIn("untrusted_catalog_url", [issue["code"] for issue in result["issues"]])

    def test_duplicate_competition_season_is_rejected(self):
        first = entry()
        second = entry(url="https://soccer365.net/competitions/eng-prem-2/")
        result = validate_competition_catalog([first, second])
        self.assertIn("duplicate_competition_season", [issue["code"] for issue in result["issues"]])

    def test_same_competition_in_different_seasons_is_allowed(self):
        first = entry()
        second = entry(season="2025-2026", url="https://soccer365.net/competitions/eng-prem-2526/")
        result = validate_competition_catalog([first, second])
        self.assertTrue(result["valid"])

    def test_duplicate_url_is_rejected(self):
        result = validate_competition_catalog([entry(), entry(
            competitionId="eng-cup", competitionName="FA Cup",
            competitionType="domestic_cup",
        )])
        self.assertIn("duplicate_catalog_url", [issue["code"] for issue in result["issues"]])

    def test_catalog_does_not_assume_url_shapes(self):
        # An explicitly supplied URL is accepted regardless of path shape;
        # the validator does not invent or infer a competition route.
        result = validate_competition_catalog([entry(url="https://www.soccer365.net/archive/verified-page/")])
        self.assertTrue(result["valid"])


if __name__ == "__main__":
    unittest.main()
