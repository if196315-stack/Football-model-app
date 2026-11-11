import unittest

from predict_uefa_markets import predict_upcoming


class UefaMarketPredictionTests(unittest.TestCase):
    def test_generates_markets_from_only_pre_matchday_xg(self):
        history = {"matches": [
            {"id": "h1", "leagueCode": "UCL", "date": "2026-10-01",
             "home": "Alpha", "away": "Gamma", "homeXg": 1.8, "awayXg": 0.7, "pairedXg": True},
            {"id": "h2", "leagueCode": "UCL", "date": "2026-10-08",
             "home": "Delta", "away": "Beta", "homeXg": 1.1, "awayXg": 1.4, "pairedXg": True},
            {"id": "h3", "leagueCode": "UCL", "date": "2026-10-15",
             "home": "Alpha", "away": "Delta", "homeXg": 2.0, "awayXg": 0.8, "pairedXg": True},
            {"id": "h4", "leagueCode": "UCL", "date": "2026-10-22",
             "home": "Gamma", "away": "Beta", "homeXg": 0.9, "awayXg": 1.6, "pairedXg": True},
            # Same-day data must not leak into predictions for 2026-11-10.
            {"id": "same-day", "leagueCode": "UCL", "date": "2026-11-10",
             "home": "Alpha", "away": "Beta", "homeXg": 4.5, "awayXg": 0.1, "pairedXg": True},
        ]}
        fixtures = {"matches": {
            "future": {"id": "future", "leagueCode": "UCL", "season": "2026/2027",
                       "date": "2026-11-10", "status": "not_started",
                       "home": "Alpha", "away": "Beta"},
        }}
        prediction = predict_upcoming(
            history, fixtures, predicted_at="2026-11-09T12:00:00+00:00"
        )["future"]
        self.assertEqual(prediction["history_max_date"], "2026-10-22")
        self.assertEqual(prediction["model_version"], "uefa-xg-poisson-v1")
        markets = prediction["markets"]
        self.assertAlmostEqual(markets["match_total"]["over"]["2.5"]
                               + markets["match_total"]["under"]["2.5"], 1.0, places=8)
        self.assertAlmostEqual(markets["btts"]["yes"] + markets["btts"]["no"], 1.0, places=8)
        self.assertTrue(0.0 <= markets["double_chance"]["1x"] <= 1.0)
        self.assertEqual(markets["handicap_zero"]["home"]["push"],
                         markets["handicap_zero"]["away"]["push"])


    def test_generates_independent_predictions_for_uecl_and_unl(self):
        history_rows = []
        fixtures = {}
        for league in ("UECL", "UNL"):
            teams = (f"{league}-A", f"{league}-B", f"{league}-C", f"{league}-D")
            past = [
                (teams[0], teams[2], 1.7, 0.6),
                (teams[3], teams[1], 1.2, 1.1),
                (teams[0], teams[3], 2.1, 0.8),
                (teams[2], teams[1], 0.9, 1.5),
            ]
            for index, (home, away, home_xg, away_xg) in enumerate(past):
                history_rows.append({
                    "id": f"{league}-past-{index}", "leagueCode": league,
                    "date": f"2026-10-{index + 1:02d}", "home": home, "away": away,
                    "homeXg": home_xg, "awayXg": away_xg, "pairedXg": True,
                })
            fixtures[f"{league}-future"] = {
                "id": f"{league}-future", "leagueCode": league,
                "season": "2026/2027", "date": "2026-11-10",
                "status": "not_started", "home": teams[0], "away": teams[1],
            }

        predictions = predict_upcoming(
            {"matches": history_rows}, {"matches": fixtures},
            predicted_at="2026-11-09T12:00:00Z",
        )
        self.assertEqual(set(predictions), {"UECL-future", "UNL-future"})
        for league in ("UECL", "UNL"):
            prediction = predictions[f"{league}-future"]
            self.assertEqual(prediction["league_code"], league)
            self.assertEqual(prediction["history_max_date"], "2026-10-04")
            markets = prediction["markets"]
            self.assertIn("1x", markets["double_chance"])
            self.assertIn("x2", markets["double_chance"])
            self.assertIn("yes", markets["btts"])
            for line in ("1.5", "2.5", "3.5"):
                self.assertIn(line, markets["match_total"]["over"])
            for team in ("home", "away"):
                for line in ("0.5", "1.5", "2.5", "3.5"):
                    self.assertIn(line, markets["team_total"][team]["over"])

    def test_does_not_predict_team_without_prior_competition_history(self):
        history = {"matches": []}
        fixtures = {"matches": {"1": {
            "id": "1", "leagueCode": "UEL", "date": "2026-11-10",
            "status": "not_started", "home": "New Team", "away": "Unknown",
        }}}
        self.assertEqual(predict_upcoming(history, fixtures, "2026-11-09T12:00:00Z"), {})


if __name__ == "__main__":
    unittest.main()
