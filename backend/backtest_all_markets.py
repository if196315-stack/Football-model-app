#!/usr/bin/env python3
"""Seven-competition chronological xG backtest for supported goal markets.

Offline only. Same-calendar-day fixtures are scored before same-day results/xG
enter history. Baselines are expanding, Laplace-smoothed competition outcome
frequencies. Historical odds are absent, so this is probability-quality research,
not a betting-edge or ROI backtest.
"""
from __future__ import annotations
import argparse, csv, json, math
import numpy as np
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
        if market != "1X2" and not market.startswith("DNB") and not isinstance(probabilities, dict):
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



def fit_vector_scaling(train_rows, market, min_n=100):
    """Fit regularized vector scaling on prior walk-forward predictions only."""
    classes = ("1", "X", "2") if market == "1X2" else (
        ("W", "P", "L") if market.startswith("DNB") else ("0", "1"))
    if len(train_rows) < min_n:
        return {"available": False, "reason": "insufficient_training_rows",
                "train_n": len(train_rows), "classes": list(classes)}
    matrix, labels = [], []
    for row in train_rows:
        raw = row["model"]
        probs = raw if isinstance(raw, dict) else {"0": 1.0 - float(raw), "1": float(raw)}
        matrix.append([math.log(max(1e-8, min(1.0, float(probs[label]))))
                       for label in classes])
        labels.append(classes.index(str(row["actual"])))
    x = np.asarray(matrix, dtype=float)
    y = np.asarray(labels, dtype=int)
    bias = np.zeros(len(classes), dtype=float)
    log_temperature = 0.0
    learning_rate = 0.04
    one_hot = np.eye(len(classes), dtype=float)[y]
    # Multinomial vector scaling: log(p)/T + class bias. L2 regularization
    # keeps small-sample class offsets from becoming extreme.
    for _ in range(250):
        inv_t = math.exp(-log_temperature)
        z = x * inv_t + bias
        z -= np.max(z, axis=1, keepdims=True)
        exp_z = np.exp(np.clip(z, -60.0, 0.0))
        q = exp_z / np.sum(exp_z, axis=1, keepdims=True)
        error = q - one_hot
        grad_bias = np.mean(error, axis=0) + 0.01 * bias
        grad_log_t = float(np.mean(np.sum(error * (-x * inv_t), axis=1))
                           + 0.01 * log_temperature)
        bias -= learning_rate * grad_bias
        log_temperature = max(-2.0, min(2.0, log_temperature - learning_rate * grad_log_t))
    return {"available": True, "train_n": len(train_rows),
            "classes": list(classes), "temperature": math.exp(log_temperature),
            "bias": {label: float(bias[i]) for i, label in enumerate(classes)}}

def apply_vector_scaling(row, params):
    raw = row["model"]
    classes = params["classes"]
    probs = raw if isinstance(raw, dict) else {"0": 1.0 - float(raw), "1": float(raw)}
    temperature = params["temperature"]
    logits = [math.log(max(1e-8, min(1.0, float(probs[label])))) / temperature
              + params["bias"][label] for label in classes]
    peak = max(logits)
    exp_z = [math.exp(max(-60.0, value - peak)) for value in logits]
    total = sum(exp_z)
    calibrated = {label: exp_z[i] / total for i, label in enumerate(classes)}
    result = dict(row)
    result["calibrated"] = calibrated if market_is_multiclass(row["market"]) else calibrated["1"]
    return result


def market_is_multiclass(market):
    return market == "1X2" or market.startswith("DNB")


def calibration_comparison(train_rows, test_rows, market):
    params = fit_vector_scaling(train_rows, market)
    if not params["available"] or not test_rows:
        return {"calibrator": params, "holdout": None, "brier_delta_vs_raw": None,
                "log_loss_delta_vs_raw": None}
    calibrated_rows = [apply_vector_scaling(row, params) for row in test_rows]
    raw_metrics = score(test_rows, market, "model")
    calibrated_metrics = score(calibrated_rows, market, "calibrated")
    return {
        "calibrator": params,
        "holdout": calibrated_metrics,
        "raw_holdout": raw_metrics,
        "brier_delta_vs_raw": raw_metrics["brier"] - calibrated_metrics["brier"],
        "log_loss_delta_vs_raw": raw_metrics["log_loss"] - calibrated_metrics["log_loss"],
        "accuracy_delta_vs_raw": calibrated_metrics["accuracy"] - raw_metrics["accuracy"],
    }

def run(rows, holdout):
    rows = sorted(rows, key=lambda row: (row["date"], row["league"], row["id"]))
    histories = defaultdict(list)
    league_xg = defaultdict(list)
    counts = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
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
        "independent_market_count": 15,
        "market_relationships": {
            "DNB_HOME": "Same W/P/L event partition as 1X2; calibration is a relabelled duplicate, not independent evidence.",
            "DNB_AWAY": "Same W/P/L event partition as 1X2; calibration is a relabelled duplicate, not independent evidence.",
        },
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

        # One calibrator is trained globally and one per competition. Every
        # calibrator sees only pre-holdout predictions/outcomes.
        global_calibration = calibration_comparison(train, test, market)
        per_competition_calibration = {}
        for league in sorted({row["league"] for row in items}):
            league_train = [row for row in train if row["league"] == league]
            league_test = [row for row in test if row["league"] == league]
            per_competition_calibration[league] = calibration_comparison(
                league_train, league_test, market)

        result["markets"][market] = {
            "all": compare(score(items, market, "model"), score(items, market, "baseline")),
            "all_by_competition": grouped(items),
            "holdout": compare(score(test, market, "model"), score(test, market, "baseline")),
            "holdout_by_competition": grouped(test),
            "train_n": len(train),
            "calibration_global": global_calibration,
            "calibration_by_competition": per_competition_calibration,
        }
    result["calibration_method"] = (
        "Regularized multinomial vector scaling (log probabilities / temperature + class bias), "
        "fit only on pre-holdout chronological walk-forward predictions; evaluated once on holdout. "
        "Per-market global and per-competition calibrators are reported. This is an evaluation, "
        "not a production strategy change."
    )
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
    calibration_global = {}
    calibration_by_competition = {}
    for market, metrics in report["markets"].items():
        cal = metrics["calibration_global"]
        holdout_metrics = cal.get("holdout")
        raw_metrics = cal.get("raw_holdout")
        calibration_global[market] = {
            "train_n": cal["calibrator"]["train_n"],
            "holdout_n": holdout_metrics["n"] if holdout_metrics else 0,
            "raw_brier": raw_metrics["brier"] if raw_metrics else None,
            "calibrated_brier": holdout_metrics["brier"] if holdout_metrics else None,
            "brier_delta_vs_raw": cal["brier_delta_vs_raw"],
            "raw_log_loss": raw_metrics["log_loss"] if raw_metrics else None,
            "calibrated_log_loss": holdout_metrics["log_loss"] if holdout_metrics else None,
            "log_loss_delta_vs_raw": cal["log_loss_delta_vs_raw"],
        }
        # DNB_HOME/AWAY reuse the same W/P/L partition as 1X2; exclude them
        # from competition-level aggregate improvement counts to avoid double weight.
        if market in ("DNB_HOME", "DNB_AWAY"):
            continue
        for league, league_cal in metrics["calibration_by_competition"].items():
            target = calibration_by_competition.setdefault(league, {"markets_tested": 0, "markets_brier_improved": 0,
                                                                       "brier_delta_sum": 0.0, "log_loss_delta_sum": 0.0})
            if league_cal.get("holdout"):
                target["markets_tested"] += 1
                delta_b = league_cal.get("brier_delta_vs_raw")
                delta_l = league_cal.get("log_loss_delta_vs_raw")
                if delta_b is not None:
                    target["brier_delta_sum"] += delta_b
                    target["markets_brier_improved"] += int(delta_b > 0)
                if delta_l is not None:
                    target["log_loss_delta_sum"] += delta_l
    for league, values in calibration_by_competition.items():
        n = values.pop("markets_tested")
        values["markets_tested"] = n
        values["mean_brier_delta_vs_raw"] = values.pop("brier_delta_sum") / n if n else None
        values["mean_log_loss_delta_vs_raw"] = values.pop("log_loss_delta_sum") / n if n else None

    print(json.dumps({
        "input_matches": report["input_matches"],
        "input_matches_by_competition": report["input_matches_by_competition"],
        "independent_market_count": report["independent_market_count"],
        "market_relationships": report["market_relationships"],
        "predicted_matches_by_competition": report["predicted_matches_by_competition"],
        "market_holdout_n": {
            market: metrics["holdout"]["model"]["n"] if metrics["holdout"] else 0
            for market, metrics in report["markets"].items()
        },
        "calibration_global": calibration_global,
        "calibration_by_competition": calibration_by_competition,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
