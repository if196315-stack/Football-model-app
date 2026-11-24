"""Enrich date-only football fixtures with authoritative API-Sport kickoff timestamps.

This deliberately queries the fixtures calendar without the odds view, so matches
without MelBet prices still receive their scheduled start time.
"""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

from update_odds_api_sport import URL, kickoff, norm, sim, team_names

ROOT = Path(__file__).resolve().parents[1]
FEEDS = (ROOT / "docs" / "data.json", ROOT / "docs" / "uefa-data.json")
WINDOW_DAYS = 31
TIMEOUT = 45


def _rows(payload: Any) -> tuple[list[dict], int]:
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)], 1
    if not isinstance(payload, dict) or not isinstance(payload.get("matches"), list):
        raise ValueError("Unexpected API-Sport matches response: missing matches list")
    return [row for row in payload["matches"] if isinstance(row, dict)], max(
        1, min(int(payload.get("totalPages") or 1), 100)
    )


def iter_windows(start: date, end: date):
    cursor = start
    while cursor <= end:
        window_end = min(cursor + timedelta(days=WINDOW_DAYS - 1), end)
        yield cursor, window_end
        cursor = window_end + timedelta(days=1)


def fetch_fixtures(api_key: str, start: date, end: date,
                   session: Any = requests) -> list[dict]:
    if not api_key:
        raise ValueError("API_SPORT_KEY is not configured")
    headers = {"Authorization": api_key, "Accept": "application/json"}
    fixtures: list[dict] = []
    for date_from, date_to in iter_windows(start, end):
        params = {
            "date_from": date_from.isoformat(),
            "date_to": date_to.isoformat(),
            "exclude_amateur": "true",
            "fields": "id,status,dateEvent,startTimestamp,homeTeam,awayTeam",
            "page_size": 500,
            "sort": "asc",
            "tz": "Etc/UTC",
            "page": 1,
        }
        response = session.get(URL, params=params, headers=headers, timeout=TIMEOUT)
        if not response.ok:
            raise RuntimeError(
                f"API-Sport fixture request failed ({response.status_code}): "
                f"{response.text[:1000]}"
            )
        rows, total_pages = _rows(response.json())
        fixtures.extend(rows)
        for page in range(2, total_pages + 1):
            page_params = {**params, "page": page}
            page_response = session.get(
                URL, params=page_params, headers=headers, timeout=TIMEOUT
            )
            if not page_response.ok:
                raise RuntimeError(
                    f"API-Sport fixture request failed ({page_response.status_code}): "
                    f"{page_response.text[:1000]}"
                )
            page_rows, _ = _rows(page_response.json())
            fixtures.extend(page_rows)
    return fixtures



# Canonical forms for common display-name/provider-name variants. These aliases
# are used only for kickoff enrichment; the stricter odds matcher is unchanged.
TEAM_ALIASES = {
    "paderborn": "sc paderborn 07",
    "sc paderborn 07": "sc paderborn 07",
    "elversberg": "sv 07 elversberg",
    "sv 07 elversberg": "sv 07 elversberg",
    "augsburg": "fc augsburg",
    "fc augsburg": "fc augsburg",
    "freiburg": "sc freiburg",
    "sc freiburg": "sc freiburg",
    "mainz 05": "1 fsv mainz 05",
    "1 fsv mainz 05": "1 fsv mainz 05",
    "borussia monchengladbach": "borussia m gladbach",
    "borussia m gladbach": "borussia m gladbach",
    "napoli": "ssc napoli",
    "ssc napoli": "ssc napoli",
    "milan": "ac milan",
    "ac milan": "ac milan",
    "roma": "as roma",
    "as roma": "as roma",
}


def _kickoff_team_similarity(target_name: str, team: dict) -> float:
    target = TEAM_ALIASES.get(norm(target_name), norm(target_name))
    best = 0.0
    for candidate_name in team_names(team):
        candidate = TEAM_ALIASES.get(norm(candidate_name), norm(candidate_name))
        score = 1.0 if target and target == candidate else sim(target_name, candidate_name)
        best = max(best, score)
    return best


def match_kickoff_fixture(target: dict, candidates: list[dict]) -> dict | None:
    """Match a date-only fixture, safely allowing duplicate IDs for the same kickoff."""
    raw_date = target.get("utcDate") or target.get("dateEvent")
    target_time = kickoff(target)
    if target_time is None or not is_date_only(raw_date):
        return None
    ranked = []
    for candidate in candidates:
        candidate_time = kickoff(candidate)
        if candidate_time is None or candidate_time.date() != target_time.date():
            continue
        home_score = _kickoff_team_similarity(target.get("home", ""), candidate.get("homeTeam") or {})
        away_score = _kickoff_team_similarity(target.get("away", ""), candidate.get("awayTeam") or {})
        if home_score >= 0.68 and away_score >= 0.68 and home_score + away_score >= 1.40:
            ranked.append((home_score + away_score, candidate_time, candidate))
    ranked.sort(key=lambda item: item[0], reverse=True)
    if not ranked:
        return None
    top_score, top_time, top_match = ranked[0]
    tied = [item for item in ranked if top_score - item[0] < 0.08]
    # Multiple provider records are harmless only when they agree on the exact
    # kickoff. If their times differ, keep the fixture unresolved rather than guess.
    if len(tied) > 1 and any(item[1] != top_time for item in tied[1:]):
        return None
    return top_match

def is_date_only(value: Any) -> bool:
    return isinstance(value, str) and len(value.strip()) == 10 and "T" not in value


def enrich_feed(payload: dict, fixtures: list[dict]) -> dict:
    checked = matched = enriched = 0
    unmatched = []
    samples = []
    for competition in payload.get("competitions", []):
        for match in competition.get("matches", []):
            raw_date = match.get("utcDate") or match.get("dateEvent")
            if not is_date_only(raw_date):
                continue
            if match.get("status") not in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                continue
            checked += 1
            source = match_kickoff_fixture(match, fixtures)
            if source is None:
                if len(unmatched) < 30:
                    unmatched.append({
                        "competition": competition.get("code") or competition.get("name"),
                        "date": raw_date,
                        "home": match.get("home"),
                        "away": match.get("away"),
                    })
                continue
            source_time = kickoff(source)
            if source_time is None:
                continue
            matched += 1
            value = source_time.isoformat(timespec="seconds").replace("+00:00", "Z")
            match["utcDate"] = value
            enriched += 1
            if len(samples) < 30:
                samples.append({
                    "competition": competition.get("code") or competition.get("name"),
                    "home": match.get("home"),
                    "away": match.get("away"),
                    "utcDate": value,
                    "apiSportId": source.get("id"),
                })
    return {
        "dateOnlyChecked": checked,
        "matched": matched,
        "enriched": enriched,
        "unmatched": unmatched,
        "samples": samples,
    }


def main() -> int:
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise RuntimeError("API_SPORT_KEY missing from GitHub Secrets")
    payloads = []
    dates = []
    for path in FEEDS:
        if not path.exists():
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        payloads.append((path, payload))
        for competition in payload.get("competitions", []):
            for match in competition.get("matches", []):
                raw = match.get("utcDate") or match.get("dateEvent")
                if (is_date_only(raw)
                        and match.get("status") in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED")):
                    parsed = kickoff(match)
                    if parsed is not None:
                        dates.append(parsed.date())
    if not dates:
        print(json.dumps({"provider": "API-Sport", "dateOnlyFixtures": 0,
                          "message": "No date-only active fixtures found"}, ensure_ascii=False))
        return 0
    today = datetime.now(timezone.utc).date()
    start = min(today, min(dates))
    end = max(dates)
    fixtures = fetch_fixtures(key, start, end)
    summary = {
        "provider": "API-Sport fixtures calendar",
        "providerFixtures": len(fixtures),
        "dateRange": [start.isoformat(), end.isoformat()],
        "feeds": {},
    }
    for path, payload in payloads:
        result = enrich_feed(payload, fixtures)
        summary["feeds"][path.name] = result
        if result["enriched"]:
            temporary = path.with_suffix(path.suffix + ".tmp")
            temporary.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            temporary.replace(path)
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
