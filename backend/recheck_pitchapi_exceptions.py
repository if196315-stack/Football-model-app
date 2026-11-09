"""Targeted PitchAPI recheck for current audit exceptions; never scans full history."""
from __future__ import annotations

import json
import os
import sys
import time
from collections import Counter

import requests

from probe_pitchapi_coverage import BASE_URL, fetch, flatten_shots

# Recheck the two historical round-robin anomalies found by the full audit.
TARGETS = {
    "laliga_2023_2024": {
        "league_id": "l_0ErfuF",
        "season": "2023/2024",
        "expected_pairs": {frozenset(("Girona", "Mallorca"))},
    },
    "bundesliga_2024_2025": {
        "league_id": "l_1Isor4",
        "season": "2024/2025",
        "expected_pairs": {frozenset(("Bochum", "Union Berlin"))},
    },
}

# All five matches with missing or one-sided shot-level xG in audit run #100.
SHOT_MATCH_IDS = (
    "m_4HsmY0",  # Barcelona - Real Sociedad
    "m_3RvEae",  # Montpellier - Clermont Foot
    "m_1fAQKz",  # Werder Bremen - Bayern München
    "m_04dR2o",  # Juventus - Genoa
    "m_1Or7sa",  # Cremonese - Pisa
)


def inspect_shots(session, match_id: str, api_key: str) -> dict:
    try:
        data = fetch(session, f"/matches/{match_id}/shots", api_key)
    except (requests.RequestException, ValueError, KeyError, TypeError) as exc:
        return {"matchId": match_id, "fetchStatus": "error", "errorType": type(exc).__name__}
    shots = flatten_shots(data)
    totals = Counter()
    valid = 0
    for shot in shots:
        xg = shot.get("expected_goals")
        team_id = shot.get("team_id")
        if team_id is not None and isinstance(xg, (int, float)) and not isinstance(xg, bool) and 0 <= xg <= 1:
            totals[str(team_id)] += float(xg)
            valid += 1
    try:
        meta = fetch(session, f"/matches/{match_id}", api_key)
    except (requests.RequestException, ValueError, KeyError, TypeError):
        meta = {}
    home = (meta.get("home_team") or {}).get("name")
    away = (meta.get("away_team") or {}).get("name")
    home_id = str((meta.get("home_team") or {}).get("id"))
    away_id = str((meta.get("away_team") or {}).get("id"))
    return {
        "matchId": match_id,
        "fetchStatus": "ok",
        "date": meta.get("date"),
        "home": home,
        "away": away,
        "score": meta.get("score"),
        "shotCount": len(shots),
        "validXgShots": valid,
        "homeXg": round(totals[home_id], 4) if home_id in totals else None,
        "awayXg": round(totals[away_id], 4) if away_id in totals else None,
        "pairedXgPresent": home_id in totals and away_id in totals,
    }


def main() -> int:
    api_key = os.environ.get("PITCHAPI_API_KEY")
    if not api_key:
        print("PITCHAPI_API_KEY is not configured", file=sys.stderr)
        return 2
    output = {"provider": "PitchAPI", "auditType": "targeted_recheck_v3", "leagueChecks": [], "shotChecks": []}
    for label, target in TARGETS.items():
        try:
            data = fetch(
                requests,
                f"/leagues/{target['league_id']}/matches",
                api_key,
                {"season": target["season"], "status": "played"},
            )
            matches = data.get("matches", []) if isinstance(data, dict) else []
            found = []
            for match in matches:
                home = (match.get("home_team") or {}).get("name")
                away = (match.get("away_team") or {}).get("name")
                if frozenset((home, away)) in target["expected_pairs"]:
                    found.append({
                        "matchId": match.get("id"),
                        "date": match.get("date"),
                        "status": match.get("status"),
                        "home": home,
                        "away": away,
                    })
            output["leagueChecks"].append({
                "check": label,
                "listedMatches": len(matches),
                "expectedPairs": [sorted(pair) for pair in target["expected_pairs"]],
                "matchesFound": sorted(found, key=lambda row: str(row.get("date") or "")),
                "errorType": None,
            })
        except (requests.RequestException, ValueError, KeyError, TypeError) as exc:
            output["leagueChecks"].append({"check": label, "errorType": type(exc).__name__})
        time.sleep(0.2)
    for match_id in SHOT_MATCH_IDS:
        output["shotChecks"].append(inspect_shots(requests, match_id, api_key))
        time.sleep(0.2)
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
