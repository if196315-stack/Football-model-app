import unittest

from soccer365_backtest import walk_forward_backtest


def match(mid, day, home="A", away="B", **overrides):
    row = {
        "id": mid,
        "utcDate": f"2026-08-{day:02d}T15:00:00+00:00",
        "home": home,
        "away": away,
        "homeTeamId": home,
        "awayTeamId": away,
        "competitionId": "league-1",
        "competitionType": "league",
        "season": "2026-2027",
        "status": "FINISHED",
        "homeScore": 1,
        "awayScore": 0,
        "statistics": {"homeXg": 1.2, "awayXg": 0.8, "xgAvailable": True},
    }
    row.update(overrides)
    return row


class Soccer365BacktestTests(unittest.TestCase):
    def test_requires_prior_team_sample(self):
        rows = [match(f"m{i}", i + 1) for i in range(6)]
        result = walk_forward_backtest(rows, min_team_matches=5)
        self.assertEqual(result["evaluatedMatches"], 1)
        self.assertEqual(result["matches"][0]["id"], "m5")
        self.assertEqual(result["matches"][0]["priorHomeMatches"], 5)

    def test_same_kickoff_batch_cannot_train_itself(self):
        rows = [match(f"m{i}", i + 1) for i in range(5)]
        rows += [match("same-a", 6), match("same-b", 6)]
        result = walk_forward_backtest(rows, min_team_matches=5)
        self.assertEqual(result["evaluatedMatches"], 2)
        self.assertEqual([m["priorHomeMatches"] for m in result["matches"]], [5, 5])

    def test_competition_type_and_season_are_isolated(self):
        rows = [match(f"m{i}", i + 1) for i in range(5)]
        rows.append(match("cup", 6, competitionType="domestic_cup"))
        rows.append(match("next-season", 7, season="2027-2028"))
        result = walk_forward_backtest(rows, min_team_matches=5)
        self.assertEqual(result["evaluatedMatches"], 0)

    def test_missing_xg_does_not_enter_history_but_can_be_evaluated(self):
        rows = [match(f"m{i}", i + 1) for i in range(5)]
        rows.append(match("no-xg", 6, statistics={"homeXg": None, "awayXg": None}))
        rows.append(match("target", 7))
        result = walk_forward_backtest(rows, min_team_matches=5)
        self.assertEqual(result["evaluatedMatches"], 2)
        self.assertEqual(result["matches"][-1]["id"], "target")
        self.assertEqual(result["matches"][-1]["priorHomeMatches"], 5)

    def test_naive_timestamp_is_excluded(self):
        rows = [match(f"m{i}", i + 1) for i in range(5)]
        rows.append(match("naive", 6, utcDate="2026-08-06T15:00:00"))
        result = walk_forward_backtest(rows, min_team_matches=5)
        self.assertEqual(result["evaluatedMatches"], 0)

    def test_duplicate_ids_count_once(self):
        first = match("dup", 1)
        result = walk_forward_backtest([first, dict(first)], min_team_matches=1)
        self.assertEqual(result["evaluatedMatches"], 0)

    def test_scheduled_match_neither_evaluated_nor_used_as_history(self):
        rows = [match(f"m{i}", i + 1) for i in range(5)]
        rows.append(match("scheduled", 6, status="SCHEDULED"))
        rows.append(match("target", 7))
        result = walk_forward_backtest(rows, min_team_matches=5)
        self.assertEqual(result["evaluatedMatches"], 1)
        self.assertEqual(result["matches"][0]["id"], "target")


if __name__ == "__main__":
    unittest.main()
