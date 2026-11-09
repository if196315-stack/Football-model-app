#!/usr/bin/env python3
"""Seven-competition chronological xG backtest for supported goal markets.

Offline only. Same-calendar-day fixtures are scored before same-day results/xG
enter history. Baselines are expanding, Laplace-smoothed competition outcome
frequencies. Historical odds are absent, so this is probability-quality research,
not a betting-edge or ROI backtest.
"""
from __future__ import annotations
import argparse, csv, json, math
from collections import defaultdict
from pathlib import Path
from backtest_pitchapi import WINDOW, HALF_LIFE, SHRINKAGE, poisson, team_strength

MATCH_LINES = (1.5, 2.5, 3.5)
TEAM_LINES = (0.5, 1.5, 2.5, 3.5)
EXPECTED_COMPETITIONS = {"ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL"}


def domestic_rows(path):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            rows.append({
                "id": str(row["match_id"]), "date": str(row["date"])[:10],
                "league": str(row["league_code"]), "season": row.get("season", ""),
                "home": str(row["home_team"]), "away": str(row["away_team"]),
                "hg": int(row["home_goals"]), "ag": int(row["away_goals"]),
                "hx": float(row["home_xg"]), "ax": float(row["away_xg"]),
            })
    return rows


def uefa_rows(path):
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    out = []
    for row in payload.get("matches", []):
        if row.get("pairedXg") is not True:
            continue
        required = ("id", "homeScore", "awayScore", "homeXg", "awayXg",
                    "date", "leagueCode", "home", "away")
        if any(row.get(key) is None for key in required):
            continue
        out.append({
            "id": str(row["id"]), "date": str(row["date"])[:10],
            "league": str(row["leagueCode"]), "season": str(row.get("season") or ""),
            "home": str(row["home"]), "away": str(row["away"]),
            "hg": int(row["homeScore"]), "ag": int(row["awayScore"]),
            "hx": float(row["homeXg"]), "ax": float(row["awayXg"]),
        })
    return out


def market_outcomes(home_goals, away_goals):
    total = home_goals + away_goals
    result = {
        "1X2": "1" if home_goals > away_goals else "2" if home_goals < away_goals else "X",
        "DC_1X": int(home_goals >= away_goals),
        "DC_X2": int(away_goals >= home_goals),
        "BTTS": int(home_goals > 0 and away_goals > 0),
        "DNB_HOME": "W" if home_goals > away_goals else "L" if home_goals < away_goals else "P",
        "DNB_AWAY": "W" if away_goals > home_goals else "L" if away_goals < home_goals else "P",
    }
    for line in MATCH_LINES:
        result[f"TOTAL_OVER_{line}"] = int(total > line)
    for side, goals in (("HOME", home_goals), ("AWAY", away_goals)):
        for line in TEAM_LINES:
            result[f"{side}_OVER_{line}"] = int(goals > line)
    return result


def market_probs(home_lambda, away_lambda):
    home_pmf, away_pmf = poisson(home_lambda), poisson(away_lambda)
    joint = {(home, away): p_home * p_away
             for home, p_home in enumerate(home_pmf)
             for away, p_away in enumerate(away_pmf)}
    p_home = sum(p for (h, a), p in joint.items() if h > a)
    p_draw = sum(p for (h, a), p in joint.items() if h == a)
    p_away = max(0.0, 1.0 - p_home - p_draw)
    result = {
        "1X2": {"1": p_home, "X": p_draw, "2": p_away},
        "DC_1X": p_home + p_draw, "DC_X2": p_away + p_draw,
        "BTTS": (1 - home_pmf[0]) * (1 - away_pmf[0]),
        "DNB_HOME": {"W": p_home, "P": p_draw, "L": p_away},
        "DNB_AWAY": {"W": p_away, "P": p_draw, "L": p_home},
    }
    for line in MATCH_LINES:
        result[f"TOTAL_OVER_{line}"] = sum(p for (h, a), p in joint.items() if h + a > line)
    for side, pmf in (("HOME", home_pmf), ("AWAY", away_pmf)):
        for line in TEAM_LINES:
            result[f"{side}_OVER_{line}"] = sum(p for goals, p in enumerate(pmf) if goals > line)
    return result


def score(rows, market, source):
    classes = ("1", "X", "2") if market == "1X2" else (
        ("W", "P", "L") if market.startswith("DNB") else ("0", "1"))
    if not rows:
        return None
    brier = log_loss = correct = 0.0
    bins = {label: [] for label in classes}
    for row in rows:
        probabilities = row[source]
        actual = str(row["actual"])
        if market != "1X2" and not market.startswith("DNB"):
            probability_yes = float(probabilities)
            probabilities = {"0": 1.0 - probability_yes, "1": probability_yes}
        brier += sum((float(probabilities[label]) - (1.0 if label == actual else 0.0)) ** 2
                     for label in classes)
        log_loss -= math.log(max(1e-15, float(probabilities[actual])))
        correct += int(max(classes, key=lambda label: probabilities[label]) == actual)
        for label in classes:
            bins[label].append((float(probabilities[label]), int(actual == label)))
    ece = {}
    for label, values in bins.items():
        weighted_gap = 0.0
        for index in range(10):
            bucket = [value for value in values
                      if index / 10 <= value[0] < (index + 1) / 10
                      or (index == 9 and value[0] == 1.0)]
            if bucket:
                mean_p = sum(p for p, _ in bucket) / len(bucket)
                observed = sum(y for _, y in bucket) / len(bucket)
                weighted_gap += len(bucket) * abs(mean_p - observed)
        ece[label] = weighted_gap / len(rows)
    return {"n": len(rows), "brier": brier / len(rows),
            "log_loss": log_loss / len(rows), "accuracy": correct / len(rows), "ece": ece}


def compare(model, baseline):
    if model is None or baseline is None:
        return None
    return {
        "model": model, "baseline": baseline,
        "brier_skill": 1.0 - model["brier"] / baseline["brier"] if baseline["brier"] else None,
        "log_loss_skill": 1.0 - model["log_loss"] / baseline["log_loss"] if baseline["log_loss"] else None,
        "accuracy_delta": model["accuracy"] - baseline["accuracy"],
    }


def run(rows, holdout):
    rows = sorted(rows, key=lambda row: (row["date"], row["league"], row["id"]))
    histories = defaultdict(list)
    league_xg = defaultdict(list)
    counts = defaultdict(lambda: defaultdict(int))
    by_date = defaultdict(list)
    for row in rows:
        by_date[row["date"]].append(row)

    predictions = []
    markets = ["1X2", "DC_1X", "DC_X2", "BTTS",
               *(f"TOTAL_OVER_{line}" for line in MATCH_LINES),
               *(f"{side}_OVER_{line}" for side in ("HOME", "AWAY") for line in TEAM_LINES),
               "DNB_HOME", "DNB_AWAY"]
    for date in sorted(by_date):
        today = by_date[date]
        base = {}
        for league in {match["league"] for match in today}:
            for side in ("home", "away"):
                values = [item[side + "_xg"] for item in league_xg[league]
                          if item["side"] == side]
                if values:
                    base[(league, side)] = sum(values) / len(values)
        batch = []
        for match in today:
            league = match["league"]
            home_strength = team_strength(histories[(league, match["home"])], league, base)
            away_strength = team_strength(histories[(league, match["away"])], league, base)
            home_base, away_base = base.get((league, "home")), base.get((league, "away"))
            if home_strength is None or away_strength is None or not home_base or not away_base:
                continue
            home_lambda = max(.05, min(4.5, home_base * home_strength["attack"] * away_strength["defence"]))
            away_lambda = max(.05, min(4.5, away_base * away_strength["attack"] * home_strength["defence"]))
            model_probabilities = market_probs(home_lambda, away_lambda)
            actual = market_outcomes(match["hg"], match["ag"])
            for market in markets:
                classes = ("1", "X", "2") if market == "1X2" else (
                    ("W", "P", "L") if market.startswith("DNB") else ("0", "1"))
                prior = counts[league][market]
                baseline_probabilities = {
                    label: (prior[label] + 1) / (sum(prior.values()) + len(classes))
                    for label in classes
                }
                batch.append({
                    "league": league, "season": match["season"], "date": date,
                    "match_id": match["id"], "market": market, "actual": actual[market],
                    "model": model_probabilities[market], "baseline": baseline_probabilities,
                })
        predictions.extend(batch)

        # No result or xG from this calendar date can affect another match on that date.
        for match in today:
            league = match["league"]
            actual = market_outcomes(match["hg"], match["ag"])
            for market in markets:
                counts[league][market][str(actual[market])] += 1
            histories[(league, match["home"])].append(
                {"side": "home", "xg": match["hx"], "xga": match["ax"]})
            histories[(league, match["away"])].append(
                {"side": "away", "xg": match["ax"], "xga": match["hx"]})
            league_xg[league].append({"side": "home", "home_xg": match["hx"], "away_xg": match["ax"]})
            league_xg[league].append({"side": "away", "home_xg": match["hx"], "away_xg": match["ax"]})

    result = {
        "method": "calendar_date_walk_forward_independent_poisson",
        "holdout_from": holdout,
        "input_matches": len(rows),
        "input_matches_by_competition": {
            league: sum(1 for row in rows if row["league"] == league)
            for league in sorted({row["league"] for row in rows})
        },
        "predicted_matches_by_competition": {
            league: len({row["match_id"] for row in predictions if row["league"] == league})
            for league in sorted({row["league"] for row in rows})
        },
        "competitions": sorted({row["league"] for row in rows}),
        "markets": {},
        "baseline_definition": "Expanding historical frequency within each competition, Laplace +1 smoothing; only results before the fixture date are used.",
        "model_definition": f"Independent Poisson using prior paired-xG team history; window={WINDOW}, half_life={HALF_LIFE}, shrinkage={SHRINKAGE}; same-calendar-day leakage prevented.",
        "limitations": [
            "No historical odds are present: edge, EV, bookmaker comparison and ROI are not measured.",
            "This research backtest is not a claim that the Android production candidate engine uses identical probabilities.",
            "BTTS NO is the complement of BTTS YES and is not reported as a separate row.",
            "DNB metrics include push as a third outcome class."
        ],
    }
    for market in markets:
        items = [row for row in predictions if row["market"] == market]
        train = [row for row in items if row["date"] < holdout]
        test = [row for row in items if row["date"] >= holdout]

        def grouped(subset):
            output = {}
            for league in sorted({row["league"] for row in subset}):
                group = [row for row in subset if row["league"] == league]
                output[league] = compare(score(group, market, "model"),
                                         score(group, market, "baseline"))
            return output

        result["markets"][market] = {
            "all": compare(score(items, market, "model"), score(items, market, "baseline")),
            "all_by_competition": grouped(items),
            "holdout": compare(score(test, market, "model"), score(test, market, "baseline")),
            "holdout_by_competition": grouped(test),
            "train_n": len(train),
        }
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--domestic", required=True)
    parser.add_argument("--uefa", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--holdout-from", default="2026-07-01")
    args = parser.parse_args()
    rows = domestic_rows(args.domestic) + uefa_rows(args.uefa)
    ids = [row["id"] for row in rows]
    if len(ids) != len(set(ids)):
        raise SystemExit("Duplicate match IDs across domestic and UEFA archives")
    found = {row["league"] for row in rows}
    missing = EXPECTED_COMPETITIONS - found
    unexpected = found - EXPECTED_COMPETITIONS
    if missing or unexpected:
        raise SystemExit(f"Archive must cover exactly seven competitions; missing={sorted(missing)}, unexpected={sorted(unexpected)}")
    report = run(rows, args.holdout_from)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "input_matches": report["input_matches"],
        "input_matches_by_competition": report["input_matches_by_competition"],
        "predicted_matches_by_competition": report["predicted_matches_by_competition"],
        "market_holdout_n": {
            market: metrics["holdout"]["model"]["n"] if metrics["holdout"] else 0
            for market, metrics in report["markets"].items()
        },
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
