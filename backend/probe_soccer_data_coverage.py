"""Read-only Soccer Data API xG coverage audit.

Set SOCCER_DATA_KEY in the environment. This makes one paged competition
listing request and one request per matched target competition; it never
downloads match histories or writes provider data into the production store.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any

import requests

BASE_URL = "https://api.soccer-data.co/v1"
TARGETS = {
    "England": {"premier league"},
    "Spain": {"laliga", "la liga"},
    "Italy": {"serie a"},
    "Germany": {"bundesliga"},
    "France": {"ligue 1"},
}


def _normalise(value: Any) -> str:
    return " ".join(str(value or "").casefold().replace("-", " ").split())


def _data(body: Any) -> Any:
    if not isinstance(body, dict) or "data" not in body:
        raise ValueError("API response is missing top-level data")
    return body["data"]


def fetch_json(session: Any, path: str, api_key: str, params: dict | None = None) -> dict:
    response = session.get(
        f"{BASE_URL}{path}",
        params=params or {},
        headers={"X-Api-Key": api_key},
        timeout=30,
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict):
        raise ValueError(f"Unexpected JSON response for {path}")
    return body


def fetch_xg_competitions(session: Any, api_key: str) -> list[dict]:
    results: list[dict] = []
    page = 1
    while True:
        body = fetch_json(
            session, "/coverage/leagues", api_key,
            {"data_type": "xg", "page": page, "per_page": 200},
        )
        data = _data(body)
        if not isinstance(data, list):
            raise ValueError("Coverage competition data must be a list")
        results.extend(item for item in data if isinstance(item, dict))
        meta = body.get("meta") or {}
        total_pages = meta.get("total_pages")
        if not isinstance(total_pages, int) or total_pages < 1:
            # A zero-page result is a valid empty response; missing pagination
            # metadata on a non-empty result is not safe to treat as complete.
            if not results and total_pages == 0:
                return results
            raise ValueError("Missing or invalid pagination meta.total_pages")
        if page >= total_pages:
            return results
        page += 1


def build_coverage_report(competitions: list[dict], season_payloads: dict[str, dict]) -> dict:
    report = {"provider": "Soccer Data API", "dataType": "xg", "competitions": [], "missingTargets": []}
    found_targets: set[str] = set()
    for competition in competitions:
        country = str(competition.get("country") or "")
        name = str(competition.get("name") or "")
        target = next(
            (c for c, names in TARGETS.items()
             if _normalise(country) == _normalise(c) and _normalise(name) in names),
            None,
        )
        if not target:
            continue
        comp_id = competition.get("id")
        if not isinstance(comp_id, str) or not comp_id:
            continue
        payload = season_payloads.get(comp_id, {})
        data = payload.get("data", {}) if isinstance(payload, dict) else {}
        seasons = data.get("seasons", []) if isinstance(data, dict) else []
        season_rows = []
        for season in seasons if isinstance(seasons, list) else []:
            if not isinstance(season, dict) or season.get("status") == "not_loaded":
                continue
            types = season.get("data_types") or {}
            xg = types.get("xg") or {}
            events = season.get("events") or {}
            season_rows.append({
                "season": season.get("year") or season.get("name"),
                "status": season.get("status"),
                "finishedMatches": events.get("finished"),
                "xgMatches": xg.get("covered_events", 0),
                "xgCoveragePct": xg.get("coverage_pct", 0),
                "xgAvailable": xg.get("available", False),
            })
        season_rows.sort(key=lambda row: str(row.get("season") or ""))
        found_targets.add(target)
        report["competitions"].append({
            "target": target,
            "competitionId": comp_id,
            "competitionName": data.get("name") or name,
            "country": data.get("country") or country,
            "seasons": season_rows,
        })
    report["competitions"].sort(key=lambda row: row["target"])
    report["missingTargets"] = sorted(set(TARGETS) - found_targets)
    return report


def run_audit(api_key: str, session: Any = requests) -> dict:
    competitions = fetch_xg_competitions(session, api_key)
    matched = []
    for competition in competitions:
        country = _normalise(competition.get("country"))
        name = _normalise(competition.get("name"))
        if any(country == _normalise(c) and name in names for c, names in TARGETS.items()):
            matched.append(competition)
    payloads = {}
    for competition in matched:
        comp_id = competition["id"]
        payloads[comp_id] = fetch_json(session, f"/coverage/leagues/{comp_id}", api_key)
    return build_coverage_report(competitions, payloads)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", help="Optional JSON output path; defaults to stdout")
    args = parser.parse_args(argv)
    api_key = os.environ.get("SOCCER_DATA_KEY")
    if not api_key:
        parser.error("Set SOCCER_DATA_KEY; the key is read from the environment only")
    try:
        report = run_audit(api_key)
    except (requests.RequestException, ValueError, KeyError) as exc:
        print(f"Coverage audit failed: {exc}", file=sys.stderr)
        return 1
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8") as stream:
            stream.write(rendered)
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
