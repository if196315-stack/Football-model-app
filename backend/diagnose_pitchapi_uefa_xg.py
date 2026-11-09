"""Read-only full-coverage xG probe for cached UEFA finished fixtures."""
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "backend/cache/pitchapi_uefa_fixtures.json"
REPORT = ROOT / "pitchapi-uefa-xg-diagnostic.json"
BASE = "https://api.pitchapi.dev/v1"
RETRYABLE_STATUS = {429, 500, 502, 503, 504, 520, 521, 522, 523, 524}


def shot_rows(data):
    if not isinstance(data, dict):
        return []
    if isinstance(data.get("shots"), list):
        return [x for x in data["shots"] if isinstance(x, dict)]
    periods = data.get("periods")
    if not isinstance(periods, list):
        return []
    return [
        shot
        for period in periods if isinstance(period, dict)
        for shot in (period.get("shots") or [])
        if isinstance(shot, dict)
    ]


def summarize_shots(data, home_id, away_id):
    totals = {home_id: 0.0, away_id: 0.0}
    counts = {home_id: 0, away_id: 0}
    valid = 0
    for shot in shot_rows(data):
        team = shot.get("team_id")
        value = shot.get("expected_goals")
        if (
            team not in totals
            or isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not 0 <= value <= 1
        ):
            continue
        totals[team] += float(value)
        counts[team] += 1
        valid += 1
    return {
        "validShots": valid,
        "homeShotCount": counts[home_id],
        "awayShotCount": counts[away_id],
        "homeXg": round(totals[home_id], 4) if counts[home_id] else None,
        "awayXg": round(totals[away_id], 4) if counts[away_id] else None,
        "pairedXg": bool(counts[home_id] and counts[away_id]),
    }


def select_sample(matches, per_group=None, limit=0):
    """Return every eligible finished fixture unless a positive cap is supplied."""
    eligible = [
        row for row in matches
        if row.get("status") == "finished"
        and row.get("homeTeamId") is not None
        and row.get("awayTeamId") is not None
        and row.get("id") is not None
    ]
    eligible.sort(key=lambda row: (
        str(row.get("leagueCode") or ""),
        str(row.get("season") or ""),
        str(row.get("date") or ""),
        str(row.get("id")),
    ))
    return eligible[:limit] if limit and limit > 0 else eligible


def build_report(rows, results, errors, limit, complete):
    ok = [item for item in results if item["requestStatus"] == "ok"]
    by_group = {}
    for item in results:
        key = f'{item.get("leagueCode") or "unknown"}:{item.get("season") or "unknown"}'
        group = by_group.setdefault(key, {
            "matchesChecked": 0, "successfulRequests": 0, "requestErrors": 0,
            "matchesWithValidXg": 0, "matchesWithPairedXg": 0,
            "matchesWithNoValidXg": 0, "validShots": 0,
        })
        group["matchesChecked"] += 1
        if item["requestStatus"] == "ok":
            group["successfulRequests"] += 1
            group["validShots"] += item.get("validShots", 0)
            group["matchesWithValidXg"] += int(item.get("validShots", 0) > 0)
            group["matchesWithPairedXg"] += int(bool(item.get("pairedXg")))
            group["matchesWithNoValidXg"] += int(item.get("validShots", 0) == 0)
        else:
            group["requestErrors"] += 1
    eligible = select_sample(rows, limit=limit)
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "provider": "PitchAPI",
        "scope": "Read-only UEFA finished-fixture shots/xG coverage; no production cache, model, odds, or betting writes",
        "status": "complete" if complete else "in_progress",
        "fixtureCacheMatches": len(rows),
        "eligibleFinishedMatches": len(eligible),
        "requestedLimit": limit or "all",
        "matchesAttempted": len(results),
        "successfulRequests": len(ok),
        "requestErrors": len(errors),
        "matchesWithValidXg": sum(x.get("validShots", 0) > 0 for x in ok),
        "matchesWithPairedXg": sum(bool(x.get("pairedXg")) for x in ok),
        "matchesWithNoValidXg": sum(x.get("validShots", 0) == 0 for x in ok),
        "validShots": sum(x.get("validShots", 0) for x in ok),
        "coverageByCompetitionSeason": by_group,
        "errors": errors,
        "results": results,
    }


def write_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def get_shots(session, match_id, api_key, retries=4):
    url = f"{BASE}/matches/{match_id}/shots"
    for attempt in range(retries + 1):
        response = session.get(
            url, headers={"X-API-KEY": api_key}, timeout=30
        )
        if response.status_code in RETRYABLE_STATUS and attempt < retries:
            retry_after = response.headers.get("Retry-After")
            try:
                delay = min(90, max(1, int(retry_after))) if retry_after else min(60, 2 ** attempt * 2)
            except ValueError:
                delay = min(60, 2 ** attempt * 2)
            time.sleep(delay)
            continue
        response.raise_for_status()
        body = response.json()
        if not isinstance(body, dict) or "data" not in body or "error" in body:
            raise ValueError("Unexpected PitchAPI shots response")
        return body["data"]
    raise RuntimeError("Retry limit exceeded")


def run(
    api_key,
    session=requests,
    cache_path=CACHE,
    report_path=REPORT,
    limit=0,
    pause=0.15,
    checkpoint_every=10,
):
    if not api_key:
        raise ValueError("PITCHAPI_API_KEY is not configured")
    cache = json.loads(Path(cache_path).read_text(encoding="utf-8"))
    rows = list(cache.get("matches", {}).values())
    sample = select_sample(rows, limit=limit)
    results, errors = [], []

    # Write a checkpoint before the first network request, then every N matches.
    write_report(report_path, build_report(rows, results, errors, limit, False))
    for index, row in enumerate(sample, start=1):
        item = {
            key: row.get(key) for key in (
                "id", "leagueCode", "season", "date", "home", "away",
                "homeTeamId", "awayTeamId",
            )
        }
        try:
            data = get_shots(session, row["id"], api_key)
            item.update(summarize_shots(data, row["homeTeamId"], row["awayTeamId"]))
            item["requestStatus"] = "ok"
        except Exception as exc:
            item.update({
                "requestStatus": "error",
                "errorType": type(exc).__name__,
                "error": str(exc)[:240],
            })
            errors.append(item["id"])
        results.append(item)

        if index % checkpoint_every == 0 or index == len(sample):
            write_report(
                report_path,
                build_report(rows, results, errors, limit, index == len(sample)),
            )
            print(f"Progress: {index}/{len(sample)} matches; errors={len(errors)}", flush=True)
        if pause and index < len(sample):
            time.sleep(pause)

    report = build_report(rows, results, errors, limit, True)
    write_report(report_path, report)
    print(json.dumps({key: value for key, value in report.items() if key != "results"}, ensure_ascii=False, indent=2))
    if errors:
        raise RuntimeError(f'{len(errors)} UEFA shot requests failed; full report saved')
    return report


if __name__ == "__main__":
    run(
        os.environ.get("PITCHAPI_API_KEY", "").strip(),
        limit=int(os.environ.get("PITCHAPI_UEFA_XG_SAMPLE_LIMIT", "0")),
    )
