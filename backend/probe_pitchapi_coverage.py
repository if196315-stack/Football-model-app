"""Read-only PitchAPI coverage and xG spot audit.

Reads PITCHAPI_API_KEY from the environment. No application or production data
is modified. Samples up to three matches per league-season for shot-level xG.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import Counter
from typing import Any

import requests

BASE_URL = "https://api.pitchapi.dev/v1"
TARGETS = {
    "ENG": {"premier league"},
    "ESP": {"laliga", "la liga"},
    "ITA": {"serie a"},
    "GER": {"bundesliga"},
    "FRA": {"ligue 1"},
}
TIMEOUT = 30


def norm(value: Any) -> str:
    return " ".join(str(value or "").casefold().replace("-", " ").split())


def fetch(session: Any, path: str, api_key: str, params: dict | None = None) -> dict:
    response = session.get(
        f"{BASE_URL}{path}", params=params or {},
        headers={"X-API-KEY": api_key}, timeout=TIMEOUT,
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict) or "data" not in body:
        raise ValueError(f"Unexpected response envelope for {path}")
    if "error" in body:
        raise ValueError(f"API returned an error for {path}")
    return body["data"]


def season_start(season: Any) -> int | None:
    if not isinstance(season, str):
        return None
    first = season.split("/", 1)[0]
    try:
        year = int(first)
    except ValueError:
        return None
    return year if year >= 2021 else None


def choose_samples(matches: list[dict], limit: int = 3) -> list[dict]:
    valid = [m for m in matches if isinstance(m, dict) and m.get("id")
             and m.get("status") == "finished"]
    if len(valid) <= limit:
        return valid
    indexes = sorted({0, len(valid) // 2, len(valid) - 1})
    return [valid[i] for i in indexes[:limit]]


def inspect_shots(session: Any, match: dict, api_key: str) -> dict:
    data = fetch(session, f"/matches/{match['id']}/shots", api_key)
    shots = data.get("shots", []) if isinstance(data, dict) else []
    teams = {match.get("home_team", {}).get("id"), match.get("away_team", {}).get("id")}
    totals = Counter()
    valid_shots = 0
    for shot in shots if isinstance(shots, list) else []:
        if not isinstance(shot, dict):
            continue
        team_id = shot.get("team_id")
        xg = shot.get("expected_goals")
        if team_id in teams and isinstance(xg, (int, float)) and not isinstance(xg, bool):
            if 0 <= xg <= 1:
                totals[team_id] += float(xg)
                valid_shots += 1
    home_id = (match.get("home_team") or {}).get("id")
    away_id = (match.get("away_team") or {}).get("id")
    return {
        "matchId": match.get("id"),
        "home": (match.get("home_team") or {}).get("name"),
        "away": (match.get("away_team") or {}).get("name"),
        "date": match.get("date"),
        "status": match.get("status"),
        "shotCount": len(shots) if isinstance(shots, list) else None,
        "validXgShots": valid_shots,
        "homeXgFromShots": round(totals[home_id], 4) if home_id in totals else None,
        "awayXgFromShots": round(totals[away_id], 4) if away_id in totals else None,
        "pairedXgPresent": home_id in totals and away_id in totals,
    }


def run_audit(api_key: str, session: Any = requests, pause_seconds: float = 0.15) -> dict:
    catalogue = fetch(session, "/leagues", api_key)
    leagues = catalogue.get("leagues", []) if isinstance(catalogue, dict) else []
    matched: dict[str, dict] = {}
    for league in leagues if isinstance(leagues, list) else []:
        code, name = league.get("country_code"), norm(league.get("name"))
        if code in TARGETS and name in TARGETS[code]:
            matched[code] = league

    report = {
        "provider": "PitchAPI",
        "auditType": "read_only_sampled_xg",
        "targetLeagueCount": len(TARGETS),
        "matchedLeagueCount": len(matched),
        "missingTargets": sorted(set(TARGETS) - set(matched)),
        "leagues": [],
        "notes": [
            "Season match totals come from the provider's league match listing.",
            "xG is checked on up to three finished matches per season using shot-level expected_goals.",
            "This is a sample, not a full match-by-match xG completeness measurement.",
            "Shot xG is summed by team; a zero sum is not treated as missing if valid shots exist.",
        ],
    }
    for code in sorted(matched):
        league = matched[code]
        seasons = [s for s in (league.get("seasons") or []) if season_start(s) is not None]
        league_row = {
            "countryCode": code, "leagueId": league.get("id"),
            "leagueName": league.get("name"), "seasons": [],
        }
        for season in sorted(set(seasons), key=lambda s: season_start(s) or 0):
            data = fetch(session, f"/leagues/{league['id']}/matches", api_key,
                         {"season": season, "status": "played"})
            matches = data.get("matches", []) if isinstance(data, dict) else []
            status_counts = Counter(m.get("status", "missing") for m in matches if isinstance(m, dict))
            samples = choose_samples(matches)
            inspected = []
            for match in samples:
                inspected.append(inspect_shots(session, match, api_key))
                if pause_seconds:
                    time.sleep(pause_seconds)
            league_row["seasons"].append({
                "season": season,
                "listedMatches": len(matches) if isinstance(matches, list) else None,
                "finishedMatches": status_counts.get("finished", 0),
                "statusCounts": dict(status_counts),
                "xgSampleSize": len(inspected),
                "xgPairedSamples": sum(bool(x["pairedXgPresent"]) for x in inspected),
                "xgSamples": inspected,
            })
            if pause_seconds:
                time.sleep(pause_seconds)
        report["leagues"].append(league_row)
        if pause_seconds:
            time.sleep(pause_seconds)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", help="Optional JSON report path")
    args = parser.parse_args(argv)
    key = os.environ.get("PITCHAPI_API_KEY")
    if not key:
        parser.error("Set PITCHAPI_API_KEY in the environment; never pass it on the command line")
    try:
        report = run_audit(key)
    except (requests.RequestException, ValueError, KeyError, TypeError) as exc:
        print(f"PitchAPI audit failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8") as stream:
            stream.write(rendered)
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
