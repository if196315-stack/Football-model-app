import unittest
from datetime import date, datetime, timezone

from refresh_match_results_api_sport import apply_provider_match, fetch_recent_matches, sync_feed


def source(status="finished", final=True, home=2, away=1):
    return {
        "id": 123,
        "status": status,
        "startTimestamp": int(datetime(2026, 11, 22, 17, 0, tzinfo=timezone.utc).timestamp() * 1000),
        "homeTeam": {"name": "Manchester City"},
        "awayTeam": {"name": "Liverpool"},
        "homeScore": {"current": home},
        "awayScore": {"current": away},
        "scoreStatus": {"final": final},
    }


class Response:
    ok = True
    def __init__(self, payload):
        self.payload = payload
    def json(self):
        return self.payload


class Session:
    def __init__(self):
        self.calls = []
    def get(self, url, params, timeout):
        self.calls.append((url, params, timeout))
        return Response({
            "matches": [source()],
            "totalPages": 2 if params["page"] == 1 else 2,
        }) if params["page"] == 1 else Response({"matches": [source(home=3)], "totalPages": 2})


class FastResultSyncTests(unittest.TestCase):
    def test_fetches_recent_finished_and_live_matches_with_final_flag(self):
        session = Session()
        rows = fetch_recent_matches(session, date(2026, 11, 22))
        self.assertEqual(len(rows), 2)
        self.assertEqual(len(session.calls), 2)
        params = session.calls[0][1]
        self.assertEqual(params["date_from"], "2026-11-18")
        self.assertEqual(params["date_to"], "2026-11-22")
        self.assertEqual(params["status"], "inprogress,finished")
        self.assertIn("scoreStatus", params["fields"])

    def test_sync_copies_final_score_and_confirmation_without_touching_odds(self):
        payload = {"competitions": [{"code": "PL", "matches": [{
            "id": "pitch-1", "home": "Manchester City", "away": "Liverpool",
            "utcDate": "2026-11-22T17:00:00Z", "status": "IN_PLAY",
            "odds": {"bookmaker": "MELBET", "prematchActive": True},
            "prediction": {"1x": 0.8},
        }]}]}
        result = sync_feed(payload, [source()], "2026-11-22T19:00:00+00:00")
        match = payload["competitions"][0]["matches"][0]
        self.assertEqual(result["changed"], 1)
        self.assertEqual(match["status"], "FINISHED")
        self.assertEqual((match["homeScore"], match["awayScore"]), (2, 1))
        self.assertIs(match["scoreStatusFinal"], True)
        self.assertEqual(match["resultProvider"], "API-Sport")
        self.assertEqual(match["odds"]["bookmaker"], "MELBET")
        self.assertEqual(match["prediction"]["1x"], 0.8)

    def test_uses_regulation_score_not_extra_time_score(self):
        fixture = source(home=3, away=2)
        fixture["homeScore"].update({"period1": 1, "period2": 1})
        fixture["awayScore"].update({"period1": 0, "period2": 1})
        target = {"status": "IN_PLAY"}
        apply_provider_match(target, fixture, "now")
        self.assertEqual((target["homeScore"], target["awayScore"]), (2, 1))

    def test_finished_but_not_final_remains_unconfirmed(self):
        target = {"status": "IN_PLAY", "homeScore": 0, "awayScore": 0}
        changed = apply_provider_match(target, source(final=False), "now")
        self.assertTrue(changed)
        self.assertEqual(target["status"], "FINISHED")
        self.assertIs(target["scoreStatusFinal"], False)
        self.assertEqual(target["homeScore"], 2)

    def test_confirmed_final_is_not_downgraded_by_stale_response(self):
        target = {"status": "FINISHED", "homeScore": 2, "awayScore": 1,
                  "scoreStatusFinal": True}
        changed = apply_provider_match(target, source(final=False, home=1, away=1), "later")
        self.assertFalse(changed)
        self.assertIs(target["scoreStatusFinal"], True)
        self.assertEqual((target["homeScore"], target["awayScore"]), (2, 1))

    def test_live_status_is_not_mistaken_for_final(self):
        target = {"status": "SCHEDULED"}
        apply_provider_match(target, source(status="inprogress", final=None), "now")
        self.assertEqual(target["status"], "IN_PLAY")
        self.assertIsNone(target["scoreStatusFinal"])

    def test_ambiguous_or_unmatched_fixture_is_untouched(self):
        payload = {"competitions": [{"matches": [{
            "id": "pitch-1", "home": "Unrelated FC", "away": "Other FC",
            "utcDate": "2026-11-22T17:00:00Z", "status": "IN_PLAY",
        }]}]}
        result = sync_feed(payload, [source()])
        self.assertEqual(result["matched"], 0)
        self.assertEqual(result["changed"], 0)


if __name__ == "__main__":
    unittest.main()
