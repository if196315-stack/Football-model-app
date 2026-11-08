"""Leakage-safe Soccer365-only walk-forward evaluation prototype.

No production integration, bookmaker odds, value, or ROI claims are made here.
"""
from __future__ import annotations

import math
import re
import unicodedata
from collections import defaultdict
from datetime import datetime


def _timestamp(value):
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.timestamp()


def _team_key(row, side):
    team_id = row.get(f"{side}TeamId")
    if team_id is not None and str(team_id).strip():
        return "id:" + str(team_id).strip()
    name = row.get(side)
    if not isinstance(name, str) or not name.strip():
        return None
    value = unicodedata.normalize("NFKD", name.casefold())
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    value = re.sub(r"[^a-z0-9]+", " ", value)
    value = " ".join(value.split())
    return "name:" + value if value else None


def _paired_xg(row):
    stats = row.get("statistics")
    if not isinstance(stats, dict):
        return None
    home, away = stats.get("homeXg"), stats.get("awayXg")
    for value in (home, away):
        if (not isinstance(value, (int, float)) or isinstance(value, bool)
                or not math.isfinite(value) or not 0 <= value <= 20):
            return None
    return float(home), float(away)


def _poisson(lam, limit=10):
    values = [math.exp(-lam)]
    for goals in range(1, limit + 1):
        values.append(values[-1] * lam / goals)
    # Fold the tiny unmodelled tail into the last bucket.
    values[-1] += max(0.0, 1.0 - sum(values))
    return values


def _result_probabilities(home_lambda, away_lambda):
    home, away = _poisson(home_lambda), _poisson(away_lambda)
    probabilities = [0.0, 0.0, 0.0]
    for h, hp in enumerate(home):
        for a, ap in enumerate(away):
            probabilities[0 if h > a else 1 if h == a else 2] += hp * ap
    total = sum(probabilities)
    return [p / total for p in probabilities]


def walk_forward_backtest(records, *, min_team_matches=5, max_team_matches=30):
    """Forecast finished scored fixtures using only earlier paired-xG matches.

    All matches with an identical UTC kickoff are forecast before any of them
    are added to history. History is isolated by competition, type, and season.
    """
    if min_team_matches < 1 or max_team_matches < min_team_matches:
        raise ValueError("Invalid team history thresholds")

    prepared, seen_ids = [], set()
    for row in records:
        if not isinstance(row, dict):
            continue
        raw_id = row.get("id")
        match_id = str(raw_id).strip() if raw_id is not None else ""
        if not match_id or match_id in seen_ids:
            continue
        seen_ids.add(match_id)

        timestamp = _timestamp(row.get("utcDate"))
        competition = row.get("competitionId")
        competition_type = row.get("competitionType")
        season = row.get("season")
        home_key, away_key = _team_key(row, "home"), _team_key(row, "away")
        if (timestamp is None or competition in (None, "") or
                competition_type in (None, "") or season in (None, "") or
                not home_key or not away_key or home_key == away_key):
            continue
        prepared.append((
            timestamp, match_id, row,
            (str(competition), str(competition_type), str(season)),
            home_key, away_key,
        ))

    prepared.sort(key=lambda item: (item[0], item[1]))
    history = defaultdict(lambda: defaultdict(list))
    evaluated = []
    cursor = 0

    while cursor < len(prepared):
        timestamp = prepared[cursor][0]
        end = cursor
        while end < len(prepared) and prepared[end][0] == timestamp:
            end += 1
        batch = prepared[cursor:end]

        for _, match_id, row, group, home_key, away_key in batch:
            home_score, away_score = row.get("homeScore"), row.get("awayScore")
            if (row.get("status") != "FINISHED" or
                    not isinstance(home_score, int) or isinstance(home_score, bool) or home_score < 0 or
                    not isinstance(away_score, int) or isinstance(away_score, bool) or away_score < 0):
                continue
            teams = history[group]
            home_history = teams.get(home_key, [])[-max_team_matches:]
            away_history = teams.get(away_key, [])[-max_team_matches:]
            if min(len(home_history), len(away_history)) < min_team_matches:
                continue

            home_for = sum(item[0] for item in home_history) / len(home_history)
            home_against = sum(item[1] for item in home_history) / len(home_history)
            away_for = sum(item[0] for item in away_history) / len(away_history)
            away_against = sum(item[1] for item in away_history) / len(away_history)
            home_lambda = max(0.05, min(4.5, (home_for + away_against) / 2))
            away_lambda = max(0.05, min(4.5, (away_for + home_against) / 2))
            probs = _result_probabilities(home_lambda, away_lambda)
            actual = 0 if home_score > away_score else 1 if home_score == away_score else 2
            brier = sum((probs[i] - (1.0 if i == actual else 0.0)) ** 2 for i in range(3))
            evaluated.append({
                "id": match_id,
                "competitionId": group[0],
                "competitionType": group[1],
                "season": group[2],
                "utcDate": row.get("utcDate"),
                "home": row.get("home"),
                "away": row.get("away"),
                "homeXgForecast": home_lambda,
                "awayXgForecast": away_lambda,
                "probabilities": {"home": probs[0], "draw": probs[1], "away": probs[2]},
                "actual": ("home" if actual == 0 else "draw" if actual == 1 else "away"),
                "brierScore": brier,
                "priorHomeMatches": len(home_history),
                "priorAwayMatches": len(away_history),
            })

        # Only valid paired-xG finished games train the model, after the batch.
        for _, _, row, group, home_key, away_key in batch:
            home_score, away_score = row.get("homeScore"), row.get("awayScore")
            xg = _paired_xg(row)
            if (row.get("status") != "FINISHED" or xg is None or
                    not isinstance(home_score, int) or isinstance(home_score, bool) or home_score < 0 or
                    not isinstance(away_score, int) or isinstance(away_score, bool) or away_score < 0):
                continue
            home_xg, away_xg = xg
            history[group][home_key].append((home_xg, away_xg))
            history[group][away_key].append((away_xg, home_xg))
        cursor = end

    scores = [item["brierScore"] for item in evaluated]
    return {
        "source": "Soccer365",
        "mode": "chronological_walk_forward",
        "syntheticData": False,
        "evaluatedMatches": len(evaluated),
        "meanBrierScore": sum(scores) / len(scores) if scores else None,
        "matches": evaluated,
        "limitations": [
            "Prototype rolling-xG model, not the production Poisson model",
            "No bookmaker odds, value, profitability, or ROI evaluation",
            "Only earlier finished matches with paired xG enter team history",
            "Competition, competition type, and season are isolated",
            "Matches sharing a kickoff timestamp cannot train one another",
        ],
    }
