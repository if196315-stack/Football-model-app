#!/usr/bin/env python3
"""Read-only full upcoming UECL calendar coverage audit against API-Sport."""
from __future__ import annotations
import json, os
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
import requests
from update_odds_api_sport import URL, match_fixture

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
TOURNAMENT_ID = "17015"
LOOKAHEAD_DAYS = 180
WINDOW_DAYS = 31
ACTIVE_STATUSES = {"not_started", "scheduled", "timed"}

def fixture_date(value: Any):
    try:
        return date.fromisoformat(str(value)[:10]) if value else None
    except ValueError:
        return None

def select_uecl_fixtures(cache: dict, today: date, lookahead_days: int = LOOKAHEAD_DAYS):
    end = today + timedelta(days=lookahead_days)
    selected = []
    for fixture_id, fixture in (cache.get("matches") or {}).items():
        if str(fixture.get("leagueCode") or "").upper() != "UECL":
            continue
        if str(fixture.get("status") or "").casefold() not in ACTIVE_STATUSES:
            continue
        kickoff_date = fixture_date(fixture.get("date"))
        if kickoff_date is None or not today <= kickoff_date <= end:
            continue
        selected.append({
            "fixture_id": str(fixture_id), "competition": "UECL",
            "date": kickoff_date.isoformat(), "utcDate": str(fixture.get("date")),
            "home": fixture.get("home"), "away": fixture.get("away"),
            "season": fixture.get("season"),
        })
    return sorted(selected, key=lambda row: (row["date"], row["home"] or "", row["away"] or ""))

def extract_rows(payload: Any):
    if isinstance(payload, list):
        return payload, 1
    if not isinstance(payload, dict):
        return [], 1
    rows = payload.get("matches")
    if not isinstance(rows, list):
        rows = payload.get("data") if isinstance(payload.get("data"), list) else []
    try:
        pages = max(1, min(100, int(payload.get("totalPages") or 1)))
    except (TypeError, ValueError):
        pages = 1
    return rows, pages

def fetch_uecl_fixtures(session: Any, api_key: str, today: date,
                        lookahead_days: int = LOOKAHEAD_DAYS):
    if not api_key:
        raise ValueError("API_SPORT_KEY is missing")
    end, cursor = today + timedelta(days=lookahead_days), today
    by_id, calls = {}, 0
    while cursor <= end:
        window_end = min(cursor + timedelta(days=WINDOW_DAYS - 1), end)
        params = {
            "date_from": cursor.isoformat(), "date_to": window_end.isoformat(),
            "tournament_id": TOURNAMENT_ID, "exclude_amateur": "true",
            "view": "odds", "with_bk_odds": "true", "odds_stage": "all",
            "page_size": 500, "sort": "asc", "page": 1,
        }
        response = session.get(URL, params=params, headers={"Authorization": api_key}, timeout=60)
        response.raise_for_status()
        rows, pages = extract_rows(response.json())
        calls += 1
        for row in rows:
            if row.get("id") is not None:
                by_id[str(row["id"])] = row
        for page in range(2, pages + 1):
            page_params = {**params, "page": page}
            page_response = session.get(URL, params=page_params, headers={"Authorization": api_key}, timeout=60)
            page_response.raise_for_status()
            page_rows, _ = extract_rows(page_response.json())
            calls += 1
            for row in page_rows:
                if row.get("id") is not None:
                    by_id[str(row["id"])] = row
        cursor = window_end + timedelta(days=1)
    return list(by_id.values()), calls

def bookmaker_summary(match: dict):
    books = match.get("oddsBk") or {}
    if not isinstance(books, dict):
        books = {}
    betboom = books.get("betboom")
    markets = (betboom.get("markets") or {}) if isinstance(betboom, dict) else {}
    return {
        "available_bookmakers": sorted(str(key) for key in books),
        "bookmaker_board_count": len(books),
        "betboom_board_present": isinstance(betboom, dict) and bool(markets),
        "betboom_market_count": len(markets) if isinstance(markets, dict) else 0,
        "any_bookmaker_board_present": any(
            isinstance(board, dict) and bool(board.get("markets")) for board in books.values()
        ),
    }

def audit_coverage(cache: dict, provider_fixtures: list[dict], today: date,
                   lookahead_days: int = LOOKAHEAD_DAYS):
    targets = select_uecl_fixtures(cache, today, lookahead_days)
    results = []
    for target in targets:
        provider_match = match_fixture(target, provider_fixtures)
        if provider_match is None:
            results.append({**target, "api_sport_match_found": False})
        else:
            results.append({
                **target, "api_sport_match_found": True,
                "provider_event_id": str(provider_match.get("id")),
                "api_home": (provider_match.get("homeTeam") or {}).get("name"),
                "api_away": (provider_match.get("awayTeam") or {}).get("name"),
                **bookmaker_summary(provider_match),
            })
    matched = [row for row in results if row.get("api_sport_match_found")]
    with_any = [row for row in matched if row.get("any_bookmaker_board_present")]
    with_betboom = [row for row in matched if row.get("betboom_board_present")]
    return {
        "read_only": True, "competition": "UECL", "api_sport_tournament_id": TOURNAMENT_ID,
        "period": {"from": today.isoformat(), "to": (today + timedelta(days=lookahead_days)).isoformat()},
        "pitchapi_upcoming_fixtures": len(targets),
        "api_sport_tournament_fixtures_returned": len(provider_fixtures),
        "matched_fixtures": len(matched), "unmatched_fixtures": len(results) - len(matched),
        "matched_with_any_bookmaker_board": len(with_any),
        "matched_without_any_bookmaker_board": len(matched) - len(with_any),
        "matched_with_betboom_board": len(with_betboom),
        "matched_without_betboom_board": len(matched) - len(with_betboom),
        "results": results,
    }

def main():
    today = datetime.now(timezone.utc).date()
    cache = json.loads(CACHE.read_text(encoding="utf-8"))
    provider, calls = fetch_uecl_fixtures(requests.Session(), os.getenv("API_SPORT_KEY", "").strip(), today)
    report = audit_coverage(cache, provider, today)
    report["date_windows_and_pages_api_calls"] = calls
    print(json.dumps(report, ensure_ascii=False))

if __name__ == "__main__":
    main()
