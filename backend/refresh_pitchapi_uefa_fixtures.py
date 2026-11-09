"""Incremental, fixture-only history cache for UEFA Champions League and Europa League.

This job is deliberately isolated from the production model and docs/data.json.
It does not request shot/xG endpoints or bookmaker odds.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

BASE_URL = "https://api.pitchapi.dev/v1"
ROOT = Path(__file__).resolve().parents[1]
CACHE_PATH = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
REPORT_PATH = ROOT / "pitchapi-uefa-history-report.json"
TIMEOUT = 30
TARGETS = {
    "UCL": "champions league",
    "UEL": "europa league",
}


def fetch(session: Any, path: str, api_key: str, params: dict | None = None) -> Any:
    retryable = {429, 500, 502, 503, 504, 520, 521, 522, 523, 524}
    for attempt in range(4):
        try:
            response = session.get(
                f"{BASE_URL}{path}", params=params or {},
                headers={"X-API-KEY": api_key}, timeout=TIMEOUT,
            )
            if response.status_code in retryable and attempt < 3:
                retry_after = response.headers.get("Retry-After")
                try:
                    delay = min(60, max(1, int(retry_after))) if retry_after else 2 ** attempt
                except (TypeError, ValueError):
                    delay = 2 ** attempt
                time.sleep(delay)
                continue
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "data" not in body or "error" in body:
                raise ValueError(f"Unexpected PitchAPI response for {path}")
            return body["data"]
        except requests.RequestException:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError(f"PitchAPI retry loop exhausted for {path}")


def season_value(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for field in ("name", "season", "year", "label"):
            if value.get(field) is not None:
                return str(value[field])
    return None


def season_sort(value: str) -> int:
    match = re.search(r"(20\d{2})", value)
    return int(match.group(1)) if match else 0


def competition_code(name: Any) -> str | None:
    normalized = re.sub(r"[^a-z]+", " ", str(name or "").casefold()).strip()
    if normalized.startswith("uefa "):
        normalized = normalized[5:]
    if any(token in normalized for token in ("women", "youth", " u17", " u18", " u19", " u20", " u21")):
        return None
    for code, expected in TARGETS.items():
        if normalized == expected:
            return code
    return None


def discover_competitions(session: Any, api_key: str) -> dict[str, dict]:
    payload = fetch(session, "/leagues", api_key)
    rows = payload.get("leagues", []) if isinstance(payload, dict) else []
    found: dict[str, dict] = {}
    for league in rows:
        code = competition_code(league.get("name"))
        if not code:
            continue
        if code in found:
            raise RuntimeError(f"Multiple PitchAPI catalogue entries found for {code}")
        found[code] = league
    missing = sorted(set(TARGETS) - set(found))
    if missing:
        raise RuntimeError("PitchAPI UEFA competitions missing: " + ", ".join(missing))
    return found


def load_cache(path: Path = CACHE_PATH) -> dict:
    if not path.exists():
        return {
            "version": 1, "provider": "PitchAPI",
            "scope": "UEFA fixtures/results only; no xG or odds",
            "competitions": {}, "matches": {},
        }
    cache = json.loads(path.read_text(encoding="utf-8"))
    if (cache.get("provider") != "PitchAPI"
            or not isinstance(cache.get("competitions"), dict)
            or not isinstance(cache.get("matches"), dict)):
        raise ValueError("UEFA fixture cache has an unexpected schema")
    return cache


def save_cache(cache: dict, path: Path = CACHE_PATH) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def normalize_match(raw: dict, code: str, league: dict, season: str) -> dict | None:
    match_id = raw.get("id")
    if match_id is None:
        return None
    home = raw.get("home_team") or {}
    away = raw.get("away_team") or {}
    return {
        "id": str(match_id), "provider": "PitchAPI",
        "leagueCode": code, "leagueId": league.get("id"),
        "leagueName": league.get("name"), "season": season,
        "date": raw.get("date"),
        "status": str(raw.get("status") or "unknown").casefold(),
        "homeTeamId": home.get("id"), "home": home.get("name") or "TBD",
        "awayTeamId": away.get("id"), "away": away.get("name") or "TBD",
        "homeScore": raw.get("score_home"), "awayScore": raw.get("score_away"),
        "roundName": raw.get("round_name"), "hasPlayoff": raw.get("has_playoff"),
    }


def refresh_uefa_fixtures(
    api_key: str,
    session: Any = requests,
    cache_path: Path = CACHE_PATH,
    report_path: Path = REPORT_PATH,
    pause_seconds: float = 0.12,
    upcoming_only: bool = False,
) -> dict:
    """Refresh the latest six available seasons without requesting shot data."""
    if not api_key:
        raise ValueError("PITCHAPI_API_KEY is not configured")
    cache = load_cache(cache_path)
    stored = cache["matches"]
    leagues = discover_competitions(session, api_key)
    results = []
    errors = []
    listed_total = 0

    for code in sorted(leagues):
        league = leagues[code]
        seasons = sorted(
            {value for value in (season_value(x) for x in (league.get("seasons") or [])) if value},
            key=season_sort,
        )[-1:] if upcoming_only else sorted(
            {value for value in (season_value(x) for x in (league.get("seasons") or [])) if value},
            key=season_sort,
        )[-6:]
        season_results = []
        for season in seasons:
            try:
                payload = fetch(
                    session, f"/leagues/{league['id']}/matches", api_key,
                    {"season": season, "status": "all"},
                )
                rows = payload.get("matches", []) if isinstance(payload, dict) else []
                counts = Counter()
                for raw in rows:
                    counts[str(raw.get("status") or "unknown").casefold()] += 1
                    row = normalize_match(raw, code, league, season)
                    if row is None:
                        continue
                    previous = stored.get(row["id"], {})
                    # This cache is fixture-only. Preserve no xG fields from other caches.
                    stored[row["id"]] = row
                    listed_total += 1
                season_results.append({
                    "season": season, "matchCount": len(rows),
                    "statusCounts": dict(sorted(counts.items())),
                })
            except Exception as exc:
                errors.append({
                    "competition": code, "season": season,
                    "errorType": type(exc).__name__, "message": str(exc)[:240],
                })
            if pause_seconds:
                time.sleep(pause_seconds)
        cache["competitions"][code] = {
            "id": league.get("id"), "name": league.get("name"),
            "countryCode": league.get("country_code"),
            "seasons": seasons, "checkedSeasons": season_results,
        }
        results.append({
            "code": code, "id": league.get("id"), "name": league.get("name"),
            "seasons": season_results,
        })

    cache["updatedAt"] = datetime.now(timezone.utc).isoformat()
    cache["lastRefreshSummary"] = {
        "listedMatches": listed_total, "cachedMatches": len(stored),
        "errors": len(errors),
    }
    save_cache(cache, cache_path)

    report = {
        "generatedAt": cache["updatedAt"],
        "provider": "PitchAPI",
        "scope": "UEFA Champions League and Europa League fixtures/results only; no xG or odds calls",
        "upcomingSeasonOnly": upcoming_only,
        "listedMatchesThisRun": listed_total,
        "cachedMatches": len(stored),
        "errors": errors,
        "competitions": results,
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if errors:
        raise RuntimeError(f"UEFA fixture refresh completed with {len(errors)} season errors; report saved")
    return {"cache": cache, "report": report}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upcoming-only", action="store_true",
                        help="refresh only the latest available season to reduce scheduled API usage")
    args = parser.parse_args()
    result = refresh_uefa_fixtures(
        os.environ.get("PITCHAPI_API_KEY", "").strip(),
        upcoming_only=args.upcoming_only,
    )
    print(json.dumps(result["cache"]["lastRefreshSummary"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
