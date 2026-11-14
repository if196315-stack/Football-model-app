"""Refresh UEFA Nations League fixtures/results from API-Sport into the isolated UEFA cache."""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

from refresh_pitchapi_uefa_fixtures import CACHE_PATH, load_cache, save_cache

URL = "https://api.api-sport.ru/v2/football/matches"
TOURNAMENT_ID = 10783
LOOKBACK_DAYS = 10
LOOKAHEAD_DAYS = 180
WINDOW_DAYS = 31
TIMEOUT = 45


def _rows(payload: Any) -> list[dict]:
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)]
    if isinstance(payload, dict):
        return [row for row in (payload.get("matches") or []) if isinstance(row, dict)]
    return []


def _score(value: Any) -> int | None:
    if not isinstance(value, dict):
        return None
    current = value.get("current")
    return current if isinstance(current, int) and not isinstance(current, bool) else None


def normalize_match(raw: dict) -> dict | None:
    match_id = raw.get("id")
    home = raw.get("homeTeam") or {}
    away = raw.get("awayTeam") or {}
    if match_id is None or not home.get("name") or not away.get("name"):
        return None
    start_ms = raw.get("startTimestamp")
    try:
        kickoff = datetime.fromtimestamp(int(start_ms) / 1000, tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OverflowError, OSError):
        kickoff = raw.get("dateEvent")
    if not kickoff:
        return None
    raw_status = str(raw.get("status") or "unknown").casefold()
    status = {"notstarted": "not_started", "inprogress": "in_progress"}.get(raw_status, raw_status)
    tournament = raw.get("tournament") or {}
    season = raw.get("season") or {}
    return {
        "id": f"UNL:{match_id}",
        "provider": "API-Sport",
        "providerEventId": str(match_id),
        "leagueCode": "UNL",
        "leagueId": tournament.get("id", TOURNAMENT_ID),
        "leagueName": tournament.get("name", "UEFA Nations League"),
        "season": season.get("name") or season.get("year") or season.get("id"),
        "date": kickoff,
        "status": status,
        "homeTeamId": home.get("id"),
        "home": home.get("name"),
        "awayTeamId": away.get("id"),
        "away": away.get("name"),
        "homeScore": _score(raw.get("homeScore")),
        "awayScore": _score(raw.get("awayScore")),
        "roundName": (raw.get("roundInfo") or {}).get("name"),
    }


def fetch_matches(api_key: str, session: Any = requests,
                  today: date | None = None) -> list[dict]:
    if not api_key:
        raise ValueError("API_SPORT_KEY is not configured")
    start = (today or datetime.now(timezone.utc).date()) - timedelta(days=LOOKBACK_DAYS)
    final_date = (today or datetime.now(timezone.utc).date()) + timedelta(days=LOOKAHEAD_DAYS)
    matches: dict[str, dict] = {}
    cursor = start
    while cursor <= final_date:
        window_end = min(cursor + timedelta(days=WINDOW_DAYS - 1), final_date)
        params = {
            "tournament_id": str(TOURNAMENT_ID),
            "date_from": cursor.isoformat(),
            "date_to": window_end.isoformat(),
            "exclude_amateur": "true",
            "fields": "id,status,dateEvent,startTimestamp,tournament,season,homeTeam,awayTeam,homeScore,awayScore,roundInfo",
            "page": 1,
            "page_size": 500,
            "sort": "asc",
        }
        response = session.get(URL, params=params,
                               headers={"Authorization": api_key, "Accept": "application/json"},
                               timeout=TIMEOUT)
        response.raise_for_status()
        for raw in _rows(response.json()):
            row = normalize_match(raw)
            if row:
                matches[row["id"]] = row
        cursor = window_end + timedelta(days=1)
    return list(matches.values())


def refresh_unl_fixtures(api_key: str, session: Any = requests,
                         cache_path: Path = CACHE_PATH,
                         today: date | None = None) -> dict:
    if not api_key:
        raise ValueError("API_SPORT_KEY is not configured")
    cache = load_cache(cache_path)
    matches = cache["matches"]
    # Replace only API-Sport Nations League rows; preserve all PitchAPI UEFA history.
    for key in [key for key, row in matches.items()
                if row.get("leagueCode") == "UNL" and row.get("provider") == "API-Sport"]:
        del matches[key]
    refreshed = fetch_matches(api_key, session=session, today=today)
    for row in refreshed:
        matches[row["id"]] = row
    cache["competitions"]["UNL"] = {
        "id": TOURNAMENT_ID,
        "name": "UEFA Nations League",
        "provider": "API-Sport",
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "lookbackDays": LOOKBACK_DAYS,
        "lookaheadDays": LOOKAHEAD_DAYS,
        "matches": len(refreshed),
    }
    cache["updatedAt"] = datetime.now(timezone.utc).isoformat()
    cache["lastAdditionalUefaRefresh"] = {
        "competition": "UNL", "matches": len(refreshed), "provider": "API-Sport",
    }
    save_cache(cache, cache_path)
    summary = {"competition": "UNL", "tournament_id": TOURNAMENT_ID,
               "matches_refreshed": len(refreshed), "cache_matches": len(matches)}
    print(json.dumps(summary, ensure_ascii=False))
    return {"cache": cache, "summary": summary}


def main() -> int:
    refresh_unl_fixtures(os.environ.get("API_SPORT_KEY", "").strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
