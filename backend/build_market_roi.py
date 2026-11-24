#!/usr/bin/env python3
"""Freeze one real MelBet price per archived forecast/market and publish settled ROI rows.

Only the eight markets shown in the Android ALL prediction summary are admitted.
The probability is always read from the frozen prediction archive, never from a
newly recalculated model snapshot. A quote must have been captured after that
forecast was frozen and while the MelBet prematch board was active and fresh.
"""
from __future__ import annotations

import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Exact market keys used by MainActivity.calculateStatsMetrics().
def market_key(row: dict) -> str | None:
    market = row.get("market")
    if market == "double_chance" and row.get("outcome") in ("1x", "x2"):
        return str(row["outcome"])
    if market == "match_total_over" and row.get("side") == "over" and _number(row.get("point")) == 2.5:
        return "over25"
    if market == "team_total_over" and row.get("side") == "over":
        team, point = row.get("team"), _number(row.get("point"))
        if team in ("home", "away") and point in (0.5, 1.5):
            return f"{team}Over{str(point).replace('.', '')}"
    if market == "btts" and row.get("outcome") == "yes":
        return "bttsYes"
    return None


def _number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
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
    source = Path(path)
    if not source.exists():
        return {}
    try:
        return json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def read_jsonl(path):
    source = Path(path)
    if not source.exists():
        return []
    rows = []
    with source.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            try:
                value = json.loads(line)
                if isinstance(value, dict):
                    rows.append(value)
            except json.JSONDecodeError:
                continue
    return rows


def archive_entry(row, domestic, uefa):
    league = str(row.get("league_code") or "")
    fixture = str(row.get("fixture_id") or "")
    namespace = str(row.get("fixture_namespace") or "")
    if namespace == "UEFA" or league in ("UCL", "UEL", "UECL"):
        matches = ((uefa.get("predictionArchive") or {}).get("matches") or {})
        return matches.get(f"{league}:{fixture}")
    matches = ((domestic.get("predictionArchive") or {}).get("matches") or {})
    return matches.get(fixture)


def quote_identity(row):
    return "|".join((str(row.get("league_code") or ""),
                     str(row.get("fixture_id") or ""),
                     str(row.get("market_key") or "")))


def select_quotes(snapshots, domestic, uefa, existing):
    by_identity = {quote_identity(row): row for row in existing}
    candidates = {}
    for row in snapshots:
        if (str(row.get("bookmaker") or "").upper() != "MELBET"
                or row.get("source") != "API-Sport"
                or row.get("prematch_active") is not True
                or row.get("freshness_status") != "fresh"
                or row.get("market_parser_version") not in ("melbet-fulltime-v2", "melbet-fulltime-v3")
                or row.get("candidate_market") is False
                or row.get("reference_only") is True):
            continue
        key = market_key(row)
        price = _number(row.get("price"))
        captured = _time(row.get("captured_at"))
        if not key or price is None or not 1.0 < price < 1000.0 or captured is None:
            continue
        frozen = archive_entry(row, domestic, uefa)
        if not isinstance(frozen, dict) or not isinstance(frozen.get("prediction"), dict):
            continue
        prediction_time = _time(frozen.get("predictionCapturedAt"))
        if prediction_time is None or captured < prediction_time:
            continue
        probability = _number(frozen["prediction"].get(key))
        if probability is None or not 0.5 <= probability <= 1.0:
            continue
        identity = "|".join((str(row.get("league_code") or ""),
                             str(row.get("fixture_id") or ""), key))
        old = by_identity.get(identity)
        # If the frozen forecast changed before kickoff, select a quote from
        # after the new capture time instead of carrying forward an old pairing.
        if old and old.get("prediction_captured_at") == frozen.get("predictionCapturedAt"):
            continue
        quote = {
            "league_code": str(row.get("league_code") or ""),
            "fixture_id": str(row.get("fixture_id") or ""),
            "fixture_namespace": str(row.get("fixture_namespace") or ""),
            "kickoff_utc": row.get("kickoff_utc") or frozen.get("utcDate"),
            "home_team": row.get("home_team") or frozen.get("home"),
            "away_team": row.get("away_team") or frozen.get("away"),
            "market_key": key,
            "market": row.get("market"),
            "outcome": row.get("outcome"),
            "team": row.get("team"),
            "side": row.get("side"),
            "point": row.get("point"),
            "bookmaker": "MELBET",
            "price": round(price, 6),
            "model_probability": round(probability, 8),
            "prediction_captured_at": frozen.get("predictionCapturedAt"),
            "captured_at": row.get("captured_at"),
            "bookmaker_updated_at": row.get("bookmaker_updated_at"),
            "model_version": (frozen["prediction"].get("modelVersion")
                              or row.get("model_version")),
        }
        previous = candidates.get(identity)
        if previous is None or str(quote.get("captured_at")) < str(previous.get("captured_at")):
            candidates[identity] = quote

    for identity, quote in candidates.items():
        by_identity[identity] = quote
    return sorted(by_identity.values(), key=lambda row: quote_identity(row))


def market_result(row, home, away):
    key = row.get("market_key")
    if key == "1x":
        return "WIN" if home >= away else "LOSS"
    if key == "x2":
        return "WIN" if away >= home else "LOSS"
    if key == "over25":
        return "WIN" if home + away > 2.5 else "LOSS"
    if key == "homeOver15":
        return "WIN" if home > 1.5 else "LOSS"
    if key == "awayOver15":
        return "WIN" if away > 1.5 else "LOSS"
    if key == "homeOver05":
        return "WIN" if home > 0.5 else "LOSS"
    if key == "awayOver05":
        return "WIN" if away > 0.5 else "LOSS"
    if key == "bttsYes":
        return "WIN" if home > 0 and away > 0 else "LOSS"
    return None


def settled_records(quotes, domestic, uefa):
    final_by_identity = {}
    for feed in (domestic, uefa):
        # The compact evaluation archive may lag behind competition fixtures.
        # Collect both sources so a newly finished match is settled immediately.
        sources = [(str(match.get("leagueCode") or ""), match)
                   for match in (feed.get("evaluationMatches") or [])]
        for competition in feed.get("competitions") or []:
            league = str(competition.get("code") or "")
            sources.extend((league, match) for match in (competition.get("matches") or []))
        for league, match in sources:
            status = str(match.get("status") or "").strip().upper()
            if (status not in {"FINISHED", "FT", "AET", "PEN", "FULL_TIME",
                               "AFTER_EXTRA_TIME", "AFTER_PENALTIES"}
                    or match.get("homeScore") is None
                    or match.get("awayScore") is None):
                continue
            fixture = str(match.get("id") or "")
            if not fixture:
                continue
            final_by_identity[(league, fixture)] = match
    output = []
    for quote in quotes:
        league, fixture = quote.get("league_code"), quote.get("fixture_id")
        final = final_by_identity.get((league, fixture))
        # Domestic archives are keyed by fixture id; their evaluation rows may
        # not carry leagueCode, so resolve by the unique fixture id as fallback.
        if final is None:
            # Provider league codes in the odds archive (ENG/ESP/...) may differ
            # from feed competition codes (PL/PD/...). A unique fixture ID is safe.
            matches = [match for (_, match_id), match in final_by_identity.items()
                       if match_id == str(fixture)]
            if len(matches) == 1:
                final = matches[0]
        row = dict(quote)
        if final is not None:
            try:
                home, away = int(final["homeScore"]), int(final["awayScore"])
                result = market_result(quote, home, away)
            except (TypeError, ValueError):
                result = None
            if result:
                row.update({
                    "status": "SETTLED", "result": result,
                    "home_score": home, "away_score": away,
                    "profit_units": round(
                        float(quote["price"]) - 1.0 if result == "WIN"
                        else -1.0 if result == "LOSS" else 0.0, 6
                    ),
                })
                output.append(row)
                continue
        row.update({"status": "PENDING", "result": None, "profit_units": None})
        output.append(row)
    return output


def _subtract_months(day, months):
    import calendar
    month_index = day.year * 12 + day.month - 1 - months
    year, month_zero = divmod(month_index, 12)
    month = month_zero + 1
    return day.replace(year=year, month=month, day=min(day.day, calendar.monthrange(year, month)[1]))


def _summary(records):
    settled = [row for row in records if row.get("status") == "SETTLED"]
    wins = sum(row.get("result") == "WIN" for row in settled)
    losses = sum(row.get("result") == "LOSS" for row in settled)
    pushes = sum(row.get("result") == "PUSH" for row in settled)
    profit = sum(float(row.get("profit_units") or 0.0) for row in settled)
    turnover = len(settled)
    return {
        "settled": turnover, "pending": sum(row.get("status") == "PENDING" for row in records),
        "wins": wins, "losses": losses, "pushes": pushes,
        "profitUnits": round(profit, 6), "turnoverUnits": turnover,
        "roi": round(profit / turnover * 100.0, 6) if turnover else None,
        "averageOdds": round(sum(float(row["price"]) for row in settled) / turnover, 6)
            if turnover else None,
    }


def _period_records(records, period, today):
    if period == "ALL":
        return records
    months = {"MONTH": 1, "QUARTER": 3, "HALF": 6, "YEAR": 12}
    if period == "WEEK":
        start = today - __import__("datetime").timedelta(days=6)
    elif period in months:
        start = _subtract_months(today, months[period])
    else:
        return records
    selected = []
    for row in records:
        match_day = _time(row.get("kickoff_utc"))
        if match_day and start <= match_day.date() <= today:
            selected.append(row)
    return selected


def build(domestic, uefa, snapshots, quotes, today=None):
    selected = select_quotes(snapshots, domestic, uefa, quotes)
    records = settled_records(selected, domestic, uefa)
    today = today or datetime.now(timezone.utc).date()
    periods = {
        period: _summary(_period_records(records, period, today))
        for period in ("WEEK", "MONTH", "QUARTER", "HALF", "YEAR", "ALL")
    }
    return {
        "schemaVersion": 1,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "stakeUnitsPerForecast": 1,
        "minimumModelProbability": 0.5,
        "bookmaker": "MELBET",
        "records": records,
        "summary": periods["ALL"],
        "periods": periods,
    }, selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshots", default="artifacts/all-eight-competitions-melbet-snapshots.jsonl")
    parser.add_argument("--quotes", default="artifacts/market-roi-quotes.jsonl")
    parser.add_argument("--data", default="docs/data.json")
    parser.add_argument("--uefa-data", default="docs/uefa-data.json")
    parser.add_argument("--output", default="docs/market-roi.json")
    args = parser.parse_args()

    domestic = read_json(args.data)
    uefa = read_json(args.uefa_data)
    existing = read_jsonl(args.quotes)
    report, selected = build(domestic, uefa, read_jsonl(args.snapshots), existing)

    quote_path = Path(args.quotes)
    quote_path.parent.mkdir(parents=True, exist_ok=True)
    quote_tmp = quote_path.with_suffix(quote_path.suffix + ".tmp")
    quote_tmp.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n"
                                 for row in selected), encoding="utf-8")
    quote_tmp.replace(quote_path)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output_tmp = output.with_suffix(output.suffix + ".tmp")
    output_tmp.write_text(json.dumps(report, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    output_tmp.replace(output)
    print(json.dumps(report["summary"], ensure_ascii=False))


if __name__ == "__main__":
    main()
