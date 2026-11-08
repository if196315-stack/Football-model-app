import json
import tempfile
import unittest
from pathlib import Path

from soccer365_store import StoreFormatError, append_jsonl, plan_append, read_jsonl


def match(**overrides):
    value = {
        "id": "s365-1",
        "source": "Soccer365",
        "sourceUrl": "https://soccer365.net/live/match-alpha-beta/",
        "home": "Alpha FC",
        "away": "Beta FC",
        "competitionId": "eng-prem",
        "competitionType": "league",
        "season": "2026-2027",
        "utcDate": "2026-08-01T15:00:00+00:00",
        "status": "FINISHED",
        "homeScore": 2,
        "awayScore": 1,
        "statistics": {"homeXg": 1.2, "awayXg": 0.8, "xgAvailable": True},
    }
    value.update(overrides)
    return value


class Soccer365StoreTests(unittest.TestCase):
    def test_new_records_are_appended_and_idempotent(self):
        first = match()
        plan = plan_append([], [first, first])
        self.assertEqual(plan["appended"], ["s365-1"])
        self.assertEqual(plan["unchanged"], ["s365-1"])
        self.assertEqual(len(plan["records"]), 1)

    def test_changed_existing_record_is_conflict_not_overwrite(self):
        original = match()
        changed = match(homeScore=3)
        plan = plan_append([original], [changed])
        self.assertEqual(plan["conflicts"], ["s365-1"])
        self.assertEqual(plan["records"], [original])

    def test_missing_xg_is_preserved_as_null(self):
        source = match(statistics={"homeXg": None, "awayXg": None, "xgAvailable": False})
        plan = plan_append([], [source])
        self.assertEqual(plan["records"][0]["statistics"]["homeXg"], None)
        self.assertEqual(plan["records"][0]["statistics"]["awayXg"], None)

    def test_records_with_integrity_errors_are_rejected(self):
        plan = plan_append([], [match(away="Alpha FC")])
        self.assertEqual(plan["appended"], [])
        self.assertEqual(plan["rejected"][0]["reason"], "integrity_error")

    def test_jsonl_append_is_atomic_and_preserves_existing_record(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history" / "matches.jsonl"
            first, second = match(), match(id="s365-2")
            result = append_jsonl(path, [first])
            self.assertEqual(result["appended"], ["s365-1"])
            result = append_jsonl(path, [first, second])
            self.assertEqual(result["appended"], ["s365-2"])
            self.assertEqual(read_jsonl(path), [first, second])

    def test_corrupt_existing_jsonl_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matches.jsonl"
            path.write_text("{not json}\n", encoding="utf-8")
            with self.assertRaisesRegex(StoreFormatError, "line 1"):
                read_jsonl(path)


if __name__ == "__main__":
    unittest.main()
