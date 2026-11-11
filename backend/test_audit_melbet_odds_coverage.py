import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_melbet_odds_coverage import normalize_market_set

class MelBetCoverageParserTests(unittest.TestCase):
 def test_parses_all_fourteen_markets(self):
  markets={
   "double_chance":{"status":"open","stage":"prematch","stakes":{"home_or_draw":{"factor":1.25},"draw_or_away":{"factor":2.1}}},
   "both_teams_to_score":{"status":"open","stage":"prematch","stakes":{"yes":{"factor":1.8}}},
   "total_goals":{"status":"open","stage":"prematch","stakes":{"over":{"lines":[{"argument":p,"factor":1.9+p/10} for p in (1.5,2.5,3.5)]}}},
   "total_team_1":{"status":"open","stage":"prematch","stakes":{"over":{"lines":[{"argument":p,"factor":1.7+p/10} for p in (0.5,1.5,2.5,3.5)]}}},
   "total_team_2":{"status":"open","stage":"prematch","stakes":{"over":{"lines":[{"argument":p,"factor":1.8+p/10} for p in (0.5,1.5,2.5,3.5)]}}},
  }
  result=normalize_market_set({"oddsBk":{"melbet":{"markets":markets}}})
  self.assertEqual(sum(v is not None for v in result.values()),14)
  self.assertEqual(result["dc_1x"],1.25)
  self.assertEqual(result["dc_x2"],2.1)
  self.assertEqual(result["btts_yes"],1.8)
  self.assertEqual(result["home_over_3_5"],2.05)
  self.assertEqual(result["away_over_0_5"],1.85)
 def test_excludes_suspended_closed_live_and_first_half(self):
  source={"oddsBk":{"melbet":{"markets":{
   "total_goals":{"status":"open","stage":"prematch","stakes":{"over":{"lines":[{"argument":2.5,"factor":1.9,"suspended":True}]}}},
   "total_team_1":{"status":"closed","stage":"prematch","stakes":{"over":{"lines":[{"argument":1.5,"factor":1.8}]}}},
   "first_half_total_team_2":{"status":"open","stage":"prematch","stakes":{"over":{"lines":[{"argument":0.5,"factor":1.7}]}}},
   "total_team_2":{"status":"open","stage":"live","stakes":{"over":{"lines":[{"argument":0.5,"factor":1.7}]}}},
  }}}}
  self.assertTrue(all(v is None for v in normalize_market_set(source).values()))
 def test_does_not_mix_betboom_prices(self):
  source={"oddsBk":{"melbet":{"markets":{"btts":{"status":"open","stage":"prematch","stakes":{"yes":{"factor":1.81}}}}},"betboom":{"markets":{"btts":{"status":"open","stage":"prematch","stakes":{"yes":{"factor":9.99}}}}}}}
  self.assertEqual(normalize_market_set(source)["btts_yes"],1.81)

if __name__=="__main__": unittest.main()
