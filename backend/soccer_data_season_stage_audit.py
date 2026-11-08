"""Read-only stage audit for anomalous Soccer Data league-season totals.

Fetches each competition's season list once, then counts finished regular and
playoff matches for selected seasons. Makes at most 14 API calls.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import time
from typing import Any

import requests

BASE_URL = "https://api.soccer-data.co/v1"
REQUEST_INTERVAL_SECONDS = 7.1
TARGETS = (
    {"country": "France", "competition": "Ligue 1",
     "competition_id": "competition_7x8p84ket95z",
     "seasons": {"20/21": 380, "21/22": 380, "23/24": 306,
                 "24/25": 306, "25/26": 306}},
    {"country": "Italy", "competition": "Serie A",
     "competition_id": "competition_oerg2ei7clgj",
     "seasons": {"22/23": 380}},
)


class SoccerDataClient:
    def __init__(self, api_key: str, session: Any = None, sleep=time.sleep):
        if not api_key:
            raise ValueError("SOCCER_DATA_KEY is required")
        self.session = session or requests.Session()
        self.session.headers.update({"X-Api-Key": api_key, "Accept": "application/json"})
        self.sleep = sleep
        self.last_request: float | None = None

    def get(self, path: str, params: dict | None = None) -> dict:
        if self.last_request is not None:
            elapsed = time.monotonic() - self.last_request
            if elapsed < REQUEST_INTERVAL_SECONDS:
                self.sleep(REQUEST_INTERVAL_SECONDS - elapsed)
        response = self.session.get(BASE_URL + path, params=params or {}, timeout=(10, 30))
        self.last_request = time.monotonic()
        if response.status_code == 429:
            raw_wait = response.headers.get("Retry-After", "65")
            try:
                wait = max(1.0, float(raw_wait)) + 1.0
            except (TypeError, ValueError):
                wait = 66.0
            self.sleep(wait)
            response = self.session.get(BASE_URL + path, params=params or {}, timeout=(10, 30))
            self.last_request = time.monotonic()
        response.raise_for_status()
        body = response.json()
        if not isinstance(body, dict) or "data" not in body:
            raise ValueError(f"Unexpected API response for {path}")
        return body


def _season_map(client: SoccerDataClient, competition_id: str) -> dict[str, str]:
    body = client.get(f"/competitions/{competition_id}/seasons")
    rows = body["data"]
    if not isinstance(rows, list):
        raise ValueError(f"Invalid seasons list for {competition_id}")
    result: dict[str, str] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        year = str(row.get("year") or row.get("name") or "")
        season_id = row.get("id")
        if year and isinstance(season_id, str):
            if year in result:
                raise ValueError(f"Ambiguous season {year} for {competition_id}")
            result[year] = season_id
    return result


def _season_id(client: SoccerDataClient, competition_id: str, year: str) -> str:
    matches = _season_map(client, competition_id)
    if year not in matches:
        raise ValueError(f"Expected exactly one season {year} for {competition_id}")
    return matches[year]


def _stage_total(client: SoccerDataClient, competition_id: str,
                 season_id: str, stage: str) -> int:
    body = client.get("/matches", {
        "competition_id": competition_id,
        "season_id": season_id,
        "status": "finished",
        "stage": stage,
        "page": 1,
        "per_page": 1,
    })
    meta = body.get("meta")
    total = meta.get("total") if isinstance(meta, dict) else None
    if not isinstance(total, int) or isinstance(total, bool) or total < 0:
        raise ValueError(f"Invalid finished match total for stage {stage}")
    return total


def run_audit(api_key: str, session: Any = None, sleep=time.sleep) -> dict:
    client = SoccerDataClient(api_key, session=session, sleep=sleep)
    rows = []
    season_cache: dict[str, dict[str, str]] = {}
    for target in TARGETS:
        competition_id = target["competition_id"]
        if competition_id not in season_cache:
            season_cache[competition_id] = _season_map(client, competition_id)
        for year, expected_regular in target["seasons"].items():
            season_id = season_cache[competition_id].get(year)
            if not season_id:
                raise ValueError(f"Expected exactly one season {year} for {competition_id}")
            regular = _stage_total(client, competition_id, season_id, "regular")
            playoff = _stage_total(client, competition_id, season_id, "playoff")
            rows.append({
                "country": target["country"],
                "competition": target["competition"],
                "competitionId": competition_id,
                "season": year,
                "seasonId": season_id,
                "expectedRegularMatches": expected_regular,
                "finishedRegularMatches": regular,
                "finishedPlayoffMatches": playoff,
                "finishedTotal": regular + playoff,
                "regularCountMatchesExpected": regular == expected_regular,
                "regularCountDifference": regular - expected_regular,
            })
    return {
        "provider": "Soccer Data API",
        "audit": "anomalous_season_stage_counts",
        "readOnly": True,
        "maxApiCalls": 14,
        "results": rows,
        "summary": {
            "seasonsChecked": len(rows),
            "regularCountsMatchingExpected": sum(r["regularCountMatchesExpected"] for r in rows),
            "nonZeroPlayoffSeasons": sum(r["finishedPlayoffMatches"] > 0 for r in rows),
        },
        "limitations": [
            "Counts only; does not download or validate individual match records",
            "Expected counts are regular-season fixture baselines, not provider completeness proof",
            "Does not write application or production data",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="soccer-data-stage-audit.json")
    args = parser.parse_args()
    key = os.environ.get("SOCCER_DATA_KEY")
    if not key:
        parser.error("Set SOCCER_DATA_KEY in the environment")
    report = run_audit(key)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
