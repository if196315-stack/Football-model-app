"""DNB / Asian handicap 0 settlement and value calculations.

The model probability is conditional on a decisive result (draw excluded).
The win/push/loss probabilities are retained separately for unconditional EV.
This module is deliberately not wired into the production candidate selector.
"""
from __future__ import annotations

import math


def analyze_handicap_zero(win_probability: float, push_probability: float,
                          loss_probability: float, odds: float,
                          edge_threshold: float = 0.05,
                          ev_threshold: float = 0.05) -> dict:
    values = (win_probability, push_probability, loss_probability, odds)
    if any(isinstance(value, bool) or not isinstance(value, (int, float))
           or not math.isfinite(float(value)) for value in values):
        raise ValueError("probabilities and odds must be finite numbers")
    win, push, loss, price = map(float, values)
    if any(value < 0.0 or value > 1.0 for value in (win, push, loss)):
        raise ValueError("probabilities must be between 0 and 1")
    if abs((win + push + loss) - 1.0) > 1e-6:
        raise ValueError("win + push + loss probabilities must sum to 1")
    if price <= 1.0:
        raise ValueError("decimal odds must be greater than 1")
    decisive = win + loss
    if decisive <= 0.0:
        raise ValueError("DNB has no decisive probability mass")

    conditional_win = win / decisive
    break_even = 1.0 / price
    edge = conditional_win - break_even
    # Push returns the stake: only wins and losses contribute to net profit.
    expected_value = win * (price - 1.0) - loss
    # Full Kelly conditional on a decisive result; push outcomes do not change bankroll.
    kelly = max(0.0, (conditional_win * price - 1.0) / (price - 1.0))
    return {
        "conditional_win_probability": conditional_win,
        "push_probability": push,
        "conditional_loss_probability": loss / decisive,
        "break_even_probability": break_even,
        "edge_pp": edge * 100.0,
        "expected_value": expected_value,
        "expected_value_pct": expected_value * 100.0,
        "full_kelly_fraction": kelly,
        "passes_probability_edge": edge >= edge_threshold,
        "passes_ev": expected_value >= ev_threshold,
        "qualifies": edge >= edge_threshold and expected_value >= ev_threshold,
    }
