"""Read-only league-ID cross-check for all upcoming PitchAPI fixtures in seven competitions.

Uses the canonical API-Sport tournament IDs resolved by the companion lookup.
"""
import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from diagnose_seven_competition_api_sport import all_upcoming_fixtures
from update_odds_api_sport import match_fixture

BASE = "https://api.api-sport.ru/v2/football/matches"
TIMEOUT = 45
PAGE_SIZE = 500
MAX_PAGES = 5
TOURNAMENT_IDS = {
    "PL": 17,
    "PD": 8,
    "SA": 23,
    "BL1": 35,
    "FL1": 34,
    "UCL": 7,
    "UEL": 679,
}


def response_items(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("matches", "data", "items", "results"):
            if isinstance(payload.get(key), list):
                return payload[key]
    return []


def tournament_id(match):
    tournament = match.get("tournament") or {}
    return tournament.get("id") if isinstance(tournament, dict) else None


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY is missing; no request was made.")
    domestic = json.loads((ROOT / "docs" / "data.json").read_text(encoding="utf-8"))
    uefa = json.loads((ROOT / "docs" / "uefa-data.json").read_text(encoding="utf-8"))
    fixtures = all_upcoming_fixtures(domestic, uefa)
    by_comp = defaultdict(list)
    for fixture in fixtures:
        by_comp[fixture["competition"]].append(fixture)

    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    results = {}
    request_count = 0
    truncated = []
    for code, expected_id in TOURNAMENT_IDS.items():
        targets = by_comp.get(code, [])
        if not targets:
            results[code] = {"expectedTournamentId": expected_id, "targetFixtures": 0}
            continue
        dates = [date.fromisoformat(row["date"]) for row in targets]
        date_from, date_to = min(dates).isoformat(), max(dates).isoformat()
        candidates = []
        for page in range(1, MAX_PAGES + 1):
            response = session.get(BASE, params={
                "date_from": date_from,
                "date_to": date_to,
                "tournament_id": expected_id,
                "exclude_amateur": "true",
                "page": page,
                "page_size": PAGE_SIZE,
                "sort": "asc",
            }, timeout=TIMEOUT)
            response.raise_for_status()
            request_count += 1
            rows = response_items(response.json())
            candidates.extend(rows)
            if len(rows) < PAGE_SIZE:
                break
        else:
            truncated.append(code)

        correct_id_rows = [row for row in candidates if tournament_id(row) == expected_id]
        unexpected_ids = sorted({str(tournament_id(row)) for row in candidates if tournament_id(row) != expected_id})
        matched = 0
        missing = []
        for target in targets:
            found = match_fixture({
                "utcDate": target["utcDate"],
                "home": target["home"],
                "away": target["away"],
            }, correct_id_rows)
            if found:
                matched += 1
            else:
                missing.append({"date": target["date"], "home": target["home"], "away": target["away"]})
        results[code] = {
            "expectedTournamentId": expected_id,
            "targetFixtures": len(targets),
            "apiSportRowsReturned": len(candidates),
            "rowsWithExpectedTournamentId": len(correct_id_rows),
            "unexpectedTournamentIds": unexpected_ids,
            "matchedWithinExpectedTournament": matched,
            "unmatchedTargets": missing,
        }

    summary = {
        "pitchApiUpcomingFixtures": len(fixtures),
        "competitionsCovered": sorted(by_comp),
        "matchedWithinExpectedTournament": sum(row.get("matchedWithinExpectedTournament", 0) for row in results.values()),
        "totalTargetFixtures": len(fixtures),
        "truncatedCompetitionsAtPageLimit": truncated,
        "apiRequests": request_count,
        "readOnly": True,
        "productionSourcesOrFiltersChanged": False,
    }
    print("LEAGUE_ID_CROSSCHECK=" + json.dumps(results, ensure_ascii=False))
    print("SUMMARY=" + json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
