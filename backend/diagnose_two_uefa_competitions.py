"""Read-only catalogue and upcoming-fixture discovery for two additional UEFA competitions."""
from __future__ import annotations

import json
import os
import re
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from typing import Any

import requests

ROOT_URL = "https://api.pitchapi.dev/v1"
API_SPORT_URL = "https://api.api-sport.ru/v2/football/matches"
TIMEOUT = 40
TARGETS = {
    "UNL": {"uefa nations league", "nations league"},
    "UECL": {"uefa conference league", "uefa europa conference league", "europa conference league", "conference league"},
}


def norm(value: Any) -> str:
    return " ".join(re.sub(r"[^a-z]+", " ", str(value or "").casefold()).split())


def discover_pitchapi_competitions(rows: list[dict]) -> dict[str, dict]:
    found: dict[str, dict] = {}
    for row in rows:
        name = norm(row.get("name"))
        for code, aliases in TARGETS.items():
            if name in aliases:
                if code in found:
                    raise ValueError(f"Multiple PitchAPI catalogue entries match {code}")
                found[code] = row
    return found


def matching_catalogue_names(rows: list[dict]) -> list[str]:
    return sorted({
        str(row.get("name")) for row in rows
        if any(token in norm(row.get("name")) for token in ("nation", "conference"))
    })


def search_entities(payload: Any) -> list[dict]:
    found = []
    def visit(value):
        if isinstance(value, dict):
            name = value.get("name") or value.get("title")
            if name and any(token in norm(name) for token in ("nation", "conference")):
                found.append(value)
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(payload)
    unique = {}
    for row in found:
        key = (str(row.get("id") or row.get("tournamentId") or ""),
               str(row.get("name") or row.get("title") or ""))
        unique[key] = row
    return list(unique.values())[:20]


def response_items(payload: Any) -> list[dict]:
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)]
    if isinstance(payload, dict):
        for key in ("matches", "data", "items", "results"):
            if isinstance(payload.get(key), list):
                return [row for row in payload[key] if isinstance(row, dict)]
    return []


def fixture_target(raw: dict, code: str) -> dict | None:
    match_id = raw.get("id")
    home = (raw.get("home_team") or {}).get("name")
    away = (raw.get("away_team") or {}).get("name")
    kickoff = raw.get("date")
    if match_id is None or not home or not away or not kickoff:
        return None
    return {"competition": code, "id": str(match_id), "date": str(kickoff)[:10],
            "utcDate": str(kickoff), "home": home, "away": away}


def main() -> int:
    pitch_key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    sport_key = os.environ.get("API_SPORT_KEY", "").strip()
    if not pitch_key or not sport_key:
        raise SystemExit("Both PITCHAPI_API_KEY and API_SPORT_KEY are required; no request was made.")

    session = requests.Session()
    catalog_response = session.get(
        f"{ROOT_URL}/leagues", headers={"X-API-KEY": pitch_key}, timeout=TIMEOUT
    )
    catalog_response.raise_for_status()
    catalog_body = catalog_response.json()
    if not isinstance(catalog_body, dict) or not isinstance(catalog_body.get("data"), dict):
        raise ValueError("Unexpected PitchAPI /leagues response")
    catalog = catalog_body["data"].get("leagues") or []
    competitions = discover_pitchapi_competitions(catalog)
    api_sport_search = {}
    for query in ("UEFA Nations League", "UEFA Conference League"):
        try:
            search_response = session.get(
                "https://api.api-sport.ru/v2/football/search",
                params={"q": query, "type": "tournament", "limit": 10},
                headers={"Authorization": sport_key, "Accept": "application/json"},
                timeout=TIMEOUT,
            )
            search_response.raise_for_status()
            api_sport_search[query] = search_entities(search_response.json())
        except Exception as exc:
            api_sport_search[query] = {"error": f"{type(exc).__name__}: {str(exc)[:160]}"}

    report: dict[str, Any] = {
        "provider": "PitchAPI + API-Sport",
        "pitchapi_catalogue_candidates": matching_catalogue_names(catalog),
        "api_sport_tournament_search": api_sport_search,
        "competitions": {},
    }
    all_targets = []
    today = datetime.now(timezone.utc).date()
    for code in sorted(TARGETS):
        league = competitions.get(code)
        if not league:
            report["competitions"][code] = {"catalogue_found": False, "fixtures": 0}
            continue
        seasons = sorted(
            [str(item.get("name") if isinstance(item, dict) else item)
             for item in (league.get("seasons") or []) if item is not None]
        )
        latest_season = seasons[-1] if seasons else None
        if not latest_season:
            report["competitions"][code] = {
                "catalogue_found": True, "pitchapi_league_id": league.get("id"),
                "pitchapi_name": league.get("name"), "seasons": [], "fixtures": 0,
            }
            continue
        response = session.get(
            f"{ROOT_URL}/leagues/{league['id']}/matches",
            params={"season": latest_season, "status": "all"},
            headers={"X-API-KEY": pitch_key}, timeout=TIMEOUT,
        )
        response.raise_for_status()
        body = response.json()
        data = body.get("data") if isinstance(body, dict) else None
        rows = data.get("matches", []) if isinstance(data, dict) else []
        upcoming = []
        for raw in rows:
            target = fixture_target(raw, code)
            if not target:
                continue
            try:
                kickoff = datetime.fromisoformat(target["utcDate"].replace("Z", "+00:00"))
                if kickoff.tzinfo is None:
                    kickoff = kickoff.replace(tzinfo=timezone.utc)
            except ValueError:
                continue
            if kickoff.date() >= today and kickoff.date() <= today + timedelta(days=30):
                upcoming.append(target)
        all_targets.extend(upcoming)
        report["competitions"][code] = {
            "catalogue_found": True, "pitchapi_league_id": league.get("id"),
            "pitchapi_name": league.get("name"), "latest_season": latest_season,
            "seasons_available": len(seasons), "upcoming_fixtures_30d": len(upcoming),
            "fixture_sample": upcoming[:5],
        }

    by_date: dict[str, list[dict]] = defaultdict(list)
    for target in all_targets:
        by_date[target["date"]].append(target)
    from update_odds_api_sport import match_fixture, parse_odds

    matched_by_code = defaultdict(int)
    boards_by_code = defaultdict(int)
    prematch_by_code = defaultdict(int)
    parsed_by_code = defaultdict(int)
    for match_date, targets in sorted(by_date.items()):
        response = session.get(
            API_SPORT_URL,
            params={"date_from": match_date, "date_to": match_date,
                    "exclude_amateur": "true", "page": 1, "page_size": 500, "sort": "asc"},
            headers={"Authorization": sport_key, "Accept": "application/json"},
            timeout=TIMEOUT,
        )
        response.raise_for_status()
        candidates = response_items(response.json())
        for target in targets:
            source = match_fixture(target, candidates)
            if not source:
                continue
            code = target["competition"]
            matched_by_code[code] += 1
            fixture_id = source.get("id")
            detail = session.get(
                f"{API_SPORT_URL}/{fixture_id}", params={"with_bk_odds": "true"},
                headers={"Authorization": sport_key, "Accept": "application/json"},
                timeout=TIMEOUT,
            )
            detail.raise_for_status()
            payload = detail.json()
            match = payload.get("match") or payload.get("data") or payload.get("result") or payload
            board = ((match.get("oddsBk") or {}).get("betboom") or {})
            if board:
                boards_by_code[code] += 1
            if (board.get("isBettingActiveByStage") or {}).get("prematch") is True:
                prematch_by_code[code] += 1
            parsed = parse_odds(match).get("best") or {}
            if any(parsed.get(key) for key in ("doubleChance", "totals", "teamTotals", "btts")):
                parsed_by_code[code] += 1

    for code, item in report["competitions"].items():
        item["api_sport_fixture_matches_30d"] = matched_by_code[code]
        item["betboom_boards_30d"] = boards_by_code[code]
        item["active_prematch_boards_30d"] = prematch_by_code[code]
        item["production_parser_quotes_30d"] = parsed_by_code[code]
    report["read_only"] = True
    report["fixture_count_30d"] = len(all_targets)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
