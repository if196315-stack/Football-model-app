"""Read-only live MelBet DNB feed audit; never writes to repository data."""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from collect_uefa_melbet_snapshots import fetch_provider_fixtures
from update_odds_api_sport import is_supported_full_time_market, market_kind, parse_odds


def build_report(fixtures: list[dict], captured_at: str | None = None) -> dict:
    captured_at = captured_at or datetime.now(timezone.utc).isoformat()
    events = []
    counts = {
        "provider_fixtures": len(fixtures),
        "fixtures_with_melbet_board": 0,
        "open_prematch_fulltime_handicap_markets": 0,
        "zero_line_quotes": 0,
        "complete_home_away_pairs": 0,
        "incomplete_pairs": 0,
    }
    for fixture in fixtures:
        board = (fixture.get("oddsBk") or {}).get("melbet")
        if not isinstance(board, dict):
            continue
        counts["fixtures_with_melbet_board"] += 1
        raw_markets = []
        for slug, market in (board.get("markets") or {}).items():
            if (not isinstance(market, dict) or market.get("status") != "open"
                    or market.get("stage") != "prematch"
                    or market_kind(slug, market) != "spread"
                    or not is_supported_full_time_market(slug, market)):
                continue
            counts["open_prematch_fulltime_handicap_markets"] += 1
            raw_markets.append({
                "slug": slug,
                "name": (market.get("name") or {}).get("en")
                    if isinstance(market.get("name"), dict) else market.get("name"),
                "outcomes": {
                    outcome: [
                        {"argument": line.get("argument"), "price": line.get("factor")}
                        for line in (stake.get("lines") or [])
                        if isinstance(stake, dict) and isinstance(line, dict)
                    ] + ([{"argument": None, "price": stake.get("factor")}]
                         if isinstance(stake, dict) and stake.get("factor") else [])
                    for outcome, stake in (market.get("stakes") or {}).items()
                    if isinstance(stake, dict)
                },
            })

        parsed = parse_odds(fixture)
        quotes = (parsed.get("best") or {}).get("spreads") or []
        counts["zero_line_quotes"] += len(quotes)
        grouped = {}
        for quote in quotes:
            group = str(quote.get("market_group") or quote.get("market_slug") or "unknown")
            grouped.setdefault(group, {})[quote.get("team")] = quote
        paired_groups = [group for group, sides in grouped.items()
                         if "home" in sides and "away" in sides]
        incomplete_groups = [group for group, sides in grouped.items()
                             if ("home" in sides) != ("away" in sides)]
        counts["complete_home_away_pairs"] += len(paired_groups)
        counts["incomplete_pairs"] += len(incomplete_groups)
        if raw_markets or quotes:
            stamp = fixture.get("startTimestamp")
            kickoff = None
            if isinstance(stamp, (int, float)):
                kickoff = datetime.fromtimestamp(stamp / 1000, tz=timezone.utc).isoformat()
            events.append({
                "event_id": str(fixture.get("id") or ""),
                "home": (fixture.get("homeTeam") or {}).get("name"),
                "away": (fixture.get("awayTeam") or {}).get("name"),
                "kickoff_utc": kickoff,
                "bookmaker_updated_at": parsed.get("bookmakerUpdatedAt"),
                "freshness_status": parsed.get("freshnessStatus"),
                "raw_open_prematch_fulltime_handicap_markets": raw_markets,
                "parsed_zero_handicap_quotes": quotes,
                "paired_market_groups": paired_groups,
                "incomplete_market_groups": incomplete_groups,
            })
    return {
        "captured_at": captured_at,
        "source": "API-Sport / MelBet",
        "mode": "read_only_no_repository_writes",
        "parser_scope": "full-time prematch Asian handicap 0 only",
        "counts": counts,
        "events_with_handicap_data": events,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="dnb-live-feed-audit.json")
    args = parser.parse_args()
    api_key = os.environ.get("API_SPORT_KEY", "").strip()
    if not api_key:
        raise RuntimeError("API_SPORT_KEY is not configured")
    fixtures = fetch_provider_fixtures(api_key)
    report = build_report(fixtures)
    Path(args.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report["counts"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
