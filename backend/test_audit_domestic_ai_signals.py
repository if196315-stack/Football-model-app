import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_domestic_ai_signals import (
    MIN_EDGE_PP, MIN_EV, MIN_ODDS, MIN_PROBABILITY,
    ai_eligibility, build, odds_for, strongest_display_signal,
)

def melbet_match(best, **extra):
    match = {"home": "Alpha", "away": "Beta", "status": "SCHEDULED",
             "utcDate": "2099-01-01T18:00:00Z",
             "odds": {"bookmaker": "MELBET", "source": "API-Sport / MelBet", "best": best}}
    match.update(extra)
    return match

class AndroidParityTests(unittest.TestCase):
    def test_line_leader_is_max_kelly_among_positive_ev_not_max_probability(self):
        match = melbet_match({
            "doubleChance": {"1x": 1.8},
            "totals": [{"side": "over", "point": 2.5, "price": 10.0}],
        })
        prediction = {"signals": [
            {"side": "1X", "modelProbability": .80},
            {"side": "ТБ 2.5", "modelProbability": .62},
        ]}
        leader = strongest_display_signal(prediction, match)
        self.assertEqual(leader[0]["side"], "ТБ 2.5")
        self.assertAlmostEqual(leader[3] / (leader[2] - 1), (.62 * 10.0 - 1) / 9.0)

    def test_negative_ev_fallback_is_highest_probability(self):
        match = melbet_match({"doubleChance": {"1x": 1.5, "x2": 1.6}})
        prediction = {"signals": [
            {"side": "1X", "modelProbability": .55},
            {"side": "X2", "modelProbability": .58},
        ]}
        self.assertEqual(strongest_display_signal(prediction, match)[0]["side"], "X2")

    def test_line_does_not_promote_second_signal_when_leader_fails_ai(self):
        match = melbet_match({"doubleChance": {"1x": 2.0, "x2": 2.0}})
        prediction = {"signals": [
            {"side": "1X", "modelProbability": .59},
            {"side": "X2", "modelProbability": .90},
        ]}
        leader = strongest_display_signal(prediction, match)
        self.assertEqual(leader[0]["side"], "X2")
        # Swap probabilities: the high-P 1X becomes the line leader and fails edge;
        # the other market is not evaluated as a fallback by the match-level audit.
        prediction = {"signals": [
            {"side": "1X", "modelProbability": .90, "formAdjustmentPp": -50},
            {"side": "X2", "modelProbability": .70},
        ]}
        leader = strongest_display_signal(prediction, match)
        self.assertEqual(leader[0]["side"], "1X")
        result, reason = ai_eligibility(leader[0], match, leader[2], leader[1], leader[3])
        self.assertIsNone(result)
        self.assertEqual(reason, "edge_below_5pp")

    def test_exact_14_supported_market_prices(self):
        match = melbet_match({
            "doubleChance": {"1x": 1.7, "x2": 1.8},
            "btts": {"yes": 1.9, "no": 2.0},
            "totals": [{"side": "over", "point": p, "price": 1.9} for p in (1.5, 2.5, 3.5)],
            "teamTotals": [
                {"team": team, "side": "over", "point": p, "price": 1.9}
                for team in ("home", "away") for p in (.5, 1.5, 2.5, 3.5)
            ],
        })
        sides = ["1X", "X2", "ОЗ ДА", "ТБ 1.5", "ТБ 2.5", "ТБ 3.5"] + [
            f"ИТБ {p} · {team}" for team in ("Alpha", "Beta") for p in (.5, 1.5, 2.5, 3.5)
        ]
        for side in sides:
            with self.subTest(side=side):
                expected = {"1X": 1.7, "X2": 1.8}.get(side, 1.9)
                self.assertEqual(odds_for({"side": side}, match), expected)

    def test_rejects_betboom_and_unknown_or_mismatched_board(self):
        best = {"doubleChance": {"1x": 2.0}}
        for odds in (
            {"bookmaker": "BETBOOM", "source": "API-Sport / BetBoom", "best": best},
            {"bookmaker": "MELBET", "source": "API-Sport / MelBet", "best": {"doubleChance": {"x2": 2.0}}},
            {"source": "API-Sport / BetBoom", "best": best},
        ):
            self.assertIsNone(odds_for({"side": "1X"}, {"home": "Alpha", "away": "Beta", "odds": odds}))

    def test_ai_filter_exact_thresholds_and_edge_formula(self):
        match = melbet_match({})
        signal = {"side": "1X", "modelProbability": .60, "formAdjustmentPp": 0}
        odds = 2.0
        ev = .60 * odds - 1
        admitted, reason = ai_eligibility(signal, match, odds, .60, ev)
        self.assertIsNotNone(admitted)
        self.assertIsNone(reason)
        self.assertEqual(admitted["edge_pp"], 10.0)
        self.assertEqual((MIN_ODDS, MIN_PROBABILITY, MIN_EV, MIN_EDGE_PP), (1.5, .60, .05, 5.0))

    def test_ai_rejects_probability_ev_edge_and_btts_no(self):
        cases = [
            ({"side": "1X", "formAdjustmentPp": 0}, 1.9, .599, .1381, "probability_below_60pct"),
            ({"side": "1X", "formAdjustmentPp": 0}, 1.6, .60, -.04, "ev_below_5pct"),
            ({"side": "1X", "formAdjustmentPp": -6}, 2.0, .60, .20, "edge_below_5pp"),
            ({"side": "ОЗ НЕТ", "formAdjustmentPp": 0}, 2.0, .60, .20, "excluded_btts_no"),
            ({"side": "1X", "formAdjustmentPp": 0, "noPositiveSignal": True}, 2.0, .60, .20, "no_positive_signal"),
        ]
        for signal, odds, probability, ev, expected in cases:
            with self.subTest(expected=expected):
                result, reason = ai_eligibility(signal, melbet_match({}), odds, probability, ev)
                self.assertIsNone(result)
                self.assertEqual(reason, expected)

    def test_paper_only_uses_raw_edge_not_adjusted_edge(self):
        signal = {"side": "1X", "paperOnly": True, "edgePp": 5.0, "formAdjustmentPp": -100}
        result, reason = ai_eligibility(signal, melbet_match({}), 2.0, .60, .20)
        self.assertIsNotNone(result)
        self.assertEqual(result["edge_pp"], 5.0)

    def test_audit_counts_one_leader_not_all_qualifying_markets(self):
        data = {"competitions": [{"code": "PL", "matches": [{
            "status": "SCHEDULED", "utcDate": "2099-01-01T18:00:00Z", "home": "Alpha", "away": "Beta",
            "odds": {"bookmaker": "MELBET", "source": "MelBet", "best": {
                "doubleChance": {"1x": 2.0, "x2": 2.0}}},
            "prediction": {"signals": [
                {"side": "1X", "modelProbability": .60},
                {"side": "X2", "modelProbability": .95},
            ]},
        }]}]}
        report = build(data, today=__import__("datetime").date(2098, 1, 1))
        counts = report["leagues"]["ENG"]["counts"]
        self.assertEqual(counts["with_line_leader"], 1)
        self.assertEqual(counts.get("qualifying_signals", 0), 1)
        self.assertEqual(report["leagues"]["ENG"]["qualifying_matches"][0]["line_leader"], "X2")

if __name__ == "__main__":
    unittest.main()
