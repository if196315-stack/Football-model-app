"""Tests for cached recent-form context used by AI selection."""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update_data import form_adjustment, recent_team_form


def sample_matches():
    rows = [
        ("2025-01-01", "Home FC", "Rival A", 2, 0),
        ("2025-01-02", "Rival B", "Away FC", 0, 1),
        ("2025-01-03", "Home FC", "Rival C", 1, 1),
        ("2025-01-04", "Rival D", "Away FC", 2, 0),
        ("2025-01-05", "Home FC", "Rival E", 3, 1),
        ("2025-01-06", "Rival F", "Away FC", 1, 2),
        ("2025-01-07", "Home FC", "Rival G", 0, 1),
        # This future result must never leak into the 2025-01-08 forecast.
        ("2025-01-09", "Home FC", "Away FC", 0, 5),
    ]
    return pd.DataFrame(
        [
            {"comp": "eng", "date": pd.Timestamp(date), "home_team": home,
             "away_team": away, "home_score": hs, "away_score": aws}
            for date, home, away, hs, aws in rows
        ]
    )


def test_recent_form_excludes_future_and_limits_to_five():
    matches = sample_matches()
    form = recent_team_form(matches, "eng", "Home FC", "2025-01-08", venue="home")
    assert form["matches"] == 4
    assert form["form"] == "WDWL"
    assert form["pointsPerGame"] == 1.75
    assert form["venueMatches"] == 4
    assert form["goalsForAvg"] == 1.5


def test_form_adjustment_is_bounded_and_requires_sample():
    home = {"matches": 5, "pointsPerGame": 2.4, "venuePointsPerGame": 2.6,
            "goalsForAvg": 2.0, "goalsAgainstAvg": 0.8}
    away = {"matches": 5, "pointsPerGame": 0.8, "venuePointsPerGame": 0.6,
            "goalsForAvg": 0.7, "goalsAgainstAvg": 1.8}
    signal = {"side": "1X"}
    assert form_adjustment(signal, home, away) == 1.1
    assert form_adjustment(signal, {**home, "matches": 2}, away) == 0.0


def test_team_total_adjustment_uses_correct_team():
    home = {"matches": 5, "pointsPerGame": 2.0, "venuePointsPerGame": 2.0,
            "goalsForAvg": 2.4, "goalsAgainstAvg": 0.6}
    away = {"matches": 5, "pointsPerGame": 1.0, "venuePointsPerGame": 1.0,
            "goalsForAvg": 0.8, "goalsAgainstAvg": 1.6}
    assert form_adjustment(
        {"side": "ИТБ 1.5 · Home FC", "homeTeam": "Home FC", "awayTeam": "Away FC"},
        home, away,
    ) == 0.36
