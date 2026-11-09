"""Read-only PitchAPI diagnostic for UEFA club competitions.

Fetches league catalogue and season match listings only. It does not request
shot data, modify docs/data.json, or call any bookmaker/odds provider.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from datetime import datetime, timezone

import requests

BASE_URL = "https://api.pitchapi.dev/v1"
TIMEOUT = 30


def fetch(session: requests.Session, path: str, api_key: str, params=None):
    response = session.get(
        f"{BASE_URL}{path}",
        params=params or {},
        headers={"X-API-KEY": api_key},
        timeout=TIMEOUT,
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict) or "data" not in body or "error" in body:
        raise ValueError(f"Unexpected PitchAPI response for {path}")
    return body["data"]


def season_value(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("name", "season", "year", "label"):
            if value.get(key) is not None:
                return str(value[key])
    return None


def season_sort(value):
    match = re.search(r"(20\d{2})", str(value))
    return int(match.group(1)) if match else 0


def competition_kind(name):
    normalized = re.sub(r"[^a-z]+", " ", str(name).casefold()).strip()
    if "champions league" in normalized and "youth" not in normalized and "women" not in normalized:
        return "UEFA Champions League"
    if "europa league" in normalized and "youth" not in normalized and "women" not in normalized:
        return "UEFA Europa League"
    return None


def main():
    api_key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("PITCHAPI_API_KEY is not configured")

    session = requests.Session()
    catalogue = fetch(session, "/leagues", api_key)
    leagues = catalogue.get("leagues", []) if isinstance(catalogue, dict) else []
    selected = []
    for league in leagues:
        kind = competition_kind(league.get("name"))
        if kind:
            seasons = sorted(
                {s for s in (season_value(x) for x in (league.get("seasons") or [])) if s},
                key=season_sort,
            )
            selected.append({
                "kind": kind,
                "id": league.get("id"),
                "name": league.get("name"),
                "country": league.get("country"),
                "country_code": league.get("country_code"),
                "seasons_available": seasons,
            })

    results = []
    errors = []
    for league in selected:
        seasons = league["seasons_available"][-6:]
        season_results = []
        for season in seasons:
            try:
                payload = fetch(
                    session,
                    f"/leagues/{league['id']}/matches",
                    api_key,
                    {"season": season, "status": "all"},
                )
                rows = payload.get("matches", []) if isinstance(payload, dict) else []
                status_counts = {}
                for row in rows:
                    status = str(row.get("status") or "unknown").casefold()
                    status_counts[status] = status_counts.get(status, 0) + 1
                sample = []
                for row in rows[:8]:
                    home = row.get("home_team") or {}
                    away = row.get("away_team") or {}
                    sample.append({
                        "id": row.get("id"),
                        "date": row.get("date"),
                        "status": row.get("status"),
                        "home": home.get("name"),
                        "away": away.get("name"),
                    })
                season_results.append({
                    "season": season,
                    "match_count": len(rows),
                    "status_counts": status_counts,
                    "sample": sample,
                })
            except Exception as exc:
                errors.append({
                    "league": league.get("name"),
                    "season": season,
                    "error_type": type(exc).__name__,
                    "message": str(exc)[:240],
                })
            time.sleep(0.15)
        results.append({**league, "checked_seasons": season_results})

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "provider": "PitchAPI",
        "scope": "league catalogue and season fixture listings only; no shot/xG or odds calls",
        "catalogue_leagues_total": len(leagues),
        "target_competitions_found": len(selected),
        "competitions": results,
        "errors": errors,
    }
    with open("pitchapi-uefa-diagnostic.json", "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(json.dumps({
        "generated_at": report["generated_at"],
        "catalogue_leagues_total": len(leagues),
        "target_competitions_found": len(selected),
        "competitions": [
            {
                "kind": item["kind"],
                "name": item["name"],
                "id": item["id"],
                "seasons": [
                    {"season": season["season"], "match_count": season["match_count"]}
                    for season in item["checked_seasons"]
                ],
            }
            for item in results
        ],
        "errors": errors,
        "report_file": "pitchapi-uefa-diagnostic.json",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
