import tempfile, unittest
from pathlib import Path
from diagnose_pitchapi_uefa_xg import summarize_shots, select_sample

class XgDiagnosticTests(unittest.TestCase):
    def test_shot_payload_and_team_mapping(self):
        data={"shots":[
            {"team_id":"h","expected_goals":0.2},
            {"team_id":"a","expected_goals":0.3},
            {"team_id":"h","expected_goals":None},
            {"team_id":"other","expected_goals":0.9},
            {"team_id":"h","expected_goals":True}]}
        result=summarize_shots(data,"h","a")
        self.assertEqual(result["validShots"],2)
        self.assertEqual(result["homeXg"],0.2)
        self.assertEqual(result["awayXg"],0.3)
        self.assertTrue(result["pairedXg"])

    def test_empty_shots_are_unknown_not_zero(self):
        result=summarize_shots({"shots":[]},"h","a")
        self.assertIsNone(result["homeXg"])
        self.assertIsNone(result["awayXg"])
        self.assertFalse(result["pairedXg"])

    def test_sample_is_finished_and_bounded(self):
        rows=[{"id":str(i),"leagueCode":"UCL","season":"2025/2026","status":"finished",
               "homeTeamId":"h","awayTeamId":"a","date":str(i)} for i in range(20)]
        rows.append({"id":"future","status":"not_started","homeTeamId":"h","awayTeamId":"a"})
        sample=select_sample(rows,per_group=3,limit=2)
        self.assertEqual(len(sample),2)
        self.assertTrue(all(x["status"]=="finished" for x in sample))

if __name__=="__main__": unittest.main()
