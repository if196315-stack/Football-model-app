import unittest

from probe_soccer_data_coverage import build_coverage_report, fetch_xg_competitions


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def raise_for_status(self):
        return None

    def json(self):
        return self.body


class SoccerDataCoverageTests(unittest.TestCase):
    def test_fetches_all_pages_using_documented_page_size(self):
        class PagedSession:
            def __init__(self):
                self.calls = []

            def get(self, url, params=None, headers=None, timeout=None):
                self.calls.append((params, headers, timeout))
                body = (
                    {"data": [{"id": "c1"}], "meta": {"total_pages": 2}}
                    if params["page"] == 1 else
                    {"data": [{"id": "c2"}], "meta": {"total_pages": 2}}
                )
                return FakeResponse(body)

        session = PagedSession()
        rows = fetch_xg_competitions(session, "secret")
        self.assertEqual([row["id"] for row in rows], ["c1", "c2"])
        self.assertEqual([call[0]["per_page"] for call in session.calls], [200, 200])
        self.assertTrue(all(call[1]["X-Api-Key"] == "secret" for call in session.calls))

    def test_builds_season_report_and_skips_not_loaded(self):
        comps = [
            {"id": "eng1", "name": "Premier League", "country": "England"},
            {"id": "es1", "name": "LaLiga", "country": "Spain"},
        ]
        payloads = {
            "eng1": {"data": {"name": "Premier League", "country": "England", "seasons": [
                {"year": "25/26", "status": "complete", "events": {"finished": 380},
                 "data_types": {"xg": {"available": True, "covered_events": 370, "coverage_pct": 97.4}}},
                {"year": "24/25", "status": "not_loaded", "events": {"finished": 0}, "data_types": {}},
            ]}},
            "es1": {"data": {"name": "LaLiga", "country": "Spain", "seasons": []}},
        }
        report = build_coverage_report(comps, payloads)
        self.assertEqual(len(report["competitions"]), 2)
        england = report["competitions"][0]
        self.assertEqual(england["seasons"], [{
            "season": "25/26", "status": "complete", "finishedMatches": 380,
            "xgMatches": 370, "xgCoveragePct": 97.4, "xgAvailable": True,
        }])
        self.assertEqual(report["missingTargets"], ["France", "Germany", "Italy"])

    def test_rejects_missing_pagination_metadata(self):
        class BadSession:
            def get(self, *args, **kwargs):
                return FakeResponse({"data": [{"id": "x"}]})

        with self.assertRaises(ValueError):
            fetch_xg_competitions(BadSession(), "secret")


if __name__ == "__main__":
    unittest.main()
