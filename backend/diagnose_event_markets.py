#!/usr/bin/env python3
"""Probe actual double-chance prices for one upcoming event per league.

Makes at most five event-odds requests. Read-only: does not alter app data or snapshots.
"""
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from update_odds import LEAGUES, convert_event

DATA_FILE = ROOT / "docs" / "data.json"
BASE_URL = "https://api.the-odds-api.com/v4/sports"


def parse_time(value):
    try:
        dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return None


def select_sample(competition, now):
    rows = []
    for match in competition.get("matches", []):
        if match.get("status") not in ("SCHEDULED", "TIMED"):
            continue
        event_id = (match.get("odds") or {}).get("eventId")
        kickoff = parse_time(match.get("utcDate"))
        if event_id and kickoff and kickoff >= now:
            rows.append((kickoff, match, str(event_id)))
    if not rows:
        return None
    kickoff, match, event_id = min(rows, key=lambda row: row[0])
    return kickoff, match, event_id


def main():
    api_key = os.getenv("ODDS_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("ODDS_API_KEY is not configured")
    data = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    competitions = {c.get("code"): c for c in data.get("competitions", [])}
    session = requests.Session()
    calls = 0
    for league, sport in LEAGUES.items():
        selected = select_sample(competitions.get(league) or {}, datetime.now(timezone.utc))
        if not selected:
            print(json.dumps({"league": league, "skipped": "no upcoming fixture with eventId"}))
            continue
        kickoff, match, event_id = selected
        url = f"{BASE_URL}/{sport}/events/{event_id}/odds"
        params = {
            "apiKey": api_key, "regions": "eu", "markets": "double_chance",
            "oddsFormat": "decimal", "dateFormat": "iso",
        }
        try:
            response = session.get(url, params=params, timeout=30)
            calls += 1
            remaining = response.headers.get("x-requests-remaining", "unknown")
            used = response.headers.get("x-requests-used", "unknown")
            if not response.ok:
                print(json.dumps({
                    "league": league, "match": f"{match.get('home')} - {match.get('away')}",
                    "http_status": response.status_code, "response": response.text[:300],
                    "credits_remaining": remaining, "credits_used": used,
                }, ensure_ascii=False))
                continue
            payload = response.json()
            raw = []
            for book in payload.get("bookmakers", []):
                for market in book.get("markets", []):
                    if market.get("key") == "double_chance":
                        raw.append({
                            "bookmaker": book.get("title") or book.get("key"),
                            "outcomes": [
                                {"name": outcome.get("name"), "price": outcome.get("price")}
                                for outcome in market.get("outcomes", [])
                            ],
                        })
            parsed = convert_event(payload).get("best", {}).get("doubleChance", {})
            print(json.dumps({
                "league": league, "match": f"{match.get('home')} - {match.get('away')}",
                "kickoff": kickoff.isoformat(), "bookmakers_with_double_chance": len(raw),
                "raw_market_examples": raw[:5], "parsed_best": parsed,
                "credits_remaining": remaining, "credits_used": used,
            }, ensure_ascii=False))
        except requests.RequestException as exc:
            print(json.dumps({"league": league, "error": str(exc)}, ensure_ascii=False))
    print(f"Probe finished; event-odds requests made: {calls} (maximum 5).")


if __name__ == "__main__":
    main()
