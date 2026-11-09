#!/usr/bin/env python3
"""Append immutable snapshots of model-used best odds from docs/data.json.

No external API calls are made here. Run after BSD and The Odds API refreshes.
"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def valid_price(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 1


def snapshot_rows(data, retrieved_at=None):
    retrieved_at = retrieved_at or datetime.now(timezone.utc).isoformat()
    for competition in data.get("competitions", []):
        league_code = competition.get("code")
        for match in competition.get("matches", []):
            odds = match.get("odds") or {}
            # The production archive is strictly Melbet-only. Do not append
            # legacy snapshots from previous bookmaker providers.
            if str(odds.get("bookmaker") or data.get("oddsBookmaker") or "").lower() != "melbet":
                continue
            best = odds.get("best") or {}
            best_books = odds.get("bestBookmakers") or {}
            common = {
                "schema_version": 1, "retrieved_at": retrieved_at,
                "league_code": league_code, "fixture_id": str(match.get("id") or ""),
                "kickoff_utc": match.get("utcDate"),
                "home_team": match.get("home"), "away_team": match.get("away"),
            }
            if not common["fixture_id"] or not common["home_team"] or not common["away_team"]:
                continue

            for outcome, price in (best.get("doubleChance") or {}).items():
                if outcome in ("1x", "x2", "12") and valid_price(price):
                    yield {**common, "market": "double_chance", "outcome": outcome,
                           "price": float(price), "bookmaker": (best_books.get("doubleChance") or {}).get(outcome)}

            for quote in best.get("spreads") or []:
                if quote.get("team") not in ("home", "away") or not valid_price(quote.get("price")):
                    continue
                try:
                    point = float(quote.get("point"))
                except (TypeError, ValueError):
                    continue
                if abs(point) > 0.001:
                    continue
                yield {**common, "market": "spread", "team": quote["team"], "point": point,
                       "price": float(quote["price"]), "bookmaker": quote.get("bookmaker")}

            for quote in best.get("teamTotals") or []:
                if quote.get("team") not in ("home", "away") or quote.get("side") != "over":
                    continue
                if not valid_price(quote.get("price")):
                    continue
                try:
                    point = float(quote.get("point"))
                except (TypeError, ValueError):
                    continue
                yield {**common, "market": "team_total", "team": quote["team"], "side": "over",
                       "point": point, "price": float(quote["price"]), "bookmaker": quote.get("bookmaker")}

            for side, price in (best.get("btts") or {}).items():
                if side in ("yes", "no") and valid_price(price):
                    yield {**common, "market": "btts", "outcome": side,
                           "price": float(price), "bookmaker": "MELBET"}

            for quote in best.get("totals") or []:
                if quote.get("side") not in ("over", "under") or not valid_price(quote.get("price")):
                    continue
                try:
                    point = float(quote.get("point"))
                except (TypeError, ValueError):
                    continue
                yield {**common, "market": "match_total", "side": quote["side"],
                       "point": point, "price": float(quote["price"]), "bookmaker": "MELBET"}

            for quote in best.get("teamTotals") or []:
                if quote.get("team") not in ("home", "away") or quote.get("side") not in ("over", "under"):
                    continue
                if not valid_price(quote.get("price")):
                    continue
                try:
                    point = float(quote.get("point"))
                except (TypeError, ValueError):
                    continue
                yield {**common, "market": "team_total", "team": quote["team"], "side": quote["side"],
                       "point": point, "price": float(quote["price"]), "bookmaker": "MELBET"}


def append_snapshots(input_path, output_path, retrieved_at=None):
    data = json.loads(Path(input_path).read_text(encoding="utf-8"))
    rows = list(snapshot_rows(data, retrieved_at))
    output = Path(output_path)
    if rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {"retrieved_at": retrieved_at or datetime.now(timezone.utc).isoformat(),
               "records_appended": len(rows), "archive": str(output),
               "archive_written": bool(rows)}
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="docs/data.json")
    parser.add_argument("--output", default="artifacts/odds-snapshots.jsonl")
    args = parser.parse_args()
    append_snapshots(args.input, args.output)


if __name__ == "__main__":
    main()
