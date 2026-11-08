import unittest

from soccer365_collector import collect_catalog


COMPETITION_URL = "https://soccer365.net/verified/eng-premier/"
MATCH_URL = "https://soccer365.net/live/match-alpha-beta/"


def entry(**overrides):
    value = {
        "country": "England",
        "competitionId": "eng-prem",
        "competitionName": "Premier League",
        "competitionType": "league",
        "season": "2026-2027",
        "url": COMPETITION_URL,
    }
    value.update(overrides)
    return value


class FakeClient:
    def __init__(self, pages):
        self.pages = pages
        self.calls = []

    def get(self, url):
        self.calls.append(url)
        value = self.pages[url]
        if isinstance(value, Exception):
            raise value
        return value


LISTING = f'<html><a href="{MATCH_URL}">match</a></html>'
MATCH_PAGE = """
<html><head><title>Alpha FC - Beta FC: Match</title></head>
<script type="application/ld+json">
{
  "@type": "SportsEvent",
  "startDate": "2026-08-01T15:00:00+02:00",
  "eventStatus": "https://schema.org/EventCompleted",
  "homeTeam": {"name": "Alpha FC"},
  "awayTeam": {"name": "Beta FC"},
  "homeTeamScore": "2",
  "awayTeamScore": "1"
}
</script></html>
"""


class Soccer365CollectorTests(unittest.TestCase):
    def test_collects_only_explicit_competition_and_match_links(self):
        client = FakeClient({COMPETITION_URL: LISTING, MATCH_URL: MATCH_PAGE})
        result = collect_catalog(client, [entry()])
        self.assertTrue(result["complete"])
        self.assertTrue(result["catalogValid"])
        self.assertEqual(client.calls, [COMPETITION_URL, MATCH_URL])
        self.assertEqual(len(result["records"]), 1)
        record = result["records"][0]
        self.assertEqual(record["competitionId"], "eng-prem")
        self.assertEqual(record["competitionType"], "league")
        self.assertEqual(record["season"], "2026-2027")
        self.assertEqual(record["home"], "Alpha FC")
        self.assertEqual(record["utcDate"], "2026-08-01T13:00:00+00:00")

    def test_invalid_catalog_causes_no_network_requests(self):
        client = FakeClient({})
        result = collect_catalog(client, [entry(url="https://example.com/")])
        self.assertFalse(result["catalogValid"])
        self.assertEqual(client.calls, [])
        self.assertEqual(result["records"], [])

    def test_competition_page_failure_marks_run_incomplete(self):
        client = FakeClient({COMPETITION_URL: RuntimeError("HTTP 503")})
        result = collect_catalog(client, [entry()])
        self.assertFalse(result["complete"])
        self.assertEqual(result["errors"][0]["stage"], "competition_page")
        self.assertEqual(result["records"], [])

    def test_match_page_failure_is_reported_not_hidden(self):
        client = FakeClient({COMPETITION_URL: LISTING, MATCH_URL: RuntimeError("HTTP 403")})
        result = collect_catalog(client, [entry()])
        self.assertFalse(result["complete"])
        self.assertEqual(result["competitionReports"][0]["failedMatchPages"], 1)
        self.assertEqual(result["errors"][0]["stage"], "match_page")

    def test_empty_listing_is_a_warning(self):
        client = FakeClient({COMPETITION_URL: "<html><body>No matches</body></html>"})
        result = collect_catalog(client, [entry()])
        self.assertTrue(result["complete"])
        self.assertEqual(result["records"], [])
        self.assertEqual(result["warnings"][0]["code"], "no_match_links_found")

    def test_duplicate_match_link_across_entries_is_not_fetched_twice(self):
        second_url = "https://soccer365.net/verified/eng-prem-cup/"
        second = entry(
            competitionId="eng-cup",
            competitionName="FA Cup",
            competitionType="domestic_cup",
            url=second_url,
        )
        client = FakeClient({
            COMPETITION_URL: LISTING,
            second_url: LISTING,
            MATCH_URL: MATCH_PAGE,
        })
        result = collect_catalog(client, [entry(), second])
        self.assertEqual(client.calls.count(MATCH_URL), 1)
        self.assertIn("duplicate_match_link_across_catalog", [
            warning["code"] for warning in result["warnings"]
        ])


if __name__ == "__main__":
    unittest.main()
