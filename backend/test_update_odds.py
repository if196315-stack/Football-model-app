import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from update_odds import read_market, convert_event


class OddsMarketParsingTests(unittest.TestCase):
    def test_shared_model_core_has_no_legacy_provider_ingestion(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import update_data
        self.assertEqual(update_data.WINDOW, 30)
        self.assertTrue(callable(update_data.predict))
        self.assertFalse(hasattr(update_data, "parquet"))
        self.assertFalse(hasattr(update_data, "history"))
        self.assertFalse(hasattr(update_data, "main"))

    def test_ah0_and_dnb_are_removed_from_archived_predictions(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import update_data
        prediction = {
            "dnbHome": 0.6, "dnbAway": 0.4, "ah0Home": 0.6,
            "strongestSignal": {"market": "Фора 0 хозяев", "side": "Фора 0 · Arsenal"}
        }
        update_data.strip_forbidden_market_fields(prediction)
        self.assertNotIn("dnbHome", prediction)
        self.assertNotIn("dnbAway", prediction)
        self.assertNotIn("ah0Home", prediction)
        self.assertIsNone(prediction["strongestSignal"])

    def test_prediction_archive_sanitizes_existing_and_new_entries(self):
        import json
        import tempfile
        from pathlib import Path
        import update_data

        with tempfile.TemporaryDirectory() as tmp:
            old_out = update_data.OUT
            update_data.OUT = Path(tmp) / "data.json"
            old_prediction = {
                "dnbHome": 0.61, "ah0Away": 0.39,
                "strongestSignal": {"market": "DNB", "side": "Draw No Bet"}
            }
            update_data.OUT.write_text(json.dumps({
                "predictionArchive": {"matches": {
                    "old-id": {"prediction": old_prediction, "status": "SCHEDULED"}
                }}
            }), encoding="utf-8")
            new_prediction = {
                "dnbAway": 0.4,
                "strongestSignal": {"market": "Фора 0 гостей", "side": "Фора 0"}
            }
            payload = {"competitions": [{"name": "Test League", "matches": [{
                "id": "new-id", "status": "SCHEDULED",
                "utcDate": "2099-01-01T12:00:00Z",
                "home": "Home", "away": "Away", "prediction": new_prediction
            }]}]}
            try:
                archive = update_data.update_prediction_archive(payload)
                for match_id in ("old-id", "new-id"):
                    prediction = archive["matches"][match_id]["prediction"]
                    self.assertFalse(any(k in prediction for k in (
                        "dnbHome", "dnbAway", "ah0Home", "ah0Away"
                    )))
                    self.assertIsNone(prediction["strongestSignal"])
            finally:
                update_data.OUT = old_out
    def test_double_chance_labels(self):
        bookmaker = {"markets": [{"key": "double_chance", "outcomes": [
            {"name": "Home/Draw", "price": 1.35},
            {"name": "Draw/Away", "price": 1.65},
            {"name": "Home/Away", "price": 1.25},
        ]}]}
        self.assertEqual(read_market(bookmaker, "double_chance", "Arsenal", "Chelsea"),
                         {"1x": 1.35, "x2": 1.65, "12": 1.25})

    def test_spreads_exclude_zero_and_preserve_sign(self):
        bookmaker = {"markets": [{"key": "spreads", "outcomes": [
            {"name": "Arsenal", "point": 0, "price": 1.9},
            {"name": "Arsenal", "point": -1.5, "price": 2.2},
            {"name": "Chelsea", "point": 1.5, "price": 1.7},
            {"name": "Arsenal", "point": -0.25, "price": 1.95},
        ]}]}
        self.assertEqual(read_market(bookmaker, "spreads", "Arsenal", "Chelsea"), [
            {"team": "home", "point": -1.5, "price": 2.2},
            {"team": "away", "point": 1.5, "price": 1.7},
        ])

    def test_convert_event_never_keeps_ah0(self):
        event = {"id": "event-1", "home_team": "Arsenal", "away_team": "Chelsea",
                 "bookmakers": [{"key": "book", "title": "Book", "markets": [
                     {"key": "spreads", "outcomes": [
                         {"name": "Arsenal", "point": 0, "price": 1.9},
                         {"name": "Arsenal", "point": -0.5, "price": 2.0},
                     ]}
                 ]}]}
        quote = convert_event(event)
        self.assertEqual(len(quote["best"]["spreads"]), 1)
        self.assertEqual(quote["best"]["spreads"][0]["point"], -0.5)


if __name__ == "__main__":
    unittest.main()
