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

    def test_missing_xg_remains_null(self):
        page = "<title>Home - Away: watch online</title><body>Finished</body>"
        match = parse_match_page(page, "https://soccer365.net/live/match-home-away/")
        self.assertFalse(match["statistics"]["xgAvailable"])
        self.assertIsNone(match["statistics"]["homeXg"])
        self.assertIsNone(match["statistics"]["awayXg"])


if __name__ == "__main__":
    unittest.main()
