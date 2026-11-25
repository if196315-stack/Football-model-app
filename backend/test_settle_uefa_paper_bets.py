import unittest

from settle_uefa_paper_bets import fixture_index_from_feed, settle_bet, settle_feed_paper_signals, settle_open_bets


def bet(market, **overrides):
    row = {
        "signal_id": "sig-1", "fixture_id": "fixture-1",
        "league_code": "UCL", "season": "2026/2027",
        "kickoff_utc": "2026-11-24", "home_team": "Home", "away_team": "Away",
        "market": market, "price": 1.9, "point": 2.5, "team": None,
        "outcome": None, "model_probability": 0.7, "edge": 0.1,
        "expected_value": 0.33, "provider_market_group": "total goals",
    }
    row.update(overrides)
    return row


class UefaPaperSettlementTests(unittest.TestCase):
    def test_settles_match_total_and_team_total(self):
        fixture = {"status": "finished", "homeScore": 2, "awayScore": 1}
        total = settle_bet(bet("match_total_over", point=2.5), fixture, "2026-11-25T00:00:00Z")
        self.assertEqual(total["result"], "WIN")
        self.assertAlmostEqual(total["profit_units"], 0.9)
        team = settle_bet(bet("team_total_over", team="home", point=1.5), fixture)
        self.assertEqual(team["result"], "WIN")
        away = settle_bet(bet("team_total_over", team="away", point=1.5), fixture)
        self.assertEqual(away["result"], "LOSS")
        self.assertEqual(away["profit_units"], -1.0)

    def test_settles_btts_and_double_chance(self):
        fixture = {"status": "finished", "homeScore": 1, "awayScore": 0}
        btts_yes = settle_bet(bet("btts", outcome="yes"), fixture)
        btts_no = settle_bet(bet("btts", outcome="no"), fixture)
        dc_1x = settle_bet(bet("double_chance", outcome="1x"), fixture)
        dc_x2 = settle_bet(bet("double_chance", outcome="x2"), fixture)
        self.assertEqual(btts_yes["result"], "LOSS")
        self.assertEqual(btts_no["result"], "WIN")
        self.assertEqual(dc_1x["result"], "WIN")
        self.assertEqual(dc_x2["result"], "LOSS")

    def test_zero_handicap_draw_is_push(self):
        fixture = {"status": "finished", "homeScore": 1, "awayScore": 1}
        result = settle_bet(bet("handicap", team="home", point=0.0), fixture)
        self.assertEqual(result["result"], "PUSH")
        self.assertEqual(result["profit_units"], 0.0)

    def test_settles_bets_from_published_uefa_feed(self):
        feed = {"competitions": [{"code": "UCL", "matches": [{
            "id": "m_18IfVI", "status": "FINISHED", "homeScore": 1, "awayScore": 4,
        }]}]}
        fixtures = fixture_index_from_feed(feed)
        bets = [
            bet("btts", fixture_id="m_18IfVI", signal_id="btts-yes", outcome="yes", price=1.58),
            bet("team_total_over", fixture_id="m_18IfVI", signal_id="home-over05", team="home", point=0.5, price=1.55),
        ]
        settled = settle_open_bets(bets, fixtures, [], "2026-11-26T00:00:00Z")
        self.assertEqual([row["result"] for row in settled], ["WIN", "WIN"])
        self.assertEqual([row["profit_units"] for row in settled], [0.58, 0.55])

    def test_updates_embedded_paper_signals_in_uefa_feed(self):
        feed = {"competitions": [{"code": "UCL", "matches": [{
            "id": "m_18IfVI", "status": "FINISHED", "homeScore": 1, "awayScore": 4,
            "paperSignals": [
                {"signal_id": "btts-yes", "market": "btts", "outcome": "yes", "price": 1.58},
                {"signal_id": "home-over05", "market": "team_total_over", "team": "home", "point": 0.5, "price": 1.55},
            ],
        }]}]}
        changed = settle_feed_paper_signals(feed, "2026-11-26T00:00:00Z")
        rows = feed["competitions"][0]["matches"][0]["paperSignals"]
        self.assertEqual(changed, 2)
        self.assertEqual([row["result"] for row in rows], ["WIN", "WIN"])
        self.assertEqual([row["profit_units"] for row in rows], [0.58, 0.55])
        self.assertEqual([row["home_score"] for row in rows], [1, 1])
        self.assertEqual([row["away_score"] for row in rows], [4, 4])

    def test_unfinished_and_duplicate_signals_are_not_settled(self):
        bets = [bet("match_total_over")]
        unfinished = {"matches": {"fixture-1": {"status": "not_started"}}}
        self.assertEqual(settle_open_bets(bets, unfinished, []), [])
        finished = {"matches": {"fixture-1": {
            "status": "finished", "homeScore": 3, "awayScore": 0,
        }}}
        existing = [{"signal_id": "sig-1"}]
        self.assertEqual(settle_open_bets(bets, finished, existing), [])


if __name__ == "__main__":
    unittest.main()
