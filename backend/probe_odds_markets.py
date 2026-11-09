#!/usr/bin/env python3
"""Probe soccer market coverage on a small, quota-bounded sample.

Uses the free events endpoint to identify fixtures, then requests event-level
odds for the selected fixtures. Never logs or persists the API key.
"""
import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests
from update_odds import similarity

SPORTS = {
    "PL": "soccer_epl",
    "PD": "soccer_spain_la_liga",
    "SA": "soccer_italy_serie_a",
    "BL1": "soccer_germany_bundesliga",
    "FL1": "soccer_france_ligue_one",
}
MARKETS = "h2h,spreads,totals,team_totals,double_chance,btts"
ROOT = "https://api.the-odds-api.com/v4/sports"


def parse_time(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def match_event(fixture, events, league):
    kickoff = parse_time(fixture.get("utcDate"))
    if not kickoff:
        return None
    ranked = []
    for event in events:
        event_time = parse_time(event.get("commence_time"))
        if not event_time or abs((event_time - kickoff).total_seconds()) > 18 * 3600:
            continue
        h = similarity(fixture.get("home"), event.get("home_team"), league)
        a = similarity(fixture.get("away"), event.get("away_team"), league)
        if h >= 0.86 and a >= 0.86:
            ranked.append((h + a, event))
    ranked.sort(key=lambda x: x[0], reverse=True)
    if not ranked or (len(ranked) > 1 and ranked[0][0] - ranked[1][0] < 0.08):
        return None
    return ranked[0][1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="docs/data.json")
    parser.add_argument("--output", default="artifacts/odds-market-probe.jsonl")
    parser.add_argument("--max-events-per-league", type=int, default=1)
    args = parser.parse_args()
    api_key = os.getenv("ODDS_API_KEY", "").strip()
    if not api_key:
        print("ODDS_API_KEY is not configured; no API request was made.", file=sys.stderr)
        return 2
    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    competitions = {x.get("code"): x for x in data.get("competitions", [])}
    rows, summary = [], {"markets_requested": MARKETS, "by_league": {}, "errors": []}
    retrieved_at = datetime.now(timezone.utc).isoformat()
    remaining = used = last_cost = None

    for code, sport in SPORTS.items():
        comp = competitions.get(code)
        if not comp:
            summary["by_league"][code] = {"eligible": 0, "probed": 0}
            continue
        fixtures = sorted(
            [m for m in comp.get("matches", [])
             if m.get("status") in ("SCHEDULED", "TIMED") and m.get("utcDate")],
            key=lambda m: m.get("utcDate", ""),
        )
        try:
            events_resp = requests.get(
                f"{ROOT}/{sport}/events",
                params={"apiKey": api_key, "dateFormat": "iso"},
                timeout=35,
            )
            events_resp.raise_for_status()
            events = events_resp.json()
            selected = []
            seen = set()
            for fixture in fixtures:
                event = match_event(fixture, events, code)
                if event and event.get("id") not in seen:
                    selected.append((fixture, event))
                    seen.add(event.get("id"))
                if len(selected) >= max(0, args.max_events_per_league):
                    break
            probed = 0
            for fixture, event in selected:
                response = requests.get(
                    f"{ROOT}/{sport}/events/{event['id']}/odds",
                    params={
                        "apiKey": api_key,
                        "regions": "eu",
                        "markets": MARKETS,
                        "oddsFormat": "decimal",
                        "dateFormat": "iso",
                    },
                    timeout=35,
                )
                remaining = response.headers.get("x-requests-remaining", remaining)
                used = response.headers.get("x-requests-used", used)
                last_cost = response.headers.get("x-requests-last", last_cost)
                response.raise_for_status()
                payload = response.json()
                market_counts = {}
                bookmakers = []
                for book in payload.get("bookmakers", []):
                    markets = []
                    for market in book.get("markets", []):
                        market_counts[market.get("key")] = market_counts.get(market.get("key"), 0) + 1
                        markets.append({
                            "key": market.get("key"),
                            "last_update": market.get("last_update"),
                            "outcomes": market.get("outcomes", []),
                        })
                    if markets:
                        bookmakers.append({
                            "key": book.get("key"),
                            "title": book.get("title"),
                            "markets": markets,
                        })
                rows.append({
                    "schema_version": 1,
                    "retrieved_at": retrieved_at,
                    "league_code": code,
                    "fixture_id": str(fixture.get("id")),
                    "kickoff_utc": fixture.get("utcDate"),
                    "home_team": fixture.get("home"),
                    "away_team": fixture.get("away"),
                    "odds_event_id": event.get("id"),
                    "markets_returned_by_bookmaker": market_counts,
                    "bookmakers": bookmakers,
                })
                probed += 1
            summary["by_league"][code] = {
                "eligible": len(fixtures),
                "probed": probed,
                "matched_events": len(selected),
            }
        except Exception as exc:
            # Avoid request URLs and query parameters, which can contain the key.
            summary["errors"].append(f"{code}: {type(exc).__name__}")
            summary["by_league"][code] = {
                "eligible": len(fixtures), "probed": 0, "error": type(exc).__name__
            }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("a", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    summary.update({
        "retrieved_at": retrieved_at,
        "records_written": len(rows),
        "requests_used_header": int(used) if str(used or "").isdigit() else None,
        "requests_remaining": int(remaining) if str(remaining or "").isdigit() else None,
        "last_request_cost": int(last_cost) if str(last_cost or "").isdigit() else None,
        "archive": str(out),
    })
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if summary["errors"] and not rows else 0


if __name__ == "__main__":
    raise SystemExit(main())
