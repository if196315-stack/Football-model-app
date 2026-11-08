#!/usr/bin/env python3
"""Reproducible date-ordered backtest for the PitchAPI clean-candidate CSV.

No network calls and no production writes. A match's xG enters team history only
after every fixture on that calendar date has been predicted.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path


WINDOW = 30
HALF_LIFE = 12.0
SHRINKAGE = 3.0
CLASSES = ("1", "x", "2")


def read_matches(path: str | Path) -> list[dict]:
    rows = []
    seen = set()
    with open(path, newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            match_id = str(row.get("match_id") or "").strip()
            date = str(row.get("date") or "")[:10]
            league = str(row.get("league_code") or "").strip()
            home, away = str(row.get("home_team") or "").strip(), str(row.get("away_team") or "").strip()
            if not match_id or not date or not league or not home or not away:
                continue
            if match_id in seen:
                raise ValueError(f"Duplicate match_id: {match_id}")
            seen.add(match_id)
            try:
                hg, ag = int(row["home_goals"]), int(row["away_goals"])
            except (KeyError, TypeError, ValueError):
                continue
            def number(value):
                try:
                    result = float(value)
                    return result if math.isfinite(result) and result >= 0 else None
                except (TypeError, ValueError):
                    return None
            hx, ax = number(row.get("home_xg")), number(row.get("away_xg"))
            rows.append({
                "match_id": match_id, "date": date, "league": league,
                "season": str(row.get("season") or ""),
                "home": home, "away": away, "hg": hg, "ag": ag,
                "hx": hx, "ax": ax,
                "paired_xg": hx is not None and ax is not None,
            })
    return sorted(rows, key=lambda r: (r["date"], r["league"], r["match_id"]))


def poisson(lam: float) -> list[float]:
    values = [math.exp(-lam)]
    for score in range(1, 13):
        values.append(values[-1] * lam / score)
    values[-1] += max(0.0, 1.0 - sum(values))
    return values


def probabilities(home_lambda: float, away_lambda: float) -> dict[str, float]:
    hp, ap = poisson(home_lambda), poisson(away_lambda)
    result = {"1": 0.0, "x": 0.0, "2": 0.0}
    for home_goals, p_home in enumerate(hp):
        for away_goals, p_away in enumerate(ap):
            key = "1" if home_goals > away_goals else "2" if home_goals < away_goals else "x"
            result[key] += p_home * p_away
    total = sum(result.values())
    return {key: value / total for key, value in result.items()}


def team_strength(records: list[dict], league: str, base: dict) -> dict | None:
    history = records[-WINDOW:]
    if not history:
        return None
    attack = defence = weight_sum = 0.0
    for index, row in enumerate(history):
        own = base.get((league, row["side"]))
        opponent = base.get((league, "away" if row["side"] == "home" else "home"))
        if not own or not opponent:
            continue
        weight = 0.5 ** ((len(history) - 1 - index) / HALF_LIFE)
        attack += weight * row["xg"] / own
        defence += weight * row["xga"] / opponent
        weight_sum += weight
    if not weight_sum:
        return None
    return {
        "attack": (attack + SHRINKAGE) / (weight_sum + SHRINKAGE),
        "defence": (defence + SHRINKAGE) / (weight_sum + SHRINKAGE),
    }


def score_metrics(items: list[dict], prefix: str) -> dict | None:
    if not items:
        return None
    brier = logloss = correct = 0.0
    for item in items:
        probs = item[prefix]
        actual = item["actual"]
        brier += sum((probs[key] - (1.0 if key == actual else 0.0)) ** 2 for key in CLASSES)
        logloss -= math.log(max(1e-15, probs[actual]))
        correct += int(max(CLASSES, key=lambda key: probs[key]) == actual)
    n = len(items)
    return {"n": n, "brier": brier / n, "log_loss": logloss / n, "accuracy": correct / n}


def walk_forward(matches: list[dict], holdout_from: str = "2024-07-01", include_predictions: bool = False) -> dict:
    # Histories contain only paired, non-null PitchAPI shot-level xG.
    histories = defaultdict(list)
    league_totals = defaultdict(lambda: {"1": 0, "x": 0, "2": 0, "n": 0})
    league_xg = defaultdict(list)
    predictions = []
    by_date = defaultdict(list)
    for match in matches:
        by_date[match["date"]].append(match)

    for date in sorted(by_date):
        todays = by_date[date]
        # Freeze all priors for this date before scoring any match on this date.
        base = {}
        for league in {m["league"] for m in todays}:
            for side in ("home", "away"):
                vals = [r[side + "_xg"] for r in league_xg[league] if r["side"] == side]
                if vals:
                    base[(league, side)] = sum(vals) / len(vals)

        todays_predictions = []
        for match in todays:
            league = match["league"]
            totals = league_totals[league]
            if totals["n"] == 0:
                continue
            baseline = {key: (totals[key] + 1.0) / (totals["n"] + 3.0) for key in CLASSES}
            home_strength = team_strength(histories[(league, match["home"])], league, base)
            away_strength = team_strength(histories[(league, match["away"])], league, base)
            if home_strength is None or away_strength is None:
                continue
            home_base, away_base = base.get((league, "home")), base.get((league, "away"))
            if not home_base or not away_base:
                continue
            home_def = team_strength(histories[(league, match["home"])], league, "home", base)
            away_def = team_strength(histories[(league, match["away"])], league, "away", base)
            home_lambda = max(0.05, min(4.5, home_base * home_strength["attack"] * away_def["defence"]))
            away_lambda = max(0.05, min(4.5, away_base * away_strength["attack"] * home_def["defence"]))
            model = probabilities(home_lambda, away_lambda)
            actual = "1" if match["hg"] > match["ag"] else "2" if match["hg"] < match["ag"] else "x"
            todays_predictions.append({
                "match_id": match["match_id"], "league": league, "season": match["season"], "date": date,
                "actual": actual, "home_lambda": home_lambda, "away_lambda": away_lambda,
                "model": model, "baseline": baseline,
            })

        predictions.extend(todays_predictions)

        # Only after all same-date predictions are frozen do outcomes/xG update priors.
        for match in todays:
            league = match["league"]
            outcome = "1" if match["hg"] > match["ag"] else "2" if match["hg"] < match["ag"] else "x"
            league_totals[league][outcome] += 1
            league_totals[league]["n"] += 1
            if match["paired_xg"]:
                histories[(league, match["home"])].append({"side": "home", "xg": match["hx"], "xga": match["ax"]})
                histories[(league, match["away"])].append({"side": "away", "xg": match["ax"], "xga": match["hx"]})
                league_xg[league].append({"side": "home", "home_xg": match["hx"], "away_xg": match["ax"]})
                league_xg[league].append({"side": "away", "home_xg": match["hx"], "away_xg": match["ax"]})

    holdout = [p for p in predictions if p["date"] >= holdout_from]
    groups = defaultdict(list)
    for item in predictions:
        groups[(item["league"], item["season"])].append(item)
    season_metrics = {}
    for (league, season), items in sorted(groups.items()):
        season_metrics[f"{league} {season}"] = {
            "model": score_metrics(items, "model"),
            "baseline": score_metrics(items, "baseline"),
        }
    return {
        "method": "strict_calendar_date_walk_forward",
        "input_matches": len(matches),
        "predicted_matches": len(predictions),
        "not_predicted": len(matches) - len(predictions),
        "paired_xg_matches": sum(m["paired_xg"] for m in matches),
        "model": score_metrics(predictions, "model"),
        "baseline": score_metrics(predictions, "baseline"),
        "holdout_from": holdout_from,
        "holdout": {
            "model": score_metrics(holdout, "model"),
            "baseline": score_metrics(holdout, "baseline"),
        },
        "by_league_season": season_metrics,
        "model_config": {"window": WINDOW, "half_life": HALF_LIFE, "shrinkage": SHRINKAGE,
                         "distribution": "independent_poisson", "goal_grid": "0..12"},
        "xg_policy": "Only paired non-null PitchAPI shot-level xG updates team history; missing xG is never imputed.",
        **({"predictions": predictions} if include_predictions else {}),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="pitchapi-clean-candidate.csv")
    parser.add_argument("--output", default="pitchapi-backtest-report.json")
    parser.add_argument("--holdout-from", default="2024-07-01")
    args = parser.parse_args()
    matches = read_matches(args.input)
    if not matches:
        raise SystemExit("No valid scored matches found in input CSV")
    report = walk_forward(matches, args.holdout_from)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("input_matches", "predicted_matches", "not_predicted", "model", "baseline", "holdout")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
