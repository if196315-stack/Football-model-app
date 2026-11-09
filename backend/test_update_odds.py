import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from update_odds import read_market, convert_event, merge_quote\nfrom update_bsd_odds import make_quote


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
                update_data.update_prediction_archive(payload)
                archive = payload["predictionArchive"]
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
            {"name": "Home or Draw", "price": 1.35},
            {"name": "Draw or Away", "price": 1.65},
            {"name": "Home or Away", "price": 1.25},
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


    def test_btts_quotes_are_captured_for_observation(self):
        bookmaker = {"markets": [{"key": "btts", "outcomes": [
            {"name": "Yes", "price": 1.72},
            {"name": "No", "price": 2.08},
        ]}]}
        self.assertEqual(read_market(bookmaker, "btts", "Arsenal", "Chelsea"),
                         {"yes": 1.72, "no": 2.08})

    def test_convert_event_keeps_btts_separate_from_actionable_markets(self):
        event = {"id": "event-btts", "home_team": "Arsenal", "away_team": "Chelsea",
                 "bookmakers": [{"key": "book", "title": "Book", "markets": [
                     {"key": "btts", "outcomes": [
                         {"name": "Yes", "price": 1.72},
                         {"name": "No", "price": 2.08},
                     ]},
                 ]}]}
        quote = convert_event(event)
        self.assertEqual(quote["best"]["btts"], {"yes": 1.72, "no": 2.08})
        self.assertEqual(quote["bookmakers"][0]["btts"], {"yes": 1.72, "no": 2.08})

    def test_team_totals_parse_home_and_away_lines(self):
        bookmaker = {"markets": [{"key": "team_totals", "outcomes": [
            {"name": "Over", "description": "Arsenal", "point": 1.5, "price": 1.82},
            {"name": "Under", "description": "Arsenal", "point": 1.5, "price": 2.02},
            {"name": "Over", "description": "Chelsea", "point": 0.5, "price": 1.74},
            {"name": "Over", "description": "Unknown FC", "point": 0.5, "price": 1.9},
            {"name": "Over", "description": "Arsenal", "point": 1.5, "price": 1.0},
        ]}]}
        self.assertEqual(read_market(bookmaker, "team_totals", "Arsenal", "Chelsea"), [
            {"team": "home", "point": 1.5, "side": "over", "price": 1.82},
            {"team": "home", "point": 1.5, "side": "under", "price": 2.02},
            {"team": "away", "point": 0.5, "side": "over", "price": 1.74},
        ])

    def test_convert_event_serializes_team_totals_and_best_bookmaker(self):
        event = {"id": "event-team-totals", "home_team": "Arsenal", "away_team": "Chelsea",
                 "bookmakers": [{"key": "book", "title": "Bookmaker", "markets": [
                     {"key": "team_totals", "outcomes": [
                         {"name": "Over", "description": "Arsenal", "point": 1.5, "price": 1.82},
                         {"name": "Under", "description": "Chelsea", "point": 0.5, "price": 1.95},
                     ]}
                 ]}]}
        quote = convert_event(event)
        self.assertEqual(len(quote["best"]["teamTotals"]), 2)
        self.assertEqual(quote["best"]["teamTotals"][0]["team"], "home")
        self.assertEqual(quote["best"]["teamTotals"][0]["side"], "over")
        self.assertEqual(quote["bestBookmakers"]["teamTotals"][0]["bookmaker"], "Bookmaker")

    def test_merge_quote_fills_missing_markets_without_overwriting_bsd(self):
        existing = {
            "eventId": "bsd-event",
            "best": {"h2h": {"home": 2.1, "draw": 3.2}, "totals": {"over25": 1.9}},
            "bestBookmakers": {"h2h": {"home": "BSD consensus"}},
            "bookmakers": [{"key": "bsd_consensus", "title": "BSD consensus"}],
        }
        incoming = {
            "eventId": "odds-event",
            "updatedAt": "2026-11-09T12:00:00+00:00",
            "best": {
                "h2h": {"home": 2.3, "away": 3.4},
                "doubleChance": {"1x": 1.3},
                "teamTotals": [{"team": "home", "point": 1.5, "side": "over", "price": 1.8}],
            },
            "bestBookmakers": {
                "h2h": {"home": "Book A", "away": "Book B"},
                "doubleChance": {"1x": "Book A"},
                "teamTotals": [{"team": "home", "point": 1.5, "side": "over", "bookmaker": "Book A"}],
            },
            "bookmakers": [{"key": "book-a", "title": "Book A"}],
        }
        merged = merge_quote(existing, incoming)
        self.assertEqual(merged["best"]["h2h"]["home"], 2.1)
        self.assertEqual(merged["best"]["h2h"]["away"], 3.4)
        self.assertEqual(merged["best"]["totals"]["over25"], 1.9)
        self.assertEqual(merged["best"]["doubleChance"]["1x"], 1.3)
        self.assertEqual(len(merged["best"]["teamTotals"]), 1)
        self.assertEqual(merged["eventId"], "bsd-event")
        self.assertEqual(len(merged["bookmakers"]), 2)

    def test_bsd_quote_includes_all_documented_match_total_lines(self):
        quote = make_quote({"id": 42}, {"odds": {
            "home_win": 2.1, "draw": 3.2, "away_win": 3.5,
            "over_15_goals": 1.2, "under_15_goals": 4.5,
            "over_25_goals": 1.8, "under_25_goals": 2.0,
            "over_35_goals": 2.7, "under_35_goals": 1.4,
            "btts_yes": 1.7, "btts_no": 2.1,
        }})
        self.assertEqual(quote["best"]["totals"], {
            "over15": 1.2, "under15": 4.5, "over25": 1.8,
            "under25": 2.0, "over35": 2.7, "under35": 1.4,
        })
        self.assertEqual(quote["bestBookmakers"]["totals"]["over35"], "BSD consensus")

if __name__ == "__main__":
    unittest.main()
