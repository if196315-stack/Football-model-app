#!/usr/bin/env python3
"""Read-only PitchAPI-to-API-Sport audit of all bookmaker boards on missing UEFA fixtures (max 30 API calls)."""
import json
import os
from collections import defaultdict
from pathlib import Path

import requests

from update_odds_api_sport import URL, match_fixture

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
FEED = ROOT / "docs" / "uefa-data.json"
LIMIT = 15
LEAGUES = ("UCL", "UEL", "UECL")


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


def feed_matches_by_id(feed):
    return {
        str(match.get("id")): match
        for comp in feed.get("competitions", [])
        for match in comp.get("matches", [])
        if match.get("id") is not None
    }


def select_candidates(cache, feed_matches, limit=LIMIT):
    candidates = []
    for fixture_id, fixture in (cache.get("matches") or {}).items():
        league = str(fixture.get("leagueCode") or "").upper()
        if league not in LEAGUES:
            continue
        if str(fixture.get("status") or "").casefold() not in ("not_started", "scheduled", "timed"):
            continue
        model = feed_matches.get(str(fixture_id))
        if not model or (model.get("odds") or {}).get("best"):
            continue
        date = str(fixture.get("date") or model.get("utcDate") or "")[:10]
        if len(date) != 10:
            continue
        candidates.append({
            "fixture_id": str(fixture_id),
            "competition": league,
            "date": date,
            "home": fixture.get("home") or model.get("home"),
            "away": fixture.get("away") or model.get("away"),
        })
    chosen = []
    for league in LEAGUES:
        chosen.extend([row for row in candidates if row["competition"] == league][:5])
    return chosen[:limit]


def fetch_date_fixtures(session, api_key, date):
    params = {
        "date_from": date, "date_to": date,
        "exclude_amateur": "true", "view": "odds",
        "with_bk_odds": "true", "odds_stage": "all",
        "bookmaker_ids": "betboom", "page_size": 500,
        "sort": "asc", "page": 1,
    }
    response = session.get(URL, params=params, headers={"Authorization": api_key}, timeout=60)
    response.raise_for_status()
    payload = response.json()
    rows = list(payload) if isinstance(payload, list) else list(payload.get("matches") or [])
    return rows


def inspect_exact_match(session, api_key, candidate, provider_match):
    provider_id = str(provider_match.get("id"))
    response = session.get(
        f"{URL}/{provider_id}",
        params={"with_bk_odds": "true", "odds_stage": "all"},
        headers={"Authorization": api_key, "Accept": "application/json"},
        timeout=45,
    )
    response.raise_for_status()
    match = extract(response.json())
    flags = match.get("hasBkOdds") or {}
    books = match.get("oddsBk") or {}
    board = books.get("betboom") if isinstance(books, dict) else None
    markets = (board.get("markets") or {}) if isinstance(board, dict) else {}
    return {
        **candidate,
        "provider_event_id": provider_id,
        "api_home": (match.get("homeTeam") or {}).get("name"),
        "api_away": (match.get("awayTeam") or {}).get("name"),
        "has_betboom_flag": flags.get("betboom") if isinstance(flags, dict) else None,
        "betboom_board_present": isinstance(board, dict),
        "market_count": len(markets),
        "market_slugs": sorted(markets)[:30],
        "available_bookmakers": sorted(books) if isinstance(books, dict) else [],
        "bookmaker_board_count": len(books) if isinstance(books, dict) else 0,
    }


def run_diagnostic(cache, feed, api_key, session=None):
    if not api_key:
        raise ValueError("API_SPORT_KEY is missing")
    session = session or requests.Session()
    candidates = select_candidates(cache, feed_matches_by_id(feed))
    by_date = defaultdict(list)
    for candidate in candidates:
        by_date[candidate["date"]].append(candidate)

    date_fixtures = {}
    for date in list(by_date)[:LIMIT]:
        date_fixtures[date] = fetch_date_fixtures(session, api_key, date)

    results = []
    for date, date_candidates in by_date.items():
        fixtures = date_fixtures.get(date, [])
        for candidate in date_candidates:
            target = {"utcDate": date, "home": candidate["home"], "away": candidate["away"]}
            provider_match = match_fixture(target, fixtures)
            if provider_match is None:
                results.append({**candidate, "provider_match_found": False})
                continue
            results.append({
                **inspect_exact_match(session, api_key, candidate, provider_match),
                "provider_match_found": True,
            })

    date_calls = len(date_fixtures)
    exact_calls = sum(1 for row in results if row.get("provider_match_found"))
    return {
        "read_only": True,
        "api_calls": date_calls + exact_calls,
        "date_lookup_calls": date_calls,
        "exact_fixture_calls": exact_calls,
        "candidate_missing_board_fixtures": len(candidates),
        "sampled": len(results),
        "provider_matches_found": sum(bool(row.get("provider_match_found")) for row in results),
        "with_betboom_board": sum(bool(row.get("betboom_board_present")) for row in results),
        "without_betboom_board": sum(row.get("provider_match_found") and not row.get("betboom_board_present") for row in results),
        "results": results,
    }


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    cache = json.loads(CACHE.read_text(encoding="utf-8"))
    feed = json.loads(FEED.read_text(encoding="utf-8"))
    print(json.dumps(run_diagnostic(cache, feed, key), ensure_ascii=False))


if __name__ == "__main__":
    main()
