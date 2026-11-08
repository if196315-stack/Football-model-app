import unittest

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
          <div>Завершен</div>
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
        self.assertIsNone(match["utcDate"])
        self.assertEqual(match["localKickoff"], "2026-09-12T18:30:00")

    def test_naive_jsonld_datetime_is_not_assumed_utc(self):
        page = '''
        <title>Home - Away</title>
        <script type="application/ld+json">
        {"@type":["Thing","SportsEvent"],"startDate":"2026-09-12T18:30:00"}
        </script>
        '''
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertIsNone(match["utcDate"])

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


if __name__ == "__main__":
    unittest.main()
