#!/usr/bin/env python3
"""Collect a timestamped snapshot of currently available 1X2 odds.

This is prospective data collection, not a historical-odds downloader.
The API key is read only from ODDS_API_KEY and is never logged or persisted.
"""
import argparse
import json
import os
import re
import sys
import unicodedata
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

import requests

SPORTS = {
    "PL": "soccer_epl",
    "PD": "soccer_spain_la_liga",
    "SA": "soccer_italy_serie_a",
    "BL1": "soccer_germany_bundesliga",
    "FL1": "soccer_france_ligue_one",
}
API_ROOT = "https://api.the-odds-api.com/v4/sports"


def normalize(value):
    value = unicodedata.normalize("NFKD", str(value or "").lower())
    value = "".join(c for c in value if not unicodedata.combining(c))
    value = value.replace("&", " and ")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    ignored = {"fc", "afc", "cf", "sc", "ac", "club", "football", "the"}
    return " ".join(part for part in value.split() if part not in ignored)


def team_similarity(left, right):
    a, b = normalize(left), normalize(right)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return SequenceMatcher(None, a, b).ratio()


def parse_datetime(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def match_event(fixture, events, threshold=0.90, max_hours=12):
    """Return a unique high-confidence event match; otherwise return None."""
    kickoff = parse_datetime(fixture.get("utcDate"))
    if kickoff is None:
        return None
    ranked = []
    for event in events:
        event_time = parse_datetime(event.get("commence_time"))
        if event_time is None or abs((event_time - kickoff).total_seconds()) > max_hours * 3600:
            continue
        home = team_similarity(fixture.get("home"), event.get("home_team"))
        away = team_similarity(fixture.get("away"), event.get("away_team"))
        if home >= threshold and away >= threshold:
            ranked.append((home + away, event))
    ranked.sort(key=lambda pair: pair[0], reverse=True)
    if not ranked:
        return None
    if len(ranked) > 1 and ranked[0][0] - ranked[1][0] < 0.08:
        return None
    return ranked[0][1]


def extract_h2h(event):
    bookmakers = []
    for bookmaker in event.get("bookmakers", []):
        market = next((m for m in bookmaker.get("markets", []) if m.get("key") == "h2h"), None)
        if not market:
            continue
        outcomes = []
        for outcome in market.get("outcomes", []):
            price = outcome.get("price")
            if isinstance(price, (int, float)) and price > 1:
                outcomes.append({
                    "name": outcome.get("name"),
                    "price": float(price),
                })
        if outcomes:
            bookmakers.append({
                "key": bookmaker.get("key"),
                "title": bookmaker.get("title"),
                "last_update": bookmaker.get("last_update"),
                "outcomes": outcomes,
            })
    return bookmakers


def collect(input_path, output_path, api_key, session=requests):
    data = json.loads(Path(input_path).read_text(encoding="utf-8"))
    competitions = {item.get("code"): item for item in data.get("competitions", [])}
    retrieved_at = datetime.now(timezone.utc).isoformat()
    rows = []
    stats = {}
    quota_remaining = None
    errors = []

    for code, sport in SPORTS.items():
        competition = competitions.get(code)
        if not competition:
            stats[code] = {"eligible": 0, "matched": 0, "with_1x2": 0}
            continue
        fixtures = [
            item for item in competition.get("matches", [])
            if item.get("status") in ("SCHEDULED", "TIMED")
            and item.get("utcDate") and item.get("prediction")
        ]
        try:
            response = session.get(
                f"{API_ROOT}/{sport}/odds",
                params={
                    "apiKey": api_key,
                    "regions": "eu",
                    "markets": "h2h",
                    "oddsFormat": "decimal",
                    "dateFormat": "iso",
                },
                timeout=35,
            )
            quota_remaining = response.headers.get("x-requests-remaining", quota_remaining)
            response.raise_for_status()
            events = response.json()
            matched = 0
            with_odds = 0
            for fixture in fixtures:
                event = match_event(fixture, events)
                if event is None:
                    continue
                matched += 1
                bookmaker_quotes = extract_h2h(event)
                if not bookmaker_quotes:
                    continue
                with_odds += 1
                rows.append({
                    "schema_version": 1,
                    "retrieved_at": retrieved_at,
                    "league_code": code,
                    "fixture_id": str(fixture.get("id")),
                    "kickoff_utc": fixture.get("utcDate"),
                    "home_team": fixture.get("home"),
                    "away_team": fixture.get("away"),
                    "prediction": fixture.get("prediction"),
                    "odds_event_id": event.get("id"),
                    "odds_commence_time": event.get("commence_time"),
                    "bookmakers": bookmaker_quotes,
                })
            stats[code] = {
                "eligible": len(fixtures),
                "matched": matched,
                "with_1x2": with_odds,
            }
        except Exception as exc:
            # Do not include request URLs or parameters, which may contain the key.
            errors.append(f"{code}: {type(exc).__name__}")
            stats[code] = {"eligible": len(fixtures), "matched": 0, "with_1x2": 0, "error": type(exc).__name__}

    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("a", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")

    summary = {
        "retrieved_at": retrieved_at,
        "records_written": len(rows),
        "by_league": stats,
        "requests_remaining": int(quota_remaining) if str(quota_remaining or "").isdigit() else None,
        "errors": errors,
        "archive": str(output),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="docs/data.json")
    parser.add_argument("--output", default="artifacts/odds-snapshots.jsonl")
    args = parser.parse_args()
    key = os.environ.get("ODDS_API_KEY", "").strip()
    if not key:
        print("ODDS_API_KEY is not configured; no API request was made.", file=sys.stderr)
        return 2
    summary = collect(args.input, args.output, key)
    return 1 if summary["errors"] and not summary["records_written"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
