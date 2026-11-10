"""Read-only league-ID cross-check for all upcoming PitchAPI fixtures in seven competitions.

Uses canonical API-Sport tournament IDs resolved by the companion lookup.
The scan uses bounded 28-day windows to stay within the provider's date-range
constraints. No production fixtures, odds, caches, predictions, or paper bets
are modified.
"""
import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from diagnose_seven_competition_api_sport import all_upcoming_fixtures
from update_odds_api_sport import match_fixture, team_names, sim

BASE = "https://api.api-sport.ru/v2/football/matches"
USAGE_URL = "https://api.api-sport.ru/v2/account/usage"
TIMEOUT = 45
PAGE_SIZE = 500
MAX_PAGES = 30
WINDOW_DAYS = 28
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


def windows(start, end):
    cursor = start
    while cursor <= end:
        stop = min(cursor + timedelta(days=WINDOW_DAYS - 1), end)
        yield cursor, stop
        cursor = stop + timedelta(days=1)


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY is missing; no request was made.")
    domestic = json.loads((ROOT / "docs" / "data.json").read_text(encoding="utf-8"))
    uefa = json.loads((ROOT / "docs" / "uefa-data.json").read_text(encoding="utf-8"))
    fixtures = all_upcoming_fixtures(domestic, uefa)
    if not fixtures:
        raise SystemExit("No upcoming fixtures in the checked-in PitchAPI calendar snapshots.")

    by_comp = defaultdict(list)
    for fixture in fixtures:
        by_comp[fixture["competition"]].append(fixture)
    all_dates = [date.fromisoformat(row["date"]) for row in fixtures]
    start_date, end_date = min(all_dates), max(all_dates)

    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    usage_response = session.get(USAGE_URL, timeout=TIMEOUT)
    usage_response.raise_for_status()
    usage_payload = usage_response.json()
    usage = usage_payload.get("usage") or {}
    print("ACCOUNT_USAGE=" + json.dumps({
        "dailyRequestLimit": usage_payload.get("dailyRequestLimit"),
        "today": usage.get("today"),
        "remaining": usage.get("remaining"),
    }, ensure_ascii=False))

    all_rows = []
    request_count = 1
    truncated_windows = []
    for first, last in windows(start_date, end_date):
        window_rows = []
        for page in range(1, MAX_PAGES + 1):
            response = session.get(BASE, params={
                "date_from": first.isoformat(),
                "date_to": last.isoformat(),
                "exclude_amateur": "true",
                "page": page,
                "page_size": PAGE_SIZE,
                "sort": "asc",
            }, timeout=TIMEOUT)
            if not response.ok:
                raise RuntimeError(
                    f"API-Sport returned {response.status_code} for "
                    f"{first.isoformat()}..{last.isoformat()}: {response.text[:500]}"
                )
            request_count += 1
            payload = response.json()
            rows = response_items(payload)
            window_rows.extend(rows)
            pagination = payload.get("pagination") or payload.get("paging") or {} if isinstance(payload, dict) else {}
            total_pages = pagination.get("total_pages") or pagination.get("totalPages") or pagination.get("last_page")
            if total_pages is not None and page >= int(total_pages):
                break
            if len(rows) < PAGE_SIZE:
                break
        else:
            truncated_windows.append(f"{first.isoformat()}..{last.isoformat()}")
        all_rows.extend(window_rows)

    results = {}
    for code, expected_id in TOURNAMENT_IDS.items():
        targets = by_comp.get(code, [])
        correct_rows = [row for row in all_rows if tournament_id(row) == expected_id]
        matched = 0
        missing = []
        for target in targets:
            found = match_fixture({
                "utcDate": target["utcDate"],
                "home": target["home"],
                "away": target["away"],
            }, correct_rows)
            if found:
                matched += 1
            else:
                any_competition_match = match_fixture({
                    "utcDate": target["utcDate"],
                    "home": target["home"],
                    "away": target["away"],
                }, all_rows)
                candidate = None
                if any_competition_match:
                    candidate = {
                        "apiSportFixtureId": any_competition_match.get("id"),
                        "apiSportTournamentId": tournament_id(any_competition_match),
                        "apiSportHome": (any_competition_match.get("homeTeam") or {}).get("name"),
                        "apiSportAway": (any_competition_match.get("awayTeam") or {}).get("name"),
                    }
                missing.append({
                    "date": target["date"], "home": target["home"], "away": target["away"],
                    "matchInAnyTournament": candidate,
                })
        results[code] = {
            "expectedTournamentId": expected_id,
            "targetFixtures": len(targets),
            "apiSportRowsWithExpectedTournamentId": len(correct_rows),
            "matchedWithinExpectedTournament": matched,
            "unmatchedTargets": missing,
        }

    summary = {
        "pitchApiUpcomingFixtures": len(fixtures),
        "competitionsCovered": sorted(by_comp),
        "matchedWithinExpectedTournament": sum(row["matchedWithinExpectedTournament"] for row in results.values()),
        "totalTargetFixtures": len(fixtures),
        "apiSportRowsScanned": len(all_rows),
        "truncatedWindowsAtPageLimit": truncated_windows,
        "apiRequestsIncludingFreeUsageCheck": request_count,
        "readOnly": True,
        "productionSourcesOrFiltersChanged": False,
        "unmatchedWithCandidateInAnotherTournament": sum(
            1 for result in results.values() for item in result["unmatchedTargets"]
            if item["matchInAnyTournament"] is not None
        ),
    }
    # Name-only comparison: deliberately ignore kickoff and tournament so that
    # spelling/translation/alias differences can be distinguished from missing fixtures.
    api_team_names = set()
    for row in all_rows:
        for key in ("homeTeam", "awayTeam"):
            api_team_names.update(team_names(row.get(key)))
    unmatched_name_checks = []
    for code, result in results.items():
        for target in result["unmatchedTargets"]:
            for side in ("home", "away"):
                target_name = target[side]
                ranked = sorted(
                    ((round(sim(target_name, candidate), 3), candidate)
                     for candidate in api_team_names),
                    reverse=True,
                )[:8]
                unmatched_name_checks.append({
                    "competition": code,
                    "date": target["date"],
                    "fixture": target["home"] + " - " + target["away"],
                    "side": side,
                    "pitchApiName": target_name,
                    "closestApiSportNames": [
                        {"similarity": score, "name": name} for score, name in ranked
                    ],
                })
    # Enrich the closest non-exact names with the actual API-Sport team IDs
    # and tournament IDs observed in the downloaded fixture catalogue.
    name_candidate_details = []
    for check in unmatched_name_checks:
        if check["closestApiSportNames"][0]["similarity"] == 1:
            continue
        candidates = []
        for ranked in check["closestApiSportNames"][:4]:
            if ranked["similarity"] < 0.60:
                continue
            occurrences = {}
            for row in all_rows:
                for key in ("homeTeam", "awayTeam"):
                    team = row.get(key) or {}
                    if ranked["name"] in team_names(team):
                        identity = (str(team.get("id") or ""), str(tournament_id(row) or ""),
                                    str(team.get("name") or ""))
                        occurrences[identity] = occurrences.get(identity, 0) + 1
            candidates.append({
                "candidateName": ranked["name"],
                "similarity": ranked["similarity"],
                "observedTeams": [
                    {"apiSportTeamId": identity[0], "tournamentId": identity[1],
                     "canonicalName": identity[2], "fixtureRows": count}
                    for identity, count in sorted(occurrences.items())
                ],
            })
        name_candidate_details.append({
            "competition": check["competition"],
            "pitchApiName": check["pitchApiName"],
            "candidateDetails": candidates,
        })
    # Match each unmatched fixture by API-Sport team IDs, ignoring kickoff.
    # Team candidates are restricted to the expected tournament to avoid
    # confusing first teams with reserves/youth sides.
    pair_id_checks = []
    for code, result in results.items():
        expected_id = TOURNAMENT_IDS[code]
        for target in result["unmatchedTargets"]:
            side_candidates = {}
            for side in ("home", "away"):
                ranked = {}
                for row in all_rows:
                    if str(tournament_id(row)) != str(expected_id):
                        continue
                    team = row.get("homeTeam" if side == "home" else "awayTeam") or {}
                    team_id = str(team.get("id") or "")
                    if not team_id:
                        continue
                    score = max((sim(target[side], name) for name in team_names(team)), default=0.0)
                    identity = (team_id, str(team.get("name") or ""))
                    if identity not in ranked or score > ranked[identity]["similarity"]:
                        ranked[identity] = {"teamId": team_id, "name": identity[1], "similarity": round(score, 3)}
                side_candidates[side] = sorted(
                    (item for item in ranked.values() if item["similarity"] >= 0.60),
                    key=lambda item: item["similarity"], reverse=True
                )[:5]
            home_ids = {item["teamId"] for item in side_candidates["home"] if item["similarity"] >= 0.60}
            away_ids = {item["teamId"] for item in side_candidates["away"] if item["similarity"] >= 0.60}
            pair_rows = []
            if home_ids and away_ids:
                for row in all_rows:
                    if str(tournament_id(row)) != str(expected_id):
                        continue
                    home_id = str((row.get("homeTeam") or {}).get("id") or "")
                    away_id = str((row.get("awayTeam") or {}).get("id") or "")
                    if home_id in home_ids and away_id in away_ids:
                        pair_rows.append({
                            "apiSportFixtureId": row.get("id"),
                            "date": row.get("utcDate") or row.get("dateEvent"),
                            "home": (row.get("homeTeam") or {}).get("name"),
                            "homeTeamId": home_id,
                            "away": (row.get("awayTeam") or {}).get("name"),
                            "awayTeamId": away_id,
                        })
            pair_id_checks.append({
                "competition": code, "expectedTournamentId": expected_id,
                "pitchApiFixture": {"date": target["date"], "home": target["home"], "away": target["away"]},
                "homeTeamCandidates": side_candidates["home"],
                "awayTeamCandidates": side_candidates["away"],
                "sameTeamIdsInExpectedTournament": pair_rows,
            })
    print("UNMATCHED_TEAM_NAME_CANDIDATES=" + json.dumps(unmatched_name_checks, ensure_ascii=False))
    print("TEAM_NAME_CANDIDATE_IDS=" + json.dumps(name_candidate_details, ensure_ascii=False))
    print("PAIR_TEAM_ID_CHECKS=" + json.dumps(pair_id_checks, ensure_ascii=False))
    print("LEAGUE_ID_CROSSCHECK=" + json.dumps(results, ensure_ascii=False))
    print("SUMMARY=" + json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
