"""Read-only spot check of real match-level xG for the five target leagues.

Makes at most ten API calls: one finished-match page and one stats request
per competition. It never downloads full history or writes application data.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import time
from typing import Any

import requests

BASE_URL = "https://api.soccer-data.co/v1"
COMPETITIONS = {
    "England": ("Premier League", "competition_705uv6jm8myn"),
    "Spain": ("LaLiga", "competition_ozwhqjc3z9ic"),
    "Italy": ("Serie A", "competition_oerg2ei7clgj"),
    "Germany": ("Bundesliga", "competition_897d0j7x1ypu"),
    "France": ("Ligue 1", "competition_7x8p84ket95z"),
}
REQUEST_INTERVAL_SECONDS = 6.2


class SoccerDataClient:
    def __init__(self, api_key: str, session: requests.Session | None = None,
                 sleep=time.sleep):
        if not api_key:
            raise ValueError("SOCCER_DATA_KEY is required")
        self.session = session or requests.Session()
        self.session.headers.update({"X-Api-Key": api_key, "Accept": "application/json"})
        self.sleep = sleep
        self._last_request: float | None = None

    def get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        if self._last_request is not None:
            elapsed = time.monotonic() - self._last_request
            if elapsed < REQUEST_INTERVAL_SECONDS:
                self.sleep(REQUEST_INTERVAL_SECONDS - elapsed)
        response = self.session.get(
            BASE_URL + path, params=params, timeout=(10, 30)
        )
        self._last_request = time.monotonic()
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict) or "data" not in payload:
            raise ValueError(f"Unexpected API response for {path}")
        return payload


def valid_xg_pair(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    home, away = value.get("home"), value.get("away")
    return all(
        isinstance(x, (int, float)) and not isinstance(x, bool)
        and math.isfinite(x) and 0 <= x <= 20
        for x in (home, away)
    )


def audit_competition(client: SoccerDataClient, country: str,
                      competition_name: str, competition_id: str) -> dict[str, Any]:
    listing = client.get("/matches", {
        "competition_id": competition_id,
        "status": "finished",
        "sort": "-utc_date",
        "page": 1,
        "per_page": 100,
    })
    matches = listing["data"]
    if not isinstance(matches, list):
        raise ValueError(f"Invalid match list for {competition_name}")

    candidate = next((
        m for m in matches
        if isinstance(m, dict)
        and m.get("status") == "finished"
        and m.get("xg_available") is True
        and isinstance(m.get("id"), str)
        and m["id"].startswith("match_")
    ), None)

    base = {
        "country": country,
        "competition": competition_name,
        "competitionId": competition_id,
        "listedFinishedMatches": len(matches),
        "listedPageTotal": listing.get("meta", {}).get("total"),
    }
    if candidate is None:
        return {**base, "result": "NO_XG_MATCH_IN_FIRST_PAGE",
                "sampleMatch": None, "xg": None}

    stats_response = client.get(f"/matches/{candidate['id']}/stats")
    stats = stats_response["data"]
    overview = stats.get("overview", {}) if isinstance(stats, dict) else {}
    xg = overview.get("expected_goals", {}).get("all") if isinstance(
        overview.get("expected_goals"), dict
    ) else None

    return {
        **base,
        "result": "PASS" if valid_xg_pair(xg) else "XG_PAIR_MISSING_OR_INVALID",
        "sampleMatch": {
            "id": candidate["id"],
            "utcDate": candidate.get("utc_date"),
            "homeTeam": candidate.get("home_team", {}).get("name"),
            "awayTeam": candidate.get("away_team", {}).get("name"),
            "score": candidate.get("score"),
            "xgAvailableFlag": candidate.get("xg_available"),
            "xgQuality": candidate.get("xg_quality"),
        },
        "xg": xg,
    }


def run_audit(api_key: str, session: requests.Session | None = None,
              sleep=time.sleep) -> dict[str, Any]:
    client = SoccerDataClient(api_key, session=session, sleep=sleep)
    results = []
    for country, (name, competition_id) in COMPETITIONS.items():
        results.append(audit_competition(client, country, name, competition_id))
    return {
        "provider": "Soccer Data API",
        "audit": "real_match_xg_spot_check",
        "readOnly": True,
        "maxApiCalls": 10,
        "results": results,
        "summary": {
            "passed": sum(r["result"] == "PASS" for r in results),
            "total": len(results),
            "failedOrMissing": [
                r["country"] for r in results if r["result"] != "PASS"
            ],
        },
        "limitations": [
            "One recent finished match sampled per competition, not a full audit",
            "Does not verify historical completeness or every match's xG",
            "Does not download history or modify production data",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="soccer-data-xg-spot-check.json")
    args = parser.parse_args()
    key = os.environ.get("SOCCER_DATA_KEY")
    if not key:
        parser.error("Set SOCCER_DATA_KEY in the environment")
    report = run_audit(key)
    Path(args.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report["summary"], ensure_ascii=False))
    return 0 if report["summary"]["passed"] == report["summary"]["total"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
