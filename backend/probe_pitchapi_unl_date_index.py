"""Read-only probe: find API-Sport Nations League fixtures in PitchAPI's daily index.

This deliberately does not alter the production xG history or fixture cache.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

from diagnose_pitchapi_uefa_xg import get_shots, summarize_shots
from refresh_pitchapi_uefa_fixtures import CACHE_PATH

BASE = "https://api.pitchapi.dev/v1"
REPORT = Path(__file__).resolve().parents[1] / "pitchapi-unl-date-index-probe.json"
TIMEOUT = 30


def team_key(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").casefold())


def fetch_date(session: Any, day: str, api_key: str) -> list[dict]:
    response = session.get(
        f"{BASE}/date/{day}", headers={"X-API-KEY": api_key}, timeout=TIMEOUT
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict) or not isinstance(body.get("data"), dict):
        raise ValueError("Unexpected PitchAPI date response")
    matches = body["data"].get("matches")
    return [row for row in matches if isinstance(row, dict)] if isinstance(matches, list) else []


def probe(cache: dict, api_key: str, session: Any = requests,
          fetch_shots_fn=None, pause: float = 0.1) -> dict:
    if not api_key:
        raise ValueError("PITCHAPI_API_KEY is not configured")
    fetch_shots_fn = fetch_shots_fn or (
        lambda match_id: get_shots(session, match_id, api_key)
    )
    fixtures = [
        row for row in (cache.get("matches") or {}).values()
        if row.get("leagueCode") == "UNL" and row.get("provider") == "API-Sport"
        and row.get("date") and row.get("home") and row.get("away")
    ]
    by_date: dict[str, list[dict]] = defaultdict(list)
    for fixture in fixtures:
        by_date[str(fixture["date"])[:10]].append(fixture)

    results, errors = [], []
    for day in sorted(by_date):
        try:
            indexed = fetch_date(session, day, api_key)
            indexed_by_pair = {
                (team_key((row.get("home_team") or {}).get("name")),
                 team_key((row.get("away_team") or {}).get("name"))): row
                for row in indexed
            }
            for fixture in by_date[day]:
                pair = (team_key(fixture["home"]), team_key(fixture["away"]))
                candidate = indexed_by_pair.get(pair)
                if candidate is None:
                    results.append({
                        "apiSportId": fixture.get("providerEventId"),
                        "date": day, "home": fixture["home"], "away": fixture["away"],
                        "dateIndexMatch": False, "pairedXg": False,
                    })
                    continue
                match_id = candidate.get("id")
                home_id = (candidate.get("home_team") or {}).get("id")
                away_id = (candidate.get("away_team") or {}).get("id")
                item = {
                    "apiSportId": fixture.get("providerEventId"),
                    "pitchApiMatchId": match_id, "date": day,
                    "home": fixture["home"], "away": fixture["away"],
                    "dateIndexMatch": True,
                }
                if not match_id or home_id is None or away_id is None:
                    item.update({"shotsRequest": "skipped_missing_ids", "pairedXg": False})
                else:
                    try:
                        xg = summarize_shots(fetch_shots_fn(str(match_id)), home_id, away_id)
                        item.update({
                            "shotsRequest": "ok", "validShots": xg.get("validShots", 0),
                            "homeShotCount": xg.get("homeShotCount", 0),
                            "awayShotCount": xg.get("awayShotCount", 0),
                            "homeXg": xg.get("homeXg"), "awayXg": xg.get("awayXg"),
                            "pairedXg": bool(xg.get("pairedXg")),
                        })
                    except Exception as exc:
                        item.update({
                            "shotsRequest": "error", "errorType": type(exc).__name__,
                            "error": str(exc)[:200], "pairedXg": False,
                        })
                results.append(item)
            if pause:
                time.sleep(pause)
        except Exception as exc:
            errors.append({"date": day, "errorType": type(exc).__name__,
                           "error": str(exc)[:200]})

    matched = [row for row in results if row.get("dateIndexMatch")]
    paired = [row for row in matched if row.get("pairedXg") is True]
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "provider": "PitchAPI",
        "scope": "Read-only UNL daily-index and shots endpoint probe; production cache/history unchanged",
        "apiSportUnlFixtures": len(fixtures),
        "distinctDatesQueried": len(by_date),
        "dateIndexMatches": len(matched),
        "dateIndexMisses": len(results) - len(matched),
        "shotsRequestsSucceeded": sum(row.get("shotsRequest") == "ok" for row in matched),
        "matchesWithPairedXg": len(paired),
        "errors": errors,
        "results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default=str(CACHE_PATH))
    parser.add_argument("--report", default=str(REPORT))
    args = parser.parse_args()
    cache = json.loads(Path(args.cache).read_text(encoding="utf-8"))
    report = probe(cache, os.environ.get("PITCHAPI_API_KEY", "").strip())
    path = Path(args.report)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)
    print(json.dumps({key: value for key, value in report.items() if key != "results"},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
