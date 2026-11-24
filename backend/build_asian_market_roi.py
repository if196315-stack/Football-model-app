#!/usr/bin/env python3
"""Build a MelBet-only paper ROI archive for six Asian markets."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

MARKETS = ("home_itb10", "away_itb10", "home_f0", "away_f0", "home_fplus1", "away_fplus1")


def _number(value):
    try:
        result = float(value)
        return result if result == result and abs(result) != float("inf") else None
    except (TypeError, ValueError, OverflowError):
        return None


def market_key(row):
    market = row.get("market")
    if market == "team_total_over" and row.get("side") == "over" and _number(row.get("point")) == 1.0:
        team = row.get("team")
        return f"{team}_itb10" if team in ("home", "away") else None
    if market == "handicap":
        team, point = row.get("team"), _number(row.get("point"))
        if team not in ("home", "away") or point not in (0.0, 1.0):
            return None
        return f"{team}_f0" if point == 0.0 else f"{team}_fplus1"
    return None


def _time(value):
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
    except ValueError:
        return None


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def read_jsonl(path):
    source = Path(path)
    if not source.exists():
        return []
    rows = []
    with source.open(encoding="utf-8") as handle:
        for line in handle:
            try:
                row = json.loads(line)
                if isinstance(row, dict):
                    rows.append(row)
            except (json.JSONDecodeError, TypeError):
                continue
    return rows


def quote_identity(row):
    return "|".join((str(row.get("league_code") or ""),
                     str(row.get("fixture_id") or ""),
                     str(row.get("market_key") or "")))


def select_quotes(snapshots, existing):
    selected = {quote_identity(row): row for row in existing}
    candidates = {}
    for row in snapshots:
        if (str(row.get("bookmaker") or "").upper() != "MELBET"
                or row.get("source") != "API-Sport"
                or row.get("prematch_active") is not True
                or row.get("freshness_status") != "fresh"
                or row.get("market_parser_version") != "melbet-fulltime-v3"):
            continue
        key = market_key(row)
        price = _number(row.get("price"))
        captured = _time(row.get("captured_at"))
        forecast_at = _time(row.get("model_prediction_time"))
        win = _number(row.get("model_probability"))
        push = _number(row.get("model_push_probability"))
        lose = _number(row.get("model_loss_probability"))
        if (key not in MARKETS or price is None or not 1.0 < price < 1000.0
                or captured is None or forecast_at is None or captured < forecast_at
                or any(value is None or not 0.0 <= value <= 1.0 for value in (win, push, lose))
                or abs(win + push + lose - 1.0) > 0.002):
            continue
        identity = "|".join((str(row.get("league_code") or ""),
                             str(row.get("fixture_id") or ""), key))
        if identity in selected:
            continue
        quote = {
            "league_code": str(row.get("league_code") or ""),
            "fixture_id": str(row.get("fixture_id") or ""),
            "fixture_namespace": str(row.get("fixture_namespace") or ""),
            "kickoff_utc": row.get("kickoff_utc"),
            "home_team": row.get("home_team"),
            "away_team": row.get("away_team"),
            "market_key": key,
            "market": row.get("market"),
            "team": row.get("team"),
            "point": _number(row.get("point")),
            "bookmaker": "MELBET",
            "price": round(price, 6),
            "model_win_probability": round(win, 8),
            "model_push_probability": round(push, 8),
            "model_loss_probability": round(lose, 8),
            "model_version": row.get("model_version"),
            "model_history_max_date": row.get("model_history_max_date"),
            "prediction_captured_at": row.get("model_prediction_time"),
            "captured_at": row.get("captured_at"),
            "bookmaker_updated_at": row.get("bookmaker_updated_at"),
        }
        previous = candidates.get(identity)
        if previous is None or str(quote["captured_at"]) < str(previous["captured_at"]):
            candidates[identity] = quote
    selected.update(candidates)
    return sorted(selected.values(), key=quote_identity)


def market_result(row, home, away):
    key = row.get("market_key")
    if key == "home_itb10":
        goals = home
        return "WIN" if goals >= 2 else "PUSH" if goals == 1 else "LOSS"
    if key == "away_itb10":
        goals = away
        return "WIN" if goals >= 2 else "PUSH" if goals == 1 else "LOSS"
    if key in ("home_f0", "away_f0", "home_fplus1", "away_fplus1"):
        team_score, opponent = (home, away) if key.startswith("home_") else (away, home)
        handicap = 1 if key.endswith("fplus1") else 0
        margin = team_score + handicap - opponent
        return "WIN" if margin > 0 else "PUSH" if margin == 0 else "LOSS"
    return None


def _settle(quotes, domestic, uefa):
    finals = {}
    for feed in (domestic, uefa):
        for match in feed.get("evaluationMatches") or []:
            if match.get("status") != "FINISHED" or match.get("homeScore") is None or match.get("awayScore") is None:
                continue
            league, fixture = str(match.get("leagueCode") or ""), str(match.get("id") or "")
            if league:
                finals[(league, fixture)] = match
            finals[("", fixture)] = match
    records = []
    for quote in quotes:
        final = finals.get((quote.get("league_code", ""), quote.get("fixture_id", "")))
        if final is None:
            final = finals.get(("", quote.get("fixture_id", "")))
        record = dict(quote)
        if final is None:
            record.update({"status": "PENDING", "result": None, "home_score": None,
                           "away_score": None, "profit_units": None})
        else:
            home, away = int(final["homeScore"]), int(final["awayScore"])
            result = market_result(quote, home, away)
            if result is None:
                record.update({"status": "PENDING", "result": None, "profit_units": None})
            else:
                profit = quote["price"] - 1.0 if result == "WIN" else -1.0 if result == "LOSS" else 0.0
                record.update({"status": "SETTLED", "result": result,
                               "home_score": home, "away_score": away,
                               "profit_units": round(profit, 6)})
        records.append(record)
    return records


def _summary(records):
    settled = [row for row in records if row.get("status") == "SETTLED"]
    wins = sum(row.get("result") == "WIN" for row in settled)
    pushes = sum(row.get("result") == "PUSH" for row in settled)
    losses = sum(row.get("result") == "LOSS" for row in settled)
    profit = sum(float(row.get("profit_units") or 0.0) for row in settled)
    return {
        "settled": len(settled), "pending": sum(row.get("status") == "PENDING" for row in records),
        "wins": wins, "pushes": pushes, "losses": losses,
        "profitUnits": round(profit, 6), "turnoverUnits": len(settled),
        "roi": round(profit / len(settled) * 100.0, 6) if settled else None,
        "averageOdds": round(sum(float(row["price"]) for row in settled) / len(settled), 6) if settled else None,
    }


def build(domestic, uefa, snapshots, existing, today=None):
    quotes = select_quotes(snapshots, existing)
    records = _settle(quotes, domestic, uefa)
    summaries = {
        market: _summary([row for row in records if row.get("market_key") == market])
        for market in MARKETS
    }
    all_summary = _summary(records)
    return {
        "schemaVersion": 1, "updatedAt": datetime.now(timezone.utc).isoformat(),
        "bookmaker": "MELBET", "paperOnly": True, "stakeUnitsPerForecast": 1,
        "markets": summaries, "summary": all_summary, "records": records,
    }, quotes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshots", default="artifacts/all-eight-competitions-melbet-snapshots.jsonl")
    parser.add_argument("--quotes", default="artifacts/asian-market-roi-quotes.jsonl")
    parser.add_argument("--data", default="docs/data.json")
    parser.add_argument("--uefa-data", default="docs/uefa-data.json")
    parser.add_argument("--output", default="docs/asian-market-roi.json")
    args = parser.parse_args()
    domestic, uefa = read_json(args.data), read_json(args.uefa_data)
    quotes = read_jsonl(args.quotes)
    report, selected = build(domestic, uefa, read_jsonl(args.snapshots), quotes)
    quote_path = Path(args.quotes)
    quote_path.parent.mkdir(parents=True, exist_ok=True)
    temp = quote_path.with_suffix(quote_path.suffix + ".tmp")
    temp.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in selected), encoding="utf-8")
    temp.replace(quote_path)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_output = output.with_suffix(output.suffix + ".tmp")
    temp_output.write_text(json.dumps(report, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temp_output.replace(output)
    print(json.dumps({"summary": report["summary"], "markets": report["markets"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
