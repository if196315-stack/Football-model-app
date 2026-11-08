"""Read-only PitchAPI coverage and xG spot audit.

Reads PITCHAPI_API_KEY from the environment. No application or production data
is modified. Samples a configurable number of matches per league-season for shot-level xG.
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
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
SAMPLE_LIMIT = 5


def norm(value: Any) -> str:
    return " ".join(str(value or "").casefold().replace("-", " ").split())


def fetch(session: Any, path: str, api_key: str, params: dict | None = None) -> dict:
    """GET a PitchAPI resource, retrying transient transport and gateway errors."""
    retryable_statuses = {429, 500, 502, 503, 504}
    for attempt in range(3):
        try:
            response = session.get(
                f"{BASE_URL}{path}", params=params or {},
                headers={"X-API-KEY": api_key}, timeout=TIMEOUT,
            )
            if response.status_code in retryable_statuses and attempt < 2:
                time.sleep(1 * (attempt + 1))
                continue
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "data" not in body:
                raise ValueError(f"Unexpected response envelope for {path}")
            if "error" in body:
                raise ValueError(f"API returned an error for {path}")
            return body["data"]
        except requests.RequestException:
            if attempt >= 2:
                raise
            time.sleep(1 * (attempt + 1))
    raise RuntimeError(f"Unreachable retry state for {path}")


def season_start(season: Any) -> int | None:
    if not isinstance(season, str):
        return None
    first = season.split("/", 1)[0]
    try:
        year = int(first)
    except ValueError:
        return None
    return year if year >= 2021 else None


def choose_samples(matches: list[dict], limit: int = SAMPLE_LIMIT) -> list[dict]:
    """Choose evenly spaced finished matches from the full season chronology."""
    valid = [m for m in matches if isinstance(m, dict) and m.get("id")
             and m.get("status") == "finished"]
    if len(valid) <= limit:
        return valid
    if limit <= 1:
        return [valid[0]]
    indexes = [round(i * (len(valid) - 1) / (limit - 1)) for i in range(limit)]
    return [valid[i] for i in indexes]



def match_integrity(matches: list[dict], season: str | None = None) -> dict:
    """Detect duplicate provider IDs and duplicate same-day team pairings."""
    rows = [m for m in matches if isinstance(m, dict)]
    ids = Counter(str(m.get("id")) for m in rows if m.get("id") is not None)
    fixtures = Counter()
    fixture_labels = {}
    for match in rows:
        home_id = (match.get("home_team") or {}).get("id")
        away_id = (match.get("away_team") or {}).get("id")
        date = str(match.get("date") or "")[:10]
        if date and home_id is not None and away_id is not None:
            pair = tuple(sorted((str(home_id), str(away_id))))
            key = (date, pair)
            fixtures[key] += 1
            fixture_labels[key] = {"date": date, "teamIds": list(pair)}
    duplicate_ids = sorted(match_id for match_id, count in ids.items() if count > 1)
    duplicate_fixtures = [fixture_labels[key] for key, count in sorted(fixtures.items()) if count > 1]
    appearances = Counter()
    home_appearances = Counter()
    away_appearances = Counter()
    team_names = {}
    countable_rows = [m for m in rows if norm(m.get("status")) not in {"canceled", "cancelled"}]
    for match in countable_rows:
        home = match.get("home_team") or {}
        away = match.get("away_team") or {}
        home_id, away_id = home.get("id"), away.get("id")
        if home_id is not None:
            appearances[str(home_id)] += 1
            home_appearances[str(home_id)] += 1
            team_names[str(home_id)] = home.get("name")
        if away_id is not None:
            appearances[str(away_id)] += 1
            away_appearances[str(away_id)] += 1
            team_names[str(away_id)] = away.get("name")
    season_year = season_start(season)
    active_season = season_year is not None and season_year >= datetime.now(timezone.utc).year
    expected_per_team = None if active_season else max(0, (len(appearances) - 1) * 2)
    team_match_counts = [
        {"teamId": team_id, "team": team_names.get(team_id),
         "matches": appearances[team_id], "home": home_appearances[team_id],
         "away": away_appearances[team_id]}
        for team_id in sorted(appearances, key=lambda key: (team_names.get(key) or "", key))
    ]
    team_count_outliers = (
        [] if expected_per_team is None else
        [row for row in team_match_counts if row["matches"] != expected_per_team]
    )
    outlier_ids = {row["teamId"] for row in team_count_outliers}
    team_fixture_lists = {team_id: [] for team_id in sorted(outlier_ids)}
    for match in sorted(countable_rows, key=lambda item: str(item.get("date") or "")):
        home = match.get("home_team") or {}
        away = match.get("away_team") or {}
        home_id, away_id = str(home.get("id")), str(away.get("id"))
        if home_id in outlier_ids:
            team_fixture_lists[home_id].append({
                "date": match.get("date"), "home": home.get("name"),
                "away": away.get("name"), "status": match.get("status"),
                "matchId": match.get("id"),
            })
        if away_id in outlier_ids:
            team_fixture_lists[away_id].append({
                "date": match.get("date"), "home": home.get("name"),
                "away": away.get("name"), "status": match.get("status"),
                "matchId": match.get("id"),
            })
    return {
        "uniqueMatchIds": len(ids),
        "duplicateMatchIds": duplicate_ids,
        "uniqueFixtures": len(fixtures),
        "duplicateFixtures": duplicate_fixtures,
        "teamCount": len(appearances),
        "expectedMatchesPerTeam": expected_per_team,
        "teamCountsComparable": expected_per_team is not None,
        "teamMatchCounts": team_match_counts,
        "teamCountOutliers": team_count_outliers,
        "teamFixtureLists": team_fixture_lists,
    }

def match_context(match: dict) -> dict:
    """Expose only compact provider fields that may distinguish league stages."""
    keys = (
        "round", "round_name", "stage", "stage_name", "matchday", "match_day",
        "phase", "group", "leg", "competition_type", "competition_name",
        "tournament", "league",
    )
    context = {}
    for key in keys:
        value = match.get(key)
        if isinstance(value, (str, int, float, bool)) or value is None:
            if key in match:
                context[key] = value
        elif isinstance(value, dict):
            compact = {
                nested: value[nested]
                for nested in ("id", "name", "type", "slug")
                if isinstance(value.get(nested), (str, int, float, bool))
            }
            if compact:
                context[key] = compact
    return context


def flatten_shots(data: dict) -> list[dict]:
    """PitchAPI groups shots under periods[].shots."""
    if not isinstance(data, dict):
        return []
    direct = data.get("shots")
    if isinstance(direct, list):
        return [shot for shot in direct if isinstance(shot, dict)]
    periods = data.get("periods", [])
    if not isinstance(periods, list):
        return []
    return [shot for period in periods if isinstance(period, dict)
            for shot in (period.get("shots") or []) if isinstance(shot, dict)]


def inspect_shots(session: Any, match: dict, api_key: str) -> dict:
    try:
        data = fetch(session, f"/matches/{match['id']}/shots", api_key)
    except requests.RequestException as exc:
        return {
            "matchId": match.get("id"),
            "home": (match.get("home_team") or {}).get("name"),
            "away": (match.get("away_team") or {}).get("name"),
            "date": match.get("date"),
            "status": match.get("status"),
            "shotCount": None,
            "validXgShots": None,
            "homeXgFromShots": None,
            "awayXgFromShots": None,
            "pairedXgPresent": False,
            "xgFetchError": type(exc).__name__,
        }
    shots = flatten_shots(data)
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
        "shotCount": len(shots),
        "validXgShots": valid_shots,
        "homeXgFromShots": round(totals[home_id], 4) if home_id in totals else None,
        "awayXgFromShots": round(totals[away_id], 4) if away_id in totals else None,
        "pairedXgPresent": home_id in totals and away_id in totals,
    }


def run_audit(api_key: str, session: Any = requests, pause_seconds: float = 0.15,
              sample_limit: int = SAMPLE_LIMIT) -> dict:
    if not isinstance(sample_limit, int) or isinstance(sample_limit, bool) or not 1 <= sample_limit <= 50:
        raise ValueError("sample_limit must be an integer between 1 and 50")
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
        "xgSampleLimitPerSeason": sample_limit,
        "targetLeagueCount": len(TARGETS),
        "matchedLeagueCount": len(matched),
        "missingTargets": sorted(set(TARGETS) - set(matched)),
        "leagues": [],
        "notes": [
            "Season match totals come from the provider's league match listing.",
            "xG is checked on up to xgSampleLimitPerSeason evenly spaced finished matches per season using shot-level expected_goals.",
            "This is a sample, not a full match-by-match xG completeness measurement.",
            "Transient API errors are retried; final per-match shot-fetch failures are recorded as unavailable, never as zero xG.",
            "Shot xG is summed by team from periods[].shots; zero is not treated as missing when valid shots exist.",
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
            integrity = match_integrity(matches, season)
            samples = choose_samples(matches, sample_limit)
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
                "providerMatchFields": sorted({
                    key for match in matches if isinstance(match, dict) for key in match
                }),
                "sampleMatchContext": [
                    {"matchId": match.get("id"), "date": match.get("date"),
                     **match_context(match)}
                    for match in samples
                ],
                **integrity,
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
    parser.add_argument("--sample-limit", type=int, default=SAMPLE_LIMIT,
                        help="Finished matches to inspect per league-season (1-50; default: 5)")
    args = parser.parse_args(argv)
    if not 1 <= args.sample_limit <= 50:
        parser.error("--sample-limit must be between 1 and 50")
    key = os.environ.get("PITCHAPI_API_KEY")
    if not key:
        parser.error("Set PITCHAPI_API_KEY in the environment; never pass it on the command line")
    try:
        report = run_audit(key, sample_limit=args.sample_limit)
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
