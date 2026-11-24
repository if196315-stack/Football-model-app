"""Read-only PitchAPI coverage audit for Australia's A-League Men.
Writes only a standalone JSON artifact; never touches app data, feeds or odds.
"""
import json
import os
import re
import time
from collections import Counter
from datetime import datetime, timezone

import requests

BASE = "https://api.pitchapi.dev/v1"
SAMPLE_LIMIT = 0  # 0 means every finished match in every advertised season


def get(session, path, key, params=None):
    for attempt in range(3):
        try:
            response = session.get(
                BASE + path, params=params or {},
                headers={"X-API-KEY": key}, timeout=30,
            )
            if response.status_code in {429, 500, 502, 503, 504} and attempt < 2:
                time.sleep(attempt + 1)
                continue
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "data" not in body or "error" in body:
                raise ValueError("Unexpected PitchAPI response envelope")
            return body["data"]
        except requests.RequestException:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise RuntimeError("PitchAPI request failed")


def season_name(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("name", "season", "year", "label"):
            if value.get(key) is not None:
                return str(value[key])
    return None


def season_order(value):
    match = re.search(r"(20\d{2})", str(value))
    return int(match.group(1)) if match else 0


def is_a_league(league):
    name = re.sub(r"[^a-z0-9]+", " ", str(league.get("name") or "").casefold()).strip()
    country = str(league.get("country") or "").casefold()
    code = str(league.get("country_code") or "").upper()
    if any(word in name for word in ("women", "youth", "u 21", "u 23")):
        return False
    named = name in {"a league", "a league men", "australian a league", "australia a league"}
    return named and (code in {"AUS", "AU"} or "australia" in country or not code)


def is_finished(match):
    return str(match.get("status") or "").casefold() in {
        "finished", "played", "complete", "completed"
    }


def spaced(rows):
    rows = sorted(rows, key=lambda row: str(row.get("date") or ""))
    if SAMPLE_LIMIT <= 0 or len(rows) <= SAMPLE_LIMIT:
        return rows
    indices = [round(i * (len(rows) - 1) / (SAMPLE_LIMIT - 1)) for i in range(SAMPLE_LIMIT)]
    return [rows[i] for i in indices]


def shots_list(data):
    if not isinstance(data, dict):
        return []
    if isinstance(data.get("shots"), list):
        return [s for s in data["shots"] if isinstance(s, dict)]
    return [
        shot for period in (data.get("periods") or []) if isinstance(period, dict)
        for shot in (period.get("shots") or []) if isinstance(shot, dict)
    ]


def audit_match(session, match, key):
    home, away = match.get("home_team") or {}, match.get("away_team") or {}
    row = {
        "match_id": match.get("id"), "date": match.get("date"),
        "home": home.get("name"), "away": away.get("name"),
        "home_team_id": home.get("id"), "away_team_id": away.get("id"),
        "home_score": match.get("score_home"), "away_score": match.get("score_away"),
        "paired_xg_present": False, "home_xg": None, "away_xg": None,
        "valid_xg_shots": 0,
    }
    try:
        data = get(session, f"/matches/{match['id']}/shots", key)
        totals = Counter()
        for shot in shots_list(data):
            tid, xg = shot.get("team_id"), shot.get("expected_goals")
            if tid in {home.get("id"), away.get("id")} and isinstance(xg, (int, float)) and not isinstance(xg, bool) and 0 <= xg <= 1:
                totals[tid] += float(xg)
                row["valid_xg_shots"] += 1
        if home.get("id") in totals and away.get("id") in totals:
            row["home_xg"] = round(totals[home["id"]], 4)
            row["away_xg"] = round(totals[away["id"]], 4)
            row["paired_xg_present"] = True
    except Exception as exc:
        row["xg_error_type"] = type(exc).__name__
        row["xg_error"] = str(exc)[:180]
    return row


def main():
    key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    if not key:
        raise SystemExit("PITCHAPI_API_KEY secret is not configured")
    session = requests.Session()
    catalogue = get(session, "/leagues", key)
    all_leagues = catalogue.get("leagues", []) if isinstance(catalogue, dict) else []
    targets = [x for x in all_leagues if isinstance(x, dict) and is_a_league(x)]
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "provider": "PitchAPI",
        "scope": "read-only Australian A-League catalogue, all finished fixtures and shot-level xG coverage; no odds",
        "catalogue_league_count": len(all_leagues),
        "target_leagues_found": len(targets),
        "leagues": [], "errors": [],
    }
    for league in targets:
        seasons = sorted(
            {s for s in (season_name(v) for v in (league.get("seasons") or [])) if s},
            key=season_order,
        )
        item = {
            "league_id": league.get("id"), "name": league.get("name"),
            "country": league.get("country"), "country_code": league.get("country_code"),
            "seasons_available": seasons, "season_reports": [],
        }
        for season in seasons:
            try:
                data = get(session, f"/leagues/{league['id']}/matches", key,
                           {"season": season, "status": "all"})
                matches = data.get("matches", []) if isinstance(data, dict) else []
                statuses = Counter(str(m.get("status") or "unknown").casefold() for m in matches)
                finished = [m for m in matches if isinstance(m, dict) and m.get("id") and is_finished(m)]
                sample = spaced(finished)
                xg_rows = []
                for match in sample:
                    xg_rows.append(audit_match(session, match, key))
                    time.sleep(0.15)
                paired = sum(bool(x["paired_xg_present"]) for x in xg_rows)
                item["season_reports"].append({
                    "season": season, "listed_matches": len(matches),
                    "status_counts": dict(statuses), "finished_matches": len(finished),
                    "xg_checked_matches": len(xg_rows), "paired_xg_matches": paired,
                    "paired_xg_sample_rate": round(paired / len(xg_rows), 4) if xg_rows else None,
                    "xg_match_audits": xg_rows,
                })
            except Exception as exc:
                report["errors"].append({
                    "league": league.get("name"), "season": season,
                    "error_type": type(exc).__name__, "error": str(exc)[:240],
                })
            time.sleep(0.15)
        report["leagues"].append(item)

    path = "pitchapi-australia-a-league-audit.json"
    with open(path, "w", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps({
        "catalogue_league_count": report["catalogue_league_count"],
        "target_leagues_found": report["target_leagues_found"],
        "leagues": [{
            "name": x["name"], "id": x["league_id"],
            "country_code": x["country_code"],
            "seasons": [{
                "season": s["season"], "listed_matches": s["listed_matches"],
                "finished_matches": s["finished_matches"],
                "xg_checked_matches": s["xg_checked_matches"],
                "paired_xg_matches": s["paired_xg_matches"],
            } for s in x["season_reports"]],
        } for x in report["leagues"]],
        "errors": report["errors"], "report_file": path,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
