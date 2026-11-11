"""Minimal read-only timestamp audit for the 39 one-day API-Sport/PitchAPI date offsets.

Fetches only two bounded date windows (Serie A 2027-05-15..30 and Bundesliga
2027-03-02..03), then emits raw kickoff-related fields for the exact ordered
team-ID pairs already identified in the previous audit. No production files
or ingestion paths are changed.
"""
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://api.api-sport.ru/v2/football/matches"
USAGE_URL = "https://api.api-sport.ru/v2/account/usage"
TIMEOUT = 45
PAGE_SIZE = 500
MAX_PAGES = 5

# competition, PitchAPI date, home, away, expected tournament ID,
# API-Sport home team ID, API-Sport away team ID
TARGETS = [
    ("SA","2027-05-16","Bologna","Frosinone",23,"2685","2801"),
    ("SA","2027-05-16","Cagliari","Torino",23,"2719","2696"),
    ("SA","2027-05-16","Como","Atalanta",23,"2704","2686"),
    ("SA","2027-05-16","Juventus","Inter",23,"2687","2697"),
    ("SA","2027-05-16","Lazio","Udinese",23,"2699","2695"),
    ("SA","2027-05-16","Lecce","Fiorentina",23,"2689","2693"),
    ("SA","2027-05-16","Milan","Roma",23,"2692","2702"),
    ("SA","2027-05-16","Monza","Parma",23,"2729","2690"),
    ("SA","2027-05-16","Napoli","Genoa",23,"2714","2713"),
    ("SA","2027-05-16","Sassuolo","Venezia",23,"2793","2688"),
    ("SA","2027-05-23","Atalanta","Lecce",23,"2686","2689"),
    ("SA","2027-05-23","Fiorentina","Monza",23,"2693","2729"),
    ("SA","2027-05-23","Frosinone","Cagliari",23,"2801","2719"),
    ("SA","2027-05-23","Genoa","Bologna",23,"2713","2685"),
    ("SA","2027-05-23","Inter","Lazio",23,"2697","2699"),
    ("SA","2027-05-23","Parma","Juventus",23,"2690","2687"),
    ("SA","2027-05-23","Roma","Como",23,"2702","2704"),
    ("SA","2027-05-23","Torino","Napoli",23,"2696","2714"),
    ("SA","2027-05-23","Udinese","Sassuolo",23,"2695","2793"),
    ("SA","2027-05-23","Venezia","Milan",23,"2688","2692"),
    ("SA","2027-05-30","Bologna","Parma",23,"2685","2690"),
    ("SA","2027-05-30","Cagliari","Roma",23,"2719","2702"),
    ("SA","2027-05-30","Como","Genoa",23,"2704","2713"),
    ("SA","2027-05-30","Juventus","Frosinone",23,"2687","2801"),
    ("SA","2027-05-30","Lazio","Fiorentina",23,"2699","2693"),
    ("SA","2027-05-30","Lecce","Venezia",23,"2689","2688"),
    ("SA","2027-05-30","Milan","Udinese",23,"2692","2695"),
    ("SA","2027-05-30","Monza","Torino",23,"2729","2696"),
    ("SA","2027-05-30","Napoli","Atalanta",23,"2714","2686"),
    ("SA","2027-05-30","Sassuolo","Inter",23,"2793","2697"),
    ("BL1","2027-03-02","Bayer Leverkusen","Hoffenheim",35,"2681","2569"),
    ("BL1","2027-03-02","Bayern München","Freiburg",35,"2672","2538"),
    ("BL1","2027-03-02","Borussia Mönchengladbach","VfB Stuttgart",35,"2527","2677"),
    ("BL1","2027-03-02","Eintracht Frankfurt","Borussia Dortmund",35,"2674","2673"),
    ("BL1","2027-03-02","Elversberg","RB Leipzig",35,"2598","36360"),
    ("BL1","2027-03-02","Hamburger SV","Paderborn",35,"2676","2561"),
    ("BL1","2027-03-02","Schalke 04","1. FC Köln",35,"2530","2671"),
    ("BL1","2027-03-02","Union Berlin","Augsburg",35,"2547","2600"),
    ("BL1","2027-03-02","Werder Bremen","Mainz 05",35,"2534","2556"),
]

def items(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("matches", "data", "items", "results"):
            if isinstance(payload.get(key), list):
                return payload[key]
    return []

def raw_kickoff(row):
    keys = ("utcDate", "dateEvent", "date", "time", "startTimestamp",
            "timestamp", "kickoff", "kickOff", "timezone", "timeZone",
            "startTime", "start_date", "start_time", "dateTime")
    return {key: row.get(key) for key in keys if key in row}

def timestamp_utc(row):
    stamp = row.get("startTimestamp")
    if isinstance(stamp, (int, float)):
        seconds = stamp / 1000 if stamp > 10_000_000_000 else stamp
        return datetime.fromtimestamp(seconds, tz=timezone.utc).isoformat()
    for key in ("utcDate", "dateEvent", "dateTime", "startTime"):
        raw = row.get(key)
        if not isinstance(raw, str) or "T" not in raw:
            continue
        try:
            dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
            if dt.tzinfo is None:
                return {"value": raw, "timezone_missing": True}
            return dt.astimezone(timezone.utc).isoformat()
        except ValueError:
            pass
    return None

def load_pitchapi():
    sources = []
    for path in (ROOT / "docs" / "data.json", ROOT / "docs" / "uefa-data.json"):
        if path.exists():
            sources.append(json.loads(path.read_text(encoding="utf-8")))
    found = {}
    for source in sources:
        for comp in source.get("competitions", []):
            code = comp.get("code") or comp.get("id")
            for match in comp.get("matches", []):
                key = (code, str(match.get("home") or ""), str(match.get("away") or ""))
                if code in {"SA", "BL1"}:
                    found.setdefault(key, []).append({
                        "id": match.get("id"),
                        "utcDate": match.get("utcDate"),
                        "status": match.get("status"),
                    })
    return found

def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY missing; no API request was made.")
    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    usage_response = session.get(USAGE_URL, timeout=TIMEOUT)
    usage_response.raise_for_status()
    usage_payload = usage_response.json()
    usage = usage_payload.get("usage") or {}
    remaining = usage.get("remaining")
    print("ACCOUNT_USAGE_BEFORE=" + json.dumps({
        "dailyRequestLimit": usage_payload.get("dailyRequestLimit"),
        "today": usage.get("today"),
        "remaining": remaining,
    }, ensure_ascii=False))
    if remaining is not None and int(remaining) < 3:
        raise SystemExit("Insufficient quota for the planned 3-request minimum; no fixture queries made.")

    # Only two date windows are queried; pagination stops on provider metadata
    # or a short page. Any cap hit is reported explicitly.
    windows = [
        ("SA", "2027-05-15", "2027-05-30", 23),
        ("BL1", "2027-03-02", "2027-03-03", 35),
    ]
    rows_by_comp = {"SA": [], "BL1": []}
    truncated = []
    request_count = 1
    for comp, first, last, expected_id in windows:
        for page in range(1, MAX_PAGES + 1):
            response = session.get(BASE, params={
                "date_from": first, "date_to": last, "exclude_amateur": "true",
                "page": page, "page_size": PAGE_SIZE, "sort": "asc",
            }, timeout=TIMEOUT)
            response.raise_for_status()
            request_count += 1
            payload = response.json()
            page_rows = items(payload)
            rows_by_comp[comp].extend(
                row for row in page_rows
                if str(((row.get("tournament") or {}).get("id") or "")) == str(expected_id)
            )
            pagination = payload.get("pagination") or payload.get("paging") or {} if isinstance(payload, dict) else {}
            total_pages = pagination.get("total_pages") or pagination.get("totalPages") or pagination.get("last_page")
            if total_pages is not None and page >= int(total_pages):
                break
            if len(page_rows) < PAGE_SIZE:
                break
        else:
            truncated.append(f"{comp}:{first}..{last}")

    pitch = load_pitchapi()
    output = []
    for comp, pitch_date, home, away, tournament_id, home_id, away_id in TARGETS:
        candidates = [
            row for row in rows_by_comp[comp]
            if str((row.get("homeTeam") or {}).get("id") or "") == home_id
            and str((row.get("awayTeam") or {}).get("id") or "") == away_id
            and str(((row.get("tournament") or {}).get("id") or "")) == str(tournament_id)
        ]
        pitch_rows = pitch.get((comp, home, away), [])
        output.append({
            "competition": comp, "pitchApiFixture": {"date": pitch_date, "home": home, "away": away},
            "pitchApiSnapshotRows": pitch_rows,
            "apiSportCandidates": [{
                "id": row.get("id"),
                "tournament": row.get("tournament"),
                "homeTeam": row.get("homeTeam"),
                "awayTeam": row.get("awayTeam"),
                "status": row.get("status"),
                "round": row.get("round"),
                "rawKickoffFields": raw_kickoff(row),
                "normalizedKickoffUTC": timestamp_utc(row),
                "topLevelKeys": sorted(row.keys()),
            } for row in candidates],
            "candidateCount": len(candidates),
        })
    summary = {
        "targets": len(TARGETS),
        "targetsWithApiSportCandidate": sum(row["candidateCount"] > 0 for row in output),
        "targetsWithMultipleCandidates": sum(row["candidateCount"] > 1 for row in output),
        "truncatedWindows": truncated,
        "apiRequestsIncludingUsage": request_count,
        "readOnly": True,
        "productionSourcesOrFiltersChanged": False,
    }
    print("TIMESTAMP_AUDIT_RESULTS=" + json.dumps(output, ensure_ascii=False))
    print("TIMESTAMP_AUDIT_SUMMARY=" + json.dumps(summary, ensure_ascii=False))
    print("DIAGNOSTIC_DONE=read_only")

if __name__ == "__main__":
    main()
