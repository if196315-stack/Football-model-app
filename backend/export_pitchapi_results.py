"""Export PitchAPI fixture results/metadata only; no shot or xG endpoint calls."""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests

from probe_pitchapi_coverage import TARGETS, fetch, norm, season_start

OUTPUT = Path("pitchapi-results-export.json")


def main() -> int:
    api_key = os.environ.get("PITCHAPI_API_KEY")
    if not api_key:
        print("PITCHAPI_API_KEY is not configured", file=sys.stderr)
        return 2

    catalogue = fetch(requests, "/leagues", api_key)
    leagues = catalogue.get("leagues", []) if isinstance(catalogue, dict) else []
    matched = {}
    for league in leagues if isinstance(leagues, list) else []:
        code, name = league.get("country_code"), norm(league.get("name"))
        if code in TARGETS and name in TARGETS[code]:
            matched[code] = league

    rows = []
    missing_leagues = sorted(set(TARGETS) - set(matched))
    for code in sorted(matched):
        league = matched[code]
        seasons = [season for season in (league.get("seasons") or []) if season_start(season) is not None]
        for season in sorted(set(seasons), key=lambda value: season_start(value) or 0):
            data = fetch(
                requests,
                f"/leagues/{league['id']}/matches",
                api_key,
                {"season": season, "status": "played"},
            )
            matches = data.get("matches", []) if isinstance(data, dict) else []
            for match in matches if isinstance(matches, list) else []:
                if not isinstance(match, dict) or not match.get("id"):
                    continue
                home = match.get("home_team") or {}
                away = match.get("away_team") or {}
                rows.append({
                    "provider": "PitchAPI",
                    "leagueCode": code,
                    "leagueId": league.get("id"),
                    "leagueName": league.get("name"),
                    "season": season,
                    "matchId": match.get("id"),
                    "date": match.get("date"),
                    "status": match.get("status"),
                    "homeTeamId": home.get("id"),
                    "homeTeam": home.get("name"),
                    "awayTeamId": away.get("id"),
                    "awayTeam": away.get("name"),
                    "score": (
                        f"{match.get('score_home')}-{match.get('score_away')}"
                        if match.get("score_home") is not None and match.get("score_away") is not None
                        else None
                    ),
                    "homeScore": match.get("score_home"),
                    "awayScore": match.get("score_away"),
                    "roundName": match.get("round_name"),
                    "hasPlayoff": match.get("has_playoff"),
                })

    result = {
        "provider": "PitchAPI",
        "exportType": "fixture_results_metadata_only",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "xgIncluded": False,
        "shotEndpointsCalled": False,
        "missingTargetLeagues": missing_leagues,
        "listedMatchCount": len(rows),
        "matches": sorted(rows, key=lambda row: (str(row.get("date") or ""), str(row["matchId"]))),
    }
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "provider": result["provider"],
        "exportType": result["exportType"],
        "missingTargetLeagues": missing_leagues,
        "listedMatchCount": len(rows),
        "statusCounts": {
            status: sum(row.get("status") == status for row in rows)
            for status in sorted({str(row.get("status")) for row in rows})
        },
        "output": str(OUTPUT),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
