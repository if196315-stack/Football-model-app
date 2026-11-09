#!/usr/bin/env python3
"""Settle unique UEFA paper BET entries once final scores appear in the fixture cache."""
from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAPER_BETS = ROOT / "artifacts" / "uefa-paper-bets.jsonl"
FIXTURE_CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
SETTLEMENTS = ROOT / "artifacts" / "uefa-paper-settlements.jsonl"


def _score(value):
    if isinstance(value, bool):
        return None
    try:
        result = int(value)
    except (TypeError, ValueError):
        return None
    return result if result >= 0 else None


def settle_bet(bet: dict, fixture: dict, settled_at: str | None = None) -> dict | None:
    if fixture.get("status") != "finished":
        return None
    home_score, away_score = _score(fixture.get("homeScore")), _score(fixture.get("awayScore"))
    if home_score is None or away_score is None:
        return None
    market = bet.get("market")
    price = float(bet["price"])
    result = None

    if market == "double_chance":
        outcome = bet.get("outcome")
        won = (home_score >= away_score) if outcome == "1x" else (
            away_score >= home_score if outcome == "x2" else None
        )
        if won is None:
            return None
        result = "WIN" if won else "LOSS"
    elif market == "match_total_over":
        if bet.get("point") is None:
            return None
        result = "WIN" if home_score + away_score > float(bet["point"]) else "LOSS"
    elif market == "team_total_over":
        team_score = home_score if bet.get("team") == "home" else (
            away_score if bet.get("team") == "away" else None
        )
        if team_score is None or bet.get("point") is None:
            return None
        result = "WIN" if team_score > float(bet["point"]) else "LOSS"
    elif market == "btts":
        both_score = home_score > 0 and away_score > 0
        outcome = bet.get("outcome")
        won = both_score if outcome == "yes" else (not both_score if outcome == "no" else None)
        if won is None:
            return None
        result = "WIN" if won else "LOSS"
    elif market == "handicap":
        team = bet.get("team")
        if team not in ("home", "away"):
            return None
        diff = home_score - away_score
        team_diff = diff if team == "home" else -diff
        result = "WIN" if team_diff > 0 else "LOSS" if team_diff < 0 else "PUSH"
    else:
        return None

    profit = price - 1.0 if result == "WIN" else -1.0 if result == "LOSS" else 0.0
    return {
        "signal_id": bet.get("signal_id"),
        "fixture_id": str(bet.get("fixture_id")),
        "league_code": bet.get("league_code"),
        "season": bet.get("season"),
        "kickoff_utc": bet.get("kickoff_utc"),
        "home_team": bet.get("home_team"),
        "away_team": bet.get("away_team"),
        "market": market,
        "provider_market_group": bet.get("provider_market_group"),
        "point": bet.get("point"),
        "team": bet.get("team"),
        "outcome": bet.get("outcome"),
        "price": price,
        "model_probability": bet.get("model_probability"),
        "edge": bet.get("edge"),
        "expected_value": bet.get("expected_value"),
        "home_score": home_score,
        "away_score": away_score,
        "result": result,
        "profit_units": round(profit, 6),
        "settled_at": settled_at or datetime.now(timezone.utc).isoformat(),
        "paper_only": True,
    }


def read_jsonl(path: str | Path) -> list[dict]:
    source = Path(path)
    if not source.exists():
        return []
    with source.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def settle_open_bets(bets: list[dict], fixtures: dict, existing: list[dict],
                     settled_at: str | None = None) -> list[dict]:
    known = {str(row.get("signal_id")) for row in existing}
    settled = []
    for bet in bets:
        signal_id = str(bet.get("signal_id") or "")
        if not signal_id or signal_id in known:
            continue
        fixture = (fixtures.get("matches") or {}).get(str(bet.get("fixture_id")))
        if not fixture:
            continue
        row = settle_bet(bet, fixture, settled_at)
        if row:
            settled.append(row)
            known.add(signal_id)
    return settled


def append_settlements(rows: list[dict], output_path: str | Path = SETTLEMENTS) -> dict:
    output = Path(output_path)
    if rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    all_rows = read_jsonl(output)
    counts = Counter(row["result"] for row in all_rows)
    total_profit = sum(float(row["profit_units"]) for row in all_rows)
    summary = {
        "new_settlements": len(rows),
        "settled_total": len(all_rows),
        "by_result": dict(sorted(counts.items())),
        "profit_units": round(total_profit, 6),
        "yield_per_settled_bet": round(total_profit / len(all_rows), 6) if all_rows else None,
        "settlements_archive": str(output),
    }
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bets", default=str(PAPER_BETS))
    parser.add_argument("--fixture-cache", default=str(FIXTURE_CACHE))
    parser.add_argument("--output", default=str(SETTLEMENTS))
    args = parser.parse_args()
    bets = read_jsonl(args.bets)
    cache_path = Path(args.fixture_cache)
    if not cache_path.exists():
        raise RuntimeError(f"UEFA fixture cache is missing: {cache_path}")
    fixtures = json.loads(cache_path.read_text(encoding="utf-8"))
    existing = read_jsonl(args.output)
    settled = settle_open_bets(bets, fixtures, existing)
    append_settlements(settled, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
