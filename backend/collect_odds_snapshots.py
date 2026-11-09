#!/usr/bin/env python3
"""Append BetBoom-only, timestamped domestic market snapshots from docs/data.json.

This collector runs after update_odds_api_sport.py. It intentionally ignores
legacy multi-bookmaker snapshots and stores the BetBoom source-board timestamp
so stale quotes can be excluded during later evaluation.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

BOOKMAKER = "BETBOOM"
PARSER_VERSION = "betboom-fulltime-v2"
LEAGUE_CODES = {"PL": "ENG", "PD": "ESP", "SA": "ITA", "BL1": "GER", "FL1": "FRA"}
MATCH_TOTAL_LINES = {1.5, 2.5, 3.5}
TEAM_TOTAL_LINES = {0.5, 1.5, 2.5, 3.5}


def valid_price(value: Any) -> bool:
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and 1.0 < float(value) < 1000.0)


def _number(value: Any) -> float | None:
    try:
        result = float(value)
        return result if result == result else None
    except (TypeError, ValueError):
        return None


def _quote(common: dict, market: str, price: Any, **fields) -> dict | None:
    if not valid_price(price):
        return None
    return {**common, "market": market, "price": float(price), **fields}


def snapshot_rows(data: dict, captured_at: str | None = None):
    captured_at = captured_at or datetime.now(timezone.utc).isoformat()
    for competition in data.get("competitions", []):
        source_league = str(competition.get("code") or "")
        league_code = LEAGUE_CODES.get(source_league, source_league)
        for match in competition.get("matches", []):
            odds = match.get("odds") or {}
            if str(odds.get("bookmaker") or "").upper() != BOOKMAKER:
                continue
            boards = odds.get("bookmakers") or []
            board = next((item for item in boards
                          if str(item.get("key") or "").lower() == "betboom"
                          or str(item.get("title") or "").upper() == BOOKMAKER), None)
            # Preserve provider market identity; never reconstruct complements
            # from a flattened best-price object.
            if not board:
                continue
            fixture_id = str(match.get("id") or "")
            home, away = match.get("home"), match.get("away")
            if not fixture_id or not home or not away:
                continue
            common = {
                "schema_version": 2,
                "captured_at": captured_at,
                "league_code": league_code,
                "source_league_code": source_league,
                "fixture_id": fixture_id,
                "kickoff_utc": match.get("utcDate"),
                "home_team": home,
                "away_team": away,
                "bookmaker": BOOKMAKER,
                "bookmaker_updated_at": odds.get("bookmakerUpdatedAt") or odds.get("updatedAt"),
                "bookmaker_update_age_seconds": odds.get("bookmakerUpdateAgeSeconds"),
                "freshness_status": odds.get("freshnessStatus") or "unknown",
                "market_parser_version": PARSER_VERSION,
            }

            for outcome, price in (board.get("doubleChance") or {}).items():
                if outcome not in ("1x", "x2", "12"):
                    continue
                row = _quote(common, "double_chance", price, outcome=outcome,
                             candidate_market=outcome in ("1x", "x2"),
                             reference_only=outcome == "12")
                if row:
                    yield row

            for quote in board.get("spreads") or []:
                team, point = quote.get("team"), _number(quote.get("point"))
                if team not in ("home", "away") or point is None or abs(point) > 0.001:
                    continue
                row = _quote(common, "handicap", quote.get("price"), team=team, point=0.0,
                             market_slug=quote.get("market_slug"),
                             provider_market_group=quote.get("market_group"),
                             provider_market_name=quote.get("market_name"),
                             candidate_market=True, reference_only=False)
                if row:
                    yield row

            for quote in board.get("totals") or []:
                side, point = quote.get("side"), _number(quote.get("point"))
                if side not in ("over", "under") or point not in MATCH_TOTAL_LINES:
                    continue
                row = _quote(common, "match_total", quote.get("price"), side=side, point=point,
                             market_slug=quote.get("market_slug"),
                             provider_market_group=quote.get("market_group"),
                             provider_market_name=quote.get("market_name"),
                             candidate_market=side == "over", reference_only=side == "under")
                if row:
                    yield row

            for quote in board.get("teamTotals") or []:
                team, side, point = quote.get("team"), quote.get("side"), _number(quote.get("point"))
                if (team not in ("home", "away") or side not in ("over", "under")
                        or point not in TEAM_TOTAL_LINES):
                    continue
                row = _quote(common, "team_total", quote.get("price"), team=team, side=side,
                             point=point, market_slug=quote.get("market_slug"),
                             provider_market_group=quote.get("market_group"),
                             provider_market_name=quote.get("market_name"),
                             candidate_market=side == "over", reference_only=side == "under")
                if row:
                    yield row

            for outcome, price in (board.get("btts") or {}).items():
                if outcome not in ("yes", "no"):
                    continue
                row = _quote(common, "btts", price, outcome=outcome,
                             candidate_market=outcome == "yes", reference_only=outcome == "no")
                if row:
                    yield row


def append_snapshots(input_path, output_path, captured_at=None):
    data = json.loads(Path(input_path).read_text(encoding="utf-8"))
    rows = list(snapshot_rows(data, captured_at))
    output = Path(output_path)
    if rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {
        "captured_at": captured_at or datetime.now(timezone.utc).isoformat(),
        "bookmaker": BOOKMAKER,
        "market_parser_version": PARSER_VERSION,
        "records_appended": len(rows),
        "by_league": dict(Counter(row["league_code"] for row in rows)),
        "by_market": dict(Counter(row["market"] for row in rows)),
        "archive": str(output),
        "archive_written": bool(rows),
    }
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
