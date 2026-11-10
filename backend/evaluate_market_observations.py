#!/usr/bin/env python3
"""Evaluate every archived domestic market quote once the fixture result is known.

Each odds snapshot is an independent observation at its captured price. The
append-only result archive makes historical evaluation reproducible.
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read_jsonl(path):
    source = Path(path)
    if not source.exists():
        return []
    with source.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def market_result(row, home_score, away_score):
    market = row.get("market")
    if market == "double_chance":
        outcome = row.get("outcome")
        won = (home_score >= away_score) if outcome == "1x" else (
            away_score >= home_score if outcome == "x2" else None)
        return ("WIN" if won else "LOSS") if won is not None else None
    if market == "match_total":
        total = home_score + away_score
        point = float(row["point"])
        side = row.get("side")
        won = total > point if side == "over" else total < point if side == "under" else None
        return ("WIN" if won else "LOSS") if won is not None else None
    if market == "team_total":
        team_score = home_score if row.get("team") == "home" else (
            away_score if row.get("team") == "away" else None)
        if team_score is None:
            return None
        point = float(row["point"])
        side = row.get("side")
        won = team_score > point if side == "over" else team_score < point if side == "under" else None
        return ("WIN" if won else "LOSS") if won is not None else None
    if market == "btts":
        both = home_score > 0 and away_score > 0
        outcome = row.get("outcome")
        won = both if outcome == "yes" else (not both if outcome == "no" else None)
        return ("WIN" if won else "LOSS") if won is not None else None
    if market == "handicap" and float(row.get("point", 999)) == 0:
        diff = home_score - away_score
        team_diff = diff if row.get("team") == "home" else -diff if row.get("team") == "away" else None
        if team_diff is None:
            return None
        return "WIN" if team_diff > 0 else "LOSS" if team_diff < 0 else "PUSH"
    return None


def observation_id(row):
    identity = {key: row.get(key) for key in (
        "captured_at", "fixture_id", "market", "outcome", "team", "side",
        "point", "price", "bookmaker")}
    return json.dumps(identity, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def evaluate_rows(snapshots, prediction_archive, existing, evaluated_at=None):
    evaluated_at = evaluated_at or datetime.now(timezone.utc).isoformat()
    matches = (prediction_archive or {}).get("matches") or {}
    known = {row.get("observation_id") for row in existing}
    output = []
    for snapshot in snapshots:
        fixture = matches.get(str(snapshot.get("fixture_id")))
        if not fixture or fixture.get("status") != "FINISHED":
            continue
        try:
            home_score, away_score = int(fixture["homeScore"]), int(fixture["awayScore"])
        except (KeyError, TypeError, ValueError):
            continue
        result = market_result(snapshot, home_score, away_score)
        if result is None:
            continue
        oid = observation_id(snapshot)
        if oid in known:
            continue
        price = float(snapshot["price"])
        profit = price - 1.0 if result == "WIN" else -1.0 if result == "LOSS" else 0.0
        output.append({
            "observation_id": oid,
            "captured_at": snapshot.get("captured_at"),
            "evaluated_at": evaluated_at,
            "fixture_id": str(snapshot.get("fixture_id")),
            "league_code": snapshot.get("league_code"),
            "kickoff_utc": snapshot.get("kickoff_utc"),
            "home_team": snapshot.get("home_team"),
            "away_team": snapshot.get("away_team"),
            "market": snapshot.get("market"),
            "outcome": snapshot.get("outcome"),
            "team": snapshot.get("team"),
            "side": snapshot.get("side"),
            "point": snapshot.get("point"),
            "bookmaker": snapshot.get("bookmaker"),
            "price": price,
            "model_probability": snapshot.get("model_probability"),
            "model_probability_status": snapshot.get("model_probability_status", "not_available"),
            "home_score": home_score,
            "away_score": away_score,
            "result": result,
            "profit_units": round(profit, 6),
        })
        known.add(oid)
    return output


def append_results(rows, output_path):
    output = Path(output_path)
    if rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    all_rows = read_jsonl(output)
    wins = sum(row.get("result") == "WIN" for row in all_rows)
    losses = sum(row.get("result") == "LOSS" for row in all_rows)
    profit = sum(float(row.get("profit_units") or 0) for row in all_rows)
    summary = {"new_results": len(rows), "evaluated_snapshots": len(all_rows),
               "wins": wins, "losses": losses, "pushes": len(all_rows)-wins-losses,
               "profit_units": round(profit, 6),
               "roi_per_snapshot": round(profit / len(all_rows), 6) if all_rows else None,
               "archive": str(output)}
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshots", default="artifacts/odds-snapshots.jsonl")
    parser.add_argument("--data", default="docs/data.json")
    parser.add_argument("--output", default="artifacts/market-observation-results.jsonl")
    args = parser.parse_args()
    snapshots = read_jsonl(args.snapshots)
    data = json.loads(Path(args.data).read_text(encoding="utf-8"))
    existing = read_jsonl(args.output)
    rows = evaluate_rows(snapshots, data.get("predictionArchive") or {}, existing)
    append_results(rows, args.output)


if __name__ == "__main__":
    main()
