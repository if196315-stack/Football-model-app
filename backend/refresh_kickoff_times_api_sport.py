"""Enrich date-only football fixtures with authoritative API-Sport kickoff timestamps.

This deliberately queries the fixtures calendar without the odds view, so matches
without MelBet prices still receive their scheduled start time.
"""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

from update_odds_api_sport import URL, kickoff, match_fixture

ROOT = Path(__file__).resolve().parents[1]
FEEDS = (ROOT / "docs" / "data.json", ROOT / "docs" / "uefa-data.json")
WINDOW_DAYS = 31
TIMEOUT = 45


def _rows(payload: Any) -> tuple[list[dict], int]:
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)], 1
    if not isinstance(payload, dict) or not isinstance(payload.get("matches"), list):
        raise ValueError("Unexpected API-Sport matches response: missing matches list")
    return [row for row in payload["matches"] if isinstance(row, dict)], max(
        1, min(int(payload.get("totalPages") or 1), 100)
    )


def iter_windows(start: date, end: date):
    cursor = start
    while cursor <= end:
        window_end = min(cursor + timedelta(days=WINDOW_DAYS - 1), end)
        yield cursor, window_end
        cursor = window_end + timedelta(days=1)


def fetch_fixtures(api_key: str, start: date, end: date,
                   session: Any = requests) -> list[dict]:
    if not api_key:
        raise ValueError("API_SPORT_KEY is not configured")
    headers = {"Authorization": api_key, "Accept": "application/json"}
    fixtures: list[dict] = []
    for date_from, date_to in iter_windows(start, end):
        params = {
            "date_from": date_from.isoformat(),
            "date_to": date_to.isoformat(),
            "exclude_amateur": "true",
            "fields": "id,status,dateEvent,startTimestamp,homeTeam,awayTeam",
            "page_size": 500,
            "sort": "asc",
            "tz": "Etc/UTC",
            "page": 1,
        }
        response = session.get(URL, params=params, headers=headers, timeout=TIMEOUT)
        if not response.ok:
            raise RuntimeError(
                f"API-Sport fixture request failed ({response.status_code}): "
                f"{response.text[:1000]}"
            )
        rows, total_pages = _rows(response.json())
        fixtures.extend(rows)
        for page in range(2, total_pages + 1):
            page_params = {**params, "page": page}
            page_response = session.get(
                URL, params=page_params, headers=headers, timeout=TIMEOUT
            )
            if not page_response.ok:
                raise RuntimeError(
                    f"API-Sport fixture request failed ({page_response.status_code}): "
                    f"{page_response.text[:1000]}"
                )
            page_rows, _ = _rows(page_response.json())
            fixtures.extend(page_rows)
    return fixtures


def is_date_only(value: Any) -> bool:
    return isinstance(value, str) and len(value.strip()) == 10 and "T" not in value


def enrich_feed(payload: dict, fixtures: list[dict]) -> dict:
    checked = matched = enriched = 0
    unmatched = []
    samples = []
    for competition in payload.get("competitions", []):
        for match in competition.get("matches", []):
            raw_date = match.get("utcDate") or match.get("dateEvent")
            if not is_date_only(raw_date):
                continue
            if match.get("status") not in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                continue
            checked += 1
            source = match_fixture(match, fixtures)
            if source is None:
                if len(unmatched) < 30:
                    unmatched.append({
                        "competition": competition.get("code") or competition.get("name"),
                        "date": raw_date,
                        "home": match.get("home"),
                        "away": match.get("away"),
                    })
                continue
            source_time = kickoff(source)
            if source_time is None:
                continue
            matched += 1
            value = source_time.isoformat(timespec="seconds").replace("+00:00", "Z")
            match["utcDate"] = value
            enriched += 1
            if len(samples) < 30:
                samples.append({
                    "competition": competition.get("code") or competition.get("name"),
                    "home": match.get("home"),
                    "away": match.get("away"),
                    "utcDate": value,
                    "apiSportId": source.get("id"),
                })
    return {
        "dateOnlyChecked": checked,
        "matched": matched,
        "enriched": enriched,
        "unmatched": unmatched,
        "samples": samples,
    }


def main() -> int:
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise RuntimeError("API_SPORT_KEY missing from GitHub Secrets")
    payloads = []
    dates = []
    for path in FEEDS:
        if not path.exists():
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        payloads.append((path, payload))
        for competition in payload.get("competitions", []):
            for match in competition.get("matches", []):
                raw = match.get("utcDate") or match.get("dateEvent")
                if (is_date_only(raw)
                        and match.get("status") in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED")):
                    parsed = kickoff(match)
                    if parsed is not None:
                        dates.append(parsed.date())
    if not dates:
        print(json.dumps({"provider": "API-Sport", "dateOnlyFixtures": 0,
                          "message": "No date-only active fixtures found"}, ensure_ascii=False))
        return 0
    today = datetime.now(timezone.utc).date()
    start = min(today, min(dates))
    end = max(dates)
    fixtures = fetch_fixtures(key, start, end)
    summary = {
        "provider": "API-Sport fixtures calendar",
        "providerFixtures": len(fixtures),
        "dateRange": [start.isoformat(), end.isoformat()],
        "feeds": {},
    }
    for path, payload in payloads:
        result = enrich_feed(payload, fixtures)
        summary["feeds"][path.name] = result
        if result["enriched"]:
            temporary = path.with_suffix(path.suffix + ".tmp")
            temporary.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            temporary.replace(path)
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
