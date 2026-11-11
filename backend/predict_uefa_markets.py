#!/usr/bin/env python3
"""Create strict-date walk-forward Poisson market probabilities for upcoming UEFA fixtures."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from backtest_pitchapi import WINDOW, probabilities, poisson, team_strength

MODEL_VERSION = "uefa-xg-poisson-v1"
MATCH_TOTAL_LINES = (1.5, 2.5, 3.5)
TEAM_TOTAL_LINES = (0.5, 1.5, 2.5, 3.5)
SUPPORTED_LEAGUES = {"UCL", "UEL", "UECL"}


def _state_before(history_rows: list[dict], cutoff_date: str) -> tuple[dict, dict, dict, str | None]:
    histories = defaultdict(list)
    league_xg = defaultdict(list)
    selected = [
        row for row in history_rows
        if row.get("pairedXg") is True and str(row.get("date") or "")[:10] < cutoff_date
    ]
    selected.sort(key=lambda row: (str(row.get("date") or ""), str(row.get("leagueCode") or ""), str(row.get("id") or "")))
    for row in selected:
        league = str(row.get("leagueCode") or "")
        home, away = row.get("home"), row.get("away")
        hx, ax = row.get("homeXg"), row.get("awayXg")
        if not league or not home or not away or hx is None or ax is None:
            continue
        histories[(league, home)].append({"side": "home", "xg": float(hx), "xga": float(ax)})
        histories[(league, away)].append({"side": "away", "xg": float(ax), "xga": float(hx)})
        league_xg[league].append({"side": "home", "home_xg": float(hx), "away_xg": float(ax)})
        league_xg[league].append({"side": "away", "home_xg": float(hx), "away_xg": float(ax)})

    base = {}
    leagues = {league for league, _team in histories}
    for league in leagues:
        for side in ("home", "away"):
            values = [
                row[side + "_xg"] for row in league_xg[league]
                if row["side"] == side
            ]
            if values:
                base[(league, side)] = sum(values) / len(values)
    history_max_date_by_league = {}
    for row in selected:
        league = str(row.get("leagueCode") or "")
        date = str(row.get("date") or "")[:10]
        if league and date:
            history_max_date_by_league[league] = max(
                date, history_max_date_by_league.get(league, date)
            )
    return histories, base, league_xg, history_max_date_by_league


def _market_probabilities(home_lambda: float, away_lambda: float) -> dict:
    home_pmf, away_pmf = poisson(home_lambda), poisson(away_lambda)
    outcome = probabilities(home_lambda, away_lambda)
    total_pmf = defaultdict(float)
    for home_goals, p_home in enumerate(home_pmf):
        for away_goals, p_away in enumerate(away_pmf):
            total_pmf[home_goals + away_goals] += p_home * p_away

    match_totals = {"over": {}, "under": {}}
    for line in MATCH_TOTAL_LINES:
        over = sum(prob for goals, prob in total_pmf.items() if goals > line)
        match_totals["over"][str(line)] = over
        match_totals["under"][str(line)] = 1.0 - over

    team_totals = {}
    for team, pmf in (("home", home_pmf), ("away", away_pmf)):
        team_totals[team] = {"over": {}, "under": {}}
        for line in TEAM_TOTAL_LINES:
            over = sum(prob for goals, prob in enumerate(pmf) if goals > line)
            team_totals[team]["over"][str(line)] = over
            team_totals[team]["under"][str(line)] = 1.0 - over

    btts_yes = (1.0 - home_pmf[0]) * (1.0 - away_pmf[0])
    return {
        "double_chance": {
            "1x": outcome["1"] + outcome["x"],
            "x2": outcome["x"] + outcome["2"],
            "12": outcome["1"] + outcome["2"],
        },
        "match_total": match_totals,
        "team_total": team_totals,
        "btts": {"yes": btts_yes, "no": 1.0 - btts_yes},
        "handicap_zero": {
            "home": {"win": outcome["1"], "push": outcome["x"], "lose": outcome["2"]},
            "away": {"win": outcome["2"], "push": outcome["x"], "lose": outcome["1"]},
        },
    }


def predict_upcoming(history_payload: dict, fixture_cache: dict,
                     predicted_at: str | None = None) -> dict[str, dict]:
    history_rows = history_payload.get("matches") or []
    upcoming = [
        row for row in (fixture_cache.get("matches") or {}).values()
        if row.get("status") in ("not_started", "scheduled", "timed")
        and row.get("id") is not None and row.get("date")
    ]
    predictions = {}
    by_date = defaultdict(list)
    for row in upcoming:
        by_date[str(row["date"])[:10]].append(row)

    predicted_at = predicted_at or datetime.now(timezone.utc).isoformat()
    for match_date, fixtures in sorted(by_date.items()):
        histories, base, _league_xg, history_max_date_by_league = _state_before(history_rows, match_date)
        for fixture in fixtures:
            league = str(fixture.get("leagueCode") or "")
            home, away = fixture.get("home"), fixture.get("away")
            if league not in SUPPORTED_LEAGUES or not home or not away:
                continue
            home_history = histories[(league, home)]
            away_history = histories[(league, away)]
            home_strength = team_strength(home_history, league, base)
            away_strength = team_strength(away_history, league, base)
            home_base, away_base = base.get((league, "home")), base.get((league, "away"))
            if home_strength is None or away_strength is None or not home_base or not away_base:
                continue
            home_lambda = max(0.05, min(4.5, home_base * home_strength["attack"] * away_strength["defence"]))
            away_lambda = max(0.05, min(4.5, away_base * away_strength["attack"] * home_strength["defence"]))
            predictions[str(fixture["id"])] = {
                "fixture_id": str(fixture["id"]),
                "league_code": league,
                "season": fixture.get("season"),
                "kickoff_date": match_date,
                "predicted_at": predicted_at,
                "model_version": MODEL_VERSION,
                "history_max_date": history_max_date_by_league.get(league),
                "home_history_matches": len(home_history[-WINDOW:]),
                "away_history_matches": len(away_history[-WINDOW:]),
                "home_lambda": round(home_lambda, 6),
                "away_lambda": round(away_lambda, 6),
                "markets": _market_probabilities(home_lambda, away_lambda),
            }
    return predictions


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", default="artifacts/uefa-xg-history.json")
    parser.add_argument("--fixture-cache", default="backend/cache/pitchapi_uefa_fixtures.json")
    parser.add_argument("--output", default="artifacts/uefa-upcoming-model-predictions.json")
    args = parser.parse_args()
    history = json.loads(Path(args.history).read_text(encoding="utf-8"))
    fixtures = json.loads(Path(args.fixture_cache).read_text(encoding="utf-8"))
    predictions = predict_upcoming(history, fixtures)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps({
        "model_version": MODEL_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "predicted_fixtures": len(predictions),
        "predictions": predictions,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"model_version": MODEL_VERSION, "predicted_fixtures": len(predictions)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
