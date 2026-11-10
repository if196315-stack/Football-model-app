"""API-Football lineup adapter. PitchAPI fixture IDs are never sent to this API."""
from __future__ import annotations

import re
import time
import unicodedata
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from typing import Any

import requests

BASE_URL = "https://v3.football.api-sports.io"
TIMEOUT = 25
FIXTURE_CACHE_TTL = timedelta(hours=20)
LINEUP_RETRY = timedelta(minutes=15)


def _utc(value: Any) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return None


def _team_key(value: Any) -> str:
    raw = unicodedata.normalize("NFKD", str(value or "").casefold())
    raw = "".join(ch for ch in raw if not unicodedata.combining(ch))
    return " ".join(re.sub(r"[^a-z0-9]+", " ", raw).split())


def _api_get(session: Any, path: str, api_key: str, params: dict | None = None) -> list:
    response = session.get(
        f"{BASE_URL}{path}", params=params or {},
        headers={"x-apisports-key": api_key}, timeout=TIMEOUT,
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict) or body.get("errors"):
        raise ValueError(f"Unexpected API-Football response for {path}")
    rows = body.get("response")
    if not isinstance(rows, list):
        raise ValueError(f"Missing response list for {path}")
    return rows


def _normalize_side(raw: dict) -> dict:
    def players(field: str) -> list[dict]:
        result = []
        for item in raw.get(field) or []:
            player = item.get("player") if isinstance(item, dict) else None
            if not isinstance(player, dict) or not player.get("name"):
                continue
            result.append({
                "id": player.get("id"),
                "name": str(player["name"]),
                "number": player.get("number"),
                "position": player.get("pos"),
                "grid": player.get("grid"),
                "captain": False,
            })
        return result

    coach = raw.get("coach")
    return {
        "formation": raw.get("formation"),
        "confirmed": len(players("startXI")) == 11,
        "lineupType": "official",
        "coach": coach.get("name") if isinstance(coach, dict) else None,
        "starters": players("startXI"),
        "subs": players("substitutes"),
    }


def normalize_lineups(payload: Any, home_team: str | None = None, away_team: str | None = None) -> dict | None:
    if not isinstance(payload, list) or len(payload) < 2:
        return None
    by_side = {}
    for side in payload:
        if not isinstance(side, dict):
            continue
        team = side.get("team") or {}
        name = _team_key(team.get("name"))
        if name:
            by_side[name] = _normalize_side(side)
    if len(by_side) != 2:
        return None
    if home_team and away_team:
        home_side, away_side = by_side.get(_team_key(home_team)), by_side.get(_team_key(away_team))
        if home_side is None or away_side is None:
            return None
        sides = [home_side, away_side]
    else:
        sides = list(by_side.values())
    if not all(len(side["starters"]) == 11 for side in sides):
        return None
    return {"source": "API-Football", "home": sides[0], "away": sides[1]}


def _fixture_match(row: dict, fixtures: list[dict]) -> dict | None:
    kickoff = _utc(row.get("timeUtc") or row.get("date"))
    if kickoff is None:
        return None
    home, away = _team_key(row.get("home")), _team_key(row.get("away"))
    candidates = []
    for fixture in fixtures:
        if not isinstance(fixture, dict):
            continue
        fixture_data = fixture.get("fixture") or {}
        teams = fixture.get("teams") or {}
        home_data, away_data = teams.get("home") or {}, teams.get("away") or {}
        fixture_time = _utc(fixture_data.get("date"))
        if fixture_time is None or abs((fixture_time - kickoff).total_seconds()) > 15 * 60:
            continue
        hname, aname = _team_key(home_data.get("name")), _team_key(away_data.get("name"))
        home_score = SequenceMatcher(None, home, hname).ratio()
        away_score = SequenceMatcher(None, away, aname).ratio()
        if home_score >= 0.90 and away_score >= 0.90:
            candidates.append((home_score + away_score, fixture))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    if len(candidates) > 1 and candidates[0][0] - candidates[1][0] < 0.02:
        return None
    return candidates[0][1]


def refresh_lineups(cache: dict, api_key: str, session: Any = requests,
                    now: datetime | None = None, pause_seconds: float = 0.12,
                    horizon_hours: int = 48, max_lineup_requests: int = 10) -> dict:
    """Map cached PitchAPI matches to API-Football fixture IDs, then fetch official XIs.

    One /fixtures?date= call is cached per UTC date for 20 hours. Lineups are
    checked only from 55 minutes before kickoff to 30 minutes after kickoff,
    with a 15-minute retry cooldown and a per-run request cap.
    """
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    current = current.astimezone(timezone.utc)
    cutoff = current + timedelta(hours=max(0, horizon_hours))
    date_fixtures = cache.setdefault("footballApiFixturesByDate", {})
    attempted = fetched = errors = mapping_calls = 0
    mapping_errors = 0
    upcoming = []
    for match_id, row in cache.get("matches", {}).items():
        if row.get("status") not in {"scheduled", "upcoming", "not_started"}:
            continue
        kickoff = _utc(row.get("timeUtc") or row.get("date"))
        if kickoff is None or kickoff < current - timedelta(minutes=30) or kickoff > cutoff:
            continue
        upcoming.append((match_id, row, kickoff))

    dates = sorted({kickoff.date().isoformat() for _, _, kickoff in upcoming})
    for day in dates:
        cached = date_fixtures.get(day)
        cached_at = _utc(cached.get("fetchedAt")) if isinstance(cached, dict) else None
        if cached_at and current - cached_at < FIXTURE_CACHE_TTL:
            continue
        try:
            rows = _api_get(session, "/fixtures", api_key, {"date": day})
            date_fixtures[day] = {"fetchedAt": current.isoformat(), "fixtures": rows}
            mapping_calls += 1
        except (requests.RequestException, ValueError, TypeError) as exc:
            mapping_errors += 1
            date_fixtures[day] = {
                "fetchedAt": current.isoformat(), "fixtures": [],
                "fetchError": type(exc).__name__,
            }
        if pause_seconds:
            time.sleep(pause_seconds)

    for match_id, row, kickoff in upcoming:
        day = kickoff.date().isoformat()
        fixtures = (date_fixtures.get(day) or {}).get("fixtures") or []
        mapped = row.get("footballApiFixtureId")
        if mapped is None:
            match = _fixture_match(row, fixtures)
            if match is None:
                continue
            mapped = (match.get("fixture") or {}).get("id")
            if mapped is None:
                continue
            row["footballApiFixtureId"] = int(mapped)
            row["footballApiFixtureMappedAt"] = current.isoformat()

        # API-Football normally publishes XIs 20–40 minutes before kickoff.
        if kickoff - current > timedelta(minutes=55) or current - kickoff > timedelta(minutes=30):
            continue
        existing = row.get("lineups")
        if isinstance(existing, dict) and existing.get("source") == "API-Football":
            if all((existing.get(side) or {}).get("confirmed") is True for side in ("home", "away")):
                continue
        attempted_at = _utc(row.get("lineupsAttemptedAt"))
        if attempted_at and current - attempted_at < LINEUP_RETRY:
            continue
        if attempted >= max(0, int(max_lineup_requests)):
            break
        attempted += 1
        try:
            payload = _api_get(session, "/fixtures/lineups", api_key, {"fixture": mapped})
            lineups = normalize_lineups(payload, row.get("home"), row.get("away"))
            if lineups is not None:
                row["lineups"] = lineups
                row.pop("predictedLineups", None)
                row["lineupsFetchedAt"] = current.isoformat()
                row.pop("lineupsFetchError", None)
                fetched += 1
            else:
                row["lineupsFetchError"] = "lineups_not_published_or_incomplete"
            row["lineupsAttemptedAt"] = current.isoformat()
        except (requests.RequestException, ValueError, TypeError) as exc:
            row["lineupsFetchError"] = type(exc).__name__
            row["lineupsAttemptedAt"] = current.isoformat()
            errors += 1
        if pause_seconds:
            time.sleep(pause_seconds)

    return {
        "lineupAttempts": attempted, "lineupsFetched": fetched,
        "lineupErrors": errors, "fixtureDateLookups": mapping_calls,
        "fixtureDateLookupErrors": mapping_errors,
    }
