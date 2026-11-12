import unittest

from collect_odds_snapshots import snapshot_rows


class MelBetOddsArchiveTests(unittest.TestCase):
    def sample(self):
        return {
            "competitions": [{
                "code": "PL",
                "matches": [{
                    "id": "fixture-1",
                    "utcDate": "2026-11-20T19:00:00Z",
                    "home": "Arsenal",
                    "away": "Chelsea",
                    "odds": {
                        "bookmaker": "MELBET",
                        "updatedAt": "2026-11-10T10:00:00+00:00",
                        "bookmakerUpdatedAt": "2026-11-10T09:59:50+00:00",
                        "bookmakerUpdateAgeSeconds": 10,
                        "freshnessStatus": "fresh",
                        "bookmakers": [{
                            "key": "melbet",
                            "title": "MELBET",
                            "doubleChance": {"1x": 1.30, "x2": 1.65, "12": 1.25},
                            "spreads": [
                                {"team": "home", "point": 0.0, "price": 1.90,
                                 "market_slug": "draw_no_bet_home", "market_group": "draw no bet",
                                 "market_name": "Draw No Bet"},
                                {"team": "away", "point": 0.0, "price": 1.95,
                                 "market_slug": "draw_no_bet_away", "market_group": "draw no bet",
                                 "market_name": "Draw No Bet"},
                                {"team": "home", "point": -0.5, "price": 2.10},
                            ],
                            "totals": [
                                {"side": "over", "point": 2.5, "price": 1.90,
                                 "market_slug": "total_over", "market_group": "total goals",
                                 "market_name": "Total Goals"},
                                {"side": "under", "point": 2.5, "price": 1.95,
                                 "market_slug": "total_under", "market_group": "total goals",
                                 "market_name": "Total Goals"},
                            ],
                            "teamTotals": [
                                {"team": "home", "side": "over", "point": 1.5, "price": 1.85,
                                 "market_slug": "home_team_total_over", "market_group": "home team total",
                                 "market_name": "Home Team Total"},
                                {"team": "home", "side": "under", "point": 1.5, "price": 2.00,
                                 "market_slug": "home_team_total_under", "market_group": "home team total",
                                 "market_name": "Home Team Total"},
                            ],
                            "btts": {"yes": 1.72, "no": 2.08},
                        }],
                    },
                }],
            }],
        }

    def test_archives_only_melbet_supported_market_quotes_with_source_timestamps(self):
        rows = list(snapshot_rows(self.sample(), "2026-11-10T10:00:00Z"))
        self.assertEqual(len(rows), 11)
        self.assertEqual({row["bookmaker"] for row in rows}, {"MELBET"})
        self.assertEqual({row["league_code"] for row in rows}, {"ENG"})
        self.assertEqual({row["schema_version"] for row in rows}, {2})
        self.assertEqual({row["market_parser_version"] for row in rows}, {"melbet-fulltime-v3"})
        self.assertEqual({row["bookmaker_updated_at"] for row in rows},
                         {"2026-11-10T09:59:50+00:00"})
        self.assertTrue(all(row["freshness_status"] == "fresh" for row in rows))
        dc12 = next(row for row in rows if row["market"] == "double_chance" and row["outcome"] == "12")
        self.assertFalse(dc12["candidate_market"])
        self.assertTrue(dc12["reference_only"])
        under = next(row for row in rows if row["market"] == "match_total" and row["side"] == "under")
        self.assertFalse(under["candidate_market"])
        self.assertEqual(under["provider_market_group"], "total goals")
        self.assertEqual(sum(row["market"] == "handicap" for row in rows), 2)

    def test_rejects_legacy_non_melbet_odds(self):
        data = self.sample()
        data["competitions"][0]["matches"][0]["odds"]["bookmaker"] = "BETBOOM"
        self.assertEqual(list(snapshot_rows(data, "2026-11-10T10:00:00Z")), [])

    def test_preserves_stale_source_status_instead_of_relabelling_it_fresh(self):
        data = self.sample()
        odds = data["competitions"][0]["matches"][0]["odds"]
        odds["freshnessStatus"] = "stale"
        odds["bookmakerUpdateAgeSeconds"] = 1800
        rows = list(snapshot_rows(data, "2026-11-10T10:00:00Z"))
        self.assertTrue(rows)
        self.assertTrue(all(row["freshness_status"] == "stale" for row in rows))
        self.assertTrue(all(row["bookmaker_update_age_seconds"] == 1800 for row in rows))


if __name__ == "__main__":
    unittest.main()
