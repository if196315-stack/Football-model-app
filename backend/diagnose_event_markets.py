#!/usr/bin/env python3
"""Inspect available bookmaker markets for one upcoming event in each league.

Makes at most five GET /events/{eventId}/markets calls (one per league).
Does not fetch odds, change docs/data.json, or append snapshots.
"""
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
DATA_FILE = ROOT / "docs" / "data.json"
BASE_URL = "https://api.the-odds-api.com/v4/sports"
LEAGUES = {
    "PL": "soccer_epl",
    "PD": "soccer_spain_la_liga",
    "SA": "soccer_italy_serie_a",
    "BL1": "soccer_germany_bundesliga",
    "FL1": "soccer_france_ligue_one",
}


def parse_kickoff(value):
    try:
        dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return None


def select_sample(competition, now):
    candidates = []
    for match in competition.get("matches", []):
        if match.get("status") not in ("SCHEDULED", "TIMED"):
            continue
        odds = match.get("odds") or {}
        event_id = odds.get("eventId")
        kickoff = parse_kickoff(match.get("utcDate"))
        if event_id and kickoff and kickoff >= now:
            candidates.append((kickoff, match, event_id))
    if not candidates:
        return None
    candidates.sort(key=lambda row: row[0])
    kickoff, match, event_id = candidates[0]
    return {
        "kickoff": kickoff.isoformat(),
        "home": match.get("home"),
        "away": match.get("away"),
        "event_id": str(event_id),
    }


def main():
    api_key = os.getenv("ODDS_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("ODDS_API_KEY is not configured")
    data = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    competitions = {c.get("code"): c for c in data.get("competitions", [])}
    session = requests.Session()
    total_calls = 0
    for league, sport in LEAGUES.items():
        competition = competitions.get(league)
        sample = select_sample(competition or {}, datetime.now(timezone.utc))
        if not sample:
            print(f"{league}: no upcoming fixture with a stored event ID; skipped")
            continue
        url = f"{BASE_URL}/{sport}/events/{sample['event_id']}/markets"
        try:
            response = session.get(
                url, params={"apiKey": api_key, "regions": "eu", "dateFormat": "iso"},
                timeout=30,
            )
            total_calls += 1
            remaining = response.headers.get("x-requests-remaining", "unknown")
            used = response.headers.get("x-requests-used", "unknown")
            if not response.ok:
                print(f"{league}: HTTP {response.status_code}; remaining={remaining}; {response.text[:240]}")
                continue
            payload = response.json()
            books = []
            all_keys = set()
            for book in payload.get("bookmakers", []):
                keys = sorted({m.get("key") for m in book.get("markets", []) if m.get("key")})
                all_keys.update(keys)
                books.append({"bookmaker": book.get("title") or book.get("key"), "markets": keys})
            print(json.dumps({
                "league": league, "match": f"{sample['home']} - {sample['away']}",
                "kickoff": sample["kickoff"], "bookmakers_count": len(books),
                "available_market_keys": sorted(all_keys), "bookmakers": books,
                "credits_remaining": remaining, "credits_used": used,
            }, ensure_ascii=False))
        except requests.RequestException as exc:
            print(f"{league}: request failed: {exc}")
    print(f"Diagnostic finished; event-markets requests made: {total_calls} (maximum 5).")


if __name__ == "__main__":
    main()
