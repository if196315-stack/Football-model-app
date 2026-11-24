"""Isolated, read-only PitchAPI audit for Australia's A-League Men.

Uses the existing PITCHAPI_API_KEY secret. Reads league catalogue, season
fixtures and a small evenly spaced sample of finished-match shot xG. Writes
only a standalone JSON report; never touches app feeds, production data or odds.
"""
from __future__ import annotations

import json
import os
import re
import time
from collections import Counter
from datetime import datetime, timezone

import requests

BASE_URL = "https://api.pitchapi.dev/v1"
TIMEOUT = 30
SAMPLE_PER_SEASON = 5


def fetch(session, path, api_key, params=None):
    last_error = None
    for attempt in range(3):
        try:
            response = session.get(
                f"{BASE_URL}{path}", params=params or {},
                headers={"X-API-KEY": api_key}, timeout=TIMEOUT,
            )
            if response.status_code in {429, 500, 502, 503, 504} and attempt < 2:
                time.sleep(attempt + 1)
                continue
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "data" not in body or "error" in body:
                raise ValueError(f"Unexpected PitchAPI response for {path}")
            return body["data"]
        except requests.RequestException as exc:
            last_error = exc
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise last_error or RuntimeError("PitchAPI request failed")


def season_label(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("name", "season", "year", "label"):
            if value.get(key) is not None:
                return str(value[key])
    return None


def season_key(value):
    match = re.search(r"(20\\d{2})", str(value))
    return int(match.group(1)) if match else 0


def is_target_league(league):
    name = re.sub(r"[^a-z0-9]+", " ", str(league.get("name") or "").casefold()).strip()
    country = str(league.get("country") or "").casefold()
    code = str(league.get("country_code") or "").upper()
    # Accept clear A-League Men / Australian A-League names, but reject women/youth.
    if any(token in name for token in ("women", "w league", "youth", "u 21", "u 23")):
        return False
    name_match = (
        "a league men" in name or "a league" == name
        or "australian a league" in name
        or "australia a league" in name
    )
    return name_match and (code in {"AUS", "AU"} or "australia" in country or code == "")


def finished(row):
    return str(row.get("status") or "").casefold() in {"finished", "played", "complete", "completed"}


def evenly_spaced(rows, limit=SAMPLE_PER_SEASON):
    rows = sorted(rows, key=lambda item: str(item.get("date") or ""))
    if len(rows) <= limit:
        return rows
    if limit == 1:
        return [rows[0]]
    indices = [round(i * (len(rows) - 1) / (limit - 1)) for i in range(limit)]
    return [rows[i] for i in indices]


def flatten_shots(data):
    if not isinstance(data, dict):
        return []
    if isinstance(data.get("shots"), list):
        return [x for x in data["shots"] if isinstance(x, dict)]
    periods = data.get("periods") or []
    return [
        shot for period in periods if isinstance(period, dict)
        for shot in (period.get("shots") or []) if isinstance(shot, dict)
    ]


def inspect_xg(session, match, api_key):
    home = match.get("home_team") or {}
    away = match.get("away_team") or {}
    result = {
        "match_id": match.get("id"), "date": match.get("date"),
        "home": home.get("name"), "away": away.get("name"),
        "status": match.get("status"), "paired_xg_present": False,
        "home_xg": None, "away_xg": None, "valid_xg_shots": 0,
    }
    try:
        data = fetch(session, f"/matches/{match['id']}/shots", api_key)
        totals = Counter()
        valid = 0
        for shot in flatten_shots(data):
            team_id = shot.get("team_id")
            xg = shot.get("expected_goals")
            if team_id in {home.get("id"), away.get("id")} and isinstance(xg, (int, float)) and not isinstance(xg, bool) and 0 <= xg <= 1:
                totals[team_id] += float(xg)
                valid += 1
        result["valid_xg_shots"] = valid
        if home.get("id") in totals and away.get("id") in totals:
            result["home_xg"] = round(totals[home["id"]], 4)
            result["away_xg"] = round(totals[away["id"]], 4)
            result["paired_xg_present"] = True
    except Exception as exc:
        result["xg_error_type"] = type(exc).__name__
        result["xg_error"] = str(exc)[:180]
    return result


def main():
    api_key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("PITCHAPI_API_KEY secret is not configured")
    session = requests.Session()
    catalogue = fetch(session, "/leagues", api_key)
    leagues = catalogue.get("leagues", []) if isinstance(catalogue, dict) else []
    targets = [league for league in leagues if isinstance(league, dict) and is_target_league(league)]
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "provider": "PitchAPI",
        "scope": "read-only A-League Men catalogue, fixtures and sampled shot-level xG; no odds",
        "catalogue_league_count": len(leagues),
        "target_leagues_found": len(targets),
        "leagues": [],
        "errors": [],
    }
    for league in targets:
        seasons = sorted(
            {s for s in (season_label(x) for x in (league.get("seasons") or [])) if s},
            key=season_key,
        )
        league_row = {
            "league_id": league.get("id"), "name": league.get("name"),
            "country": league.get("country"), "country_code": league.get("country_code"),
            "seasons_available": seasons, "season_reports": [],
        }
        for season in seasons:
            try:
                payload = fetch(
                    session, f"/leagues/{league['id']}/matches", api_key,
                    {"season": season, "status": "all"},
                )
                matches = payload.get("matches", []) if isinstance(payload, dict) else []
                statuses = Counter(str(row.get("status") or "unknown").casefold() for row in matches)
                completed = [row for row in matches if isinstance(row, dict) and row.get("id") and finished(row)]
                sample = evenly_spaced(completed)
                xg_rows = []
                for match in sample:
                    xg_rows.append(inspect_xg(session, match, api_key))
                    time.sleep(0.15)
                league_row["season_reports"].append({
                    "season": season,
                    "listed_matches": len(matches),
                    "status_counts": dict(statuses),
                    "finished_matches": len(completed),
                    "xg_sample_size": len(xg_rows),
                    "paired_xg_matches": sum(bool(row["paired_xg_present"]) for row in xg_rows),
                    "paired_xg_sample_rate": (
                        round(sum(bool(row["paired_xg_present"]) for row in xg_rows) / len(xg_rows), 4)
                        if xg_rows else None
                    ),
                    "xg_sample": xg_rows,
                })
            except Exception as exc:
                report["errors"].append({
                    "league": league.get("name"), "season": season,
                    "error_type": type(exc).__name__, "error": str(exc)[:240],
                })
            time.sleep(0.15)
        report["leagues"].append(league_row)

    with open("pitchapi-australia-a-league-audit.json", "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(json.dumps({
        "catalogue_league_count": report["catalogue_league_count"],
        "target_leagues_found": report["target_leagues_found"],
        "leagues": [
            {
                "name": item["name"], "id": item["league_id"],
                "country_code": item["country_code"],
                "seasons": [
                    {
                        "season": row["season"], "listed_matches": row["listed_matches"],
                        "finished_matches": row["finished_matches"],
                        "xg_sample_size": row["xg_sample_size"],
                        "paired_xg_matches": row["paired_xg_matches"],
                    } for row in item["season_reports"]
                ],
            } for item in report["leagues"]
        ],
        "errors": report["errors"],
        "report_file": "pitchapi-australia-a-league-audit.json",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
