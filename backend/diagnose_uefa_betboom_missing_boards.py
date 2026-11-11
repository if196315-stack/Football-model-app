#!/usr/bin/env python3
"""Read-only exact-ID audit of missing UEFA BetBoom boards (max 15 API calls)."""
import json
import os
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
FEED = ROOT / "docs" / "uefa-data.json"
URL = "https://api.api-sport.ru/v2/football/matches"
LIMIT = 15


def extract(payload):
    if isinstance(payload, dict):
        for key in ("match", "data", "result"):
            if isinstance(payload.get(key), dict):
                return payload[key]
        matches = payload.get("matches")
        if isinstance(matches, list):
            return matches[0] if matches else {}
        return payload
    if isinstance(payload, list):
        return payload[0] if payload else {}
    return {}


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY is missing")
    cache = json.loads(CACHE.read_text(encoding="utf-8"))
    feed = json.loads(FEED.read_text(encoding="utf-8"))
    feed_matches = {
        str(match.get("id")): match
        for comp in feed.get("competitions", [])
        for match in comp.get("matches", [])
    }
    candidates = []
    for fixture_id, fixture in (cache.get("matches") or {}).items():
        if str(fixture.get("leagueCode") or "").upper() not in ("UCL", "UEL", "UECL"):
            continue
        if str(fixture.get("status") or "").casefold() not in ("not_started", "scheduled", "timed"):
            continue
        provider_id = fixture.get("providerEventId")
        if fixture.get("provider") != "API-Sport" or provider_id is None:
            continue
        model = feed_matches.get(str(fixture_id), {})
        if model.get("odds", {}).get("best"):
            continue
        candidates.append((str(fixture_id), fixture, str(provider_id)))
    # Sample evenly by competition, then fill to the hard cap.
    chosen = []
    for league in ("UCL", "UEL", "UECL"):
        chosen.extend([row for row in candidates if row[1].get("leagueCode") == league][:5])
    seen = set()
    chosen = [row for row in chosen if not (row[2] in seen or seen.add(row[2]))][:LIMIT]
    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    results = []
    for fixture_id, fixture, provider_id in chosen:
        response = session.get(
            f"{URL}/{provider_id}",
            params={"with_bk_odds": "true", "odds_stage": "all", "bookmaker_ids": "betboom"},
            timeout=45,
        )
        response.raise_for_status()
        match = extract(response.json())
        flags = match.get("hasBkOdds") or {}
        books = match.get("oddsBk") or {}
        board = books.get("betboom") if isinstance(books, dict) else None
        markets = board.get("markets") or {} if isinstance(board, dict) else {}
        results.append({
            "competition": fixture.get("leagueCode"),
            "fixture_id": fixture_id,
            "provider_event_id": provider_id,
            "model_home": fixture.get("home"),
            "model_away": fixture.get("away"),
            "api_home": (match.get("homeTeam") or {}).get("name"),
            "api_away": (match.get("awayTeam") or {}).get("name"),
            "has_betboom_flag": flags.get("betboom") if isinstance(flags, dict) else None,
            "betboom_board_present": isinstance(board, dict),
            "market_count": len(markets),
            "market_slugs": sorted(markets)[:30],
            "other_bookmakers": sorted(k for k in books if k != "betboom") if isinstance(books, dict) else [],
        })
    summary = {
        "read_only": True,
        "api_calls": len(results),
        "candidate_missing_board_fixtures": len(candidates),
        "sampled": len(results),
        "with_betboom_board": sum(row["betboom_board_present"] for row in results),
        "without_betboom_board": sum(not row["betboom_board_present"] for row in results),
        "results": results,
    }
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
