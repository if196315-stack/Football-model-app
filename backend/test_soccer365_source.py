import unittest
from unittest.mock import Mock, patch

from soccer365_source import extract_match_links, parse_match_page


class Soccer365ParserTests(unittest.TestCase):
    def test_extracts_only_soccer365_match_links(self):
        page = """
        <a href="/live/match-arsenal-hull-smotret-onlayn/">match</a>
        <a href="https://soccer365.net/live/game-test/">game</a>
        <a href="https://example.com/live/match-fake/">external</a>
        <a href="/competitions/12/">competition</a>
        """
        links = extract_match_links(page)
        self.assertEqual(len(links), 2)
        self.assertTrue(all("soccer365.net" in link for link in links))

    def test_rejects_lookalike_hostnames(self):
        page = """
        <a href="https://soccer365.net.attacker.example/live/match-fake/">fake</a>
        <a href="https://not-soccer365.net/live/match-fake/">fake2</a>
        """
        self.assertEqual(extract_match_links(page), [])

    def test_parses_xg_and_match_metadata_without_fabricating_utc(self):
        page = """
        <html><head><title>Боруссия Дортмунд - Падерборн 07: смотреть онлайн</title></head>
        <body>
          <div>12.09.2026 18:30</div>
          <script type="application/ld+json">
          {"@type":"SportsEvent","eventStatus":"https://schema.org/EventCompleted"}
          </script>
          <div>Статистика матча</div>
          <div>1.54 Ожидаемые голы (xG) 0.41</div>
        </body></html>
        """
        match = parse_match_page(
            page,
            "https://soccer365.net/live/match-paderborn-07-borussiya-d-smotret-onlayn/",
            country="Germany",
            competition_id=17,
            competition_name="Bundesliga",
            competition_type="league",
        )
        self.assertEqual(match["home"], "Боруссия Дортмунд")
        self.assertEqual(match["away"], "Падерборн 07")
        self.assertEqual(match["status"], "FINISHED")
        self.assertEqual(match["statistics"]["homeXg"], 1.54)
        self.assertEqual(match["statistics"]["awayXg"], 0.41)
        self.assertIsNone(match["season"])
        self.assertIsNone(match["utcDate"])
        self.assertEqual(match["localKickoff"], "2026-09-12T18:30:00")


    def test_ignores_unrelated_page_text_when_determining_match_status(self):
        page = """
        <title>Home - Away</title>
        <nav>Завершен · Finished · Postponed</nav>
        <div>Другие матчи: матч окончен</div>
        """
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertIsNone(match["status"])

    def test_maps_explicit_jsonld_event_status(self):
        cases = {
            "https://schema.org/EventCompleted": "FINISHED",
            "https://schema.org/EventScheduled": "SCHEDULED",
            "https://schema.org/EventPostponed": "POSTPONED",
            "https://schema.org/EventCancelled": "CANCELLED",
        }
        for source_status, expected in cases.items():
            with self.subTest(source_status=source_status):
                page = f"""
                <title>Home - Away</title>
                <script type="application/ld+json">
                {{"@type":"SportsEvent","eventStatus":"{source_status}"}}
                </script>
                """
                match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
                self.assertEqual(match["status"], expected)

    def test_naive_jsonld_datetime_is_not_assumed_utc(self):
        page = '''
        <title>Home - Away</title>
        <script type="application/ld+json">
        {"@type":["Thing","SportsEvent"],"startDate":"2026-09-12T18:30:00"}
        </script>
        '''
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertIsNone(match["utcDate"])

    def test_nested_jsonld_graph_finds_sports_event(self):
        page = '''
        <title>Fallback Home - Fallback Away</title>
        <script type="application/ld+json">
        {
          "@context": "https://schema.org",
          "@graph": [{
            "@type": "WebPage",
            "@graph": [{
              "@type": ["Thing", "SportsEvent"],
              "startDate": "2026-09-12T18:30:00+02:00",
              "homeTeam": {"name": "Nested Home"},
              "awayTeam": {"name": "Nested Away"}
            }]
          }]
        }
        </script>
        '''
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertEqual(match["home"], "Nested Home")
        self.assertEqual(match["away"], "Nested Away")
        self.assertEqual(match["utcDate"], "2026-09-12T16:30:00+00:00")

    def test_jsonld_string_team_names_override_title_fallback(self):
        page = '''
        <title>Fallback Home - Fallback Away</title>
        <script type="application/ld+json">
        {
          "@type": "SportsEvent",
          "homeTeam": "Structured Home",
          "awayTeam": "Structured Away"
        }
        </script>
        '''
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertEqual(match["home"], "Structured Home")
        self.assertEqual(match["away"], "Structured Away")

    def test_explicit_jsonld_timezone_is_normalized_to_utc(self):
        page = '''
        <title>Home - Away</title>
        <script type="application/ld+json">
        {"@type":"SportsEvent","startDate":"2026-09-12T18:30:00+02:00"}
        </script>
        '''
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertEqual(match["utcDate"], "2026-09-12T16:30:00+00:00")

    def test_missing_xg_remains_null(self):
        page = "<title>Home - Away: watch online</title><body>Finished</body>"
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertFalse(match["statistics"]["xgAvailable"])
        self.assertIsNone(match["statistics"]["homeXg"])
        self.assertIsNone(match["statistics"]["awayXg"])

    def test_client_rejects_non_https_and_unapproved_hosts(self):
        from soccer365_source import Soccer365Client

        for url in (
            "http://soccer365.net/live/match-home-away/",
            "https://soccer365.ru/live/match-home-away/",
            "https://soccer365.net.attacker.example/live/match-home-away/",
            "https://user@soccer365.net/live/match-home-away/",
            "https://soccer365.net:8443/live/match-home-away/",
        ):
            with self.subTest(url=url):
                with self.assertRaises(ValueError):
                    Soccer365Client._validate_url(url)

    def test_client_accepts_canonical_https_hosts(self):
        from soccer365_source import Soccer365Client

        Soccer365Client._validate_url("https://soccer365.net/live/match-home-away/")
        Soccer365Client._validate_url("https://www.soccer365.net/live/match-home-away/")

    def test_client_refuses_redirects_without_following_them(self):
        from soccer365_source import Soccer365Client

        session = Mock()
        response = Mock()
        response.is_redirect = True
        response.is_permanent_redirect = False
        response.status_code = 302
        session.get.return_value = response
        client = Soccer365Client(session=session, min_delay=1)

        with patch("soccer365_source.time.sleep"):
            with self.assertRaisesRegex(ValueError, "redirect refused"):
                client.get("https://soccer365.net/live/match-home-away/")

        session.get.assert_called_once_with(
            "https://soccer365.net/live/match-home-away/",
            timeout=(15, 45),
            allow_redirects=False,
        )


if __name__ == "__main__":
    unittest.main()
