"""Fast API-Sport result confirmation for the Android feeds.

The normal model/odds pipelines remain untouched. This job only enriches existing
fixtures with API-Sport status, score and scoreStatus.final so Android can settle
paper bets as soon as the provider confirms the result.
"""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

from update_odds_api_sport import URL, match_fixture

ROOT = Path(__file__).resolve().parents[1]
FEEDS = (ROOT / "docs" / "data.json", ROOT / "docs" / "uefa-data.json")
TIMEOUT = 25
LOOKBACK_DAYS = 4
# Provider status is the authoritative lifecycle signal. API-Sport's nested
# scoreStatus.final can be null even after its top-level status becomes finished;
# forwarding that null blocks Android settlement indefinitely.
FINISHED = {
    "finished", "ft", "aet", "pen", "after extra time",
    "after penalties", "match finished",
}
LIVE = {"inprogress", "in progress", "live", "1h", "2h", "halftime", "half time"}


def normalized_status(match: dict) -> str:
    return str(match.get("status") or "").strip().casefold().replace("_", " ")


def is_finished_status(status: str) -> bool:
    return status in FINISHED


def is_live_status(status: str) -> bool:
    return status in LIVE


def response_matches(response: Any) -> tuple[list[dict], int]:
    if not response.ok:
        raise RuntimeError(
            f"API-Sport result request failed ({response.status_code}): "
            f"{response.text[:1000]}"
        )
    payload = response.json()
    if isinstance(payload, list):
        return payload, 1
    if not isinstance(payload, dict):
        raise ValueError("Unexpected API-Sport matches response")
    matches = payload.get("matches")
    if not isinstance(matches, list):
        raise ValueError("API-Sport response does not contain a matches list")
    return matches, max(1, min(int(payload.get("totalPages") or 1), 100))


def fetch_recent_matches(session: Any, today: date | None = None) -> list[dict]:
    today = today or datetime.now(timezone.utc).date()
    params = {
        "date_from": (today - timedelta(days=LOOKBACK_DAYS)).isoformat(),
        "date_to": today.isoformat(),
        "status": "inprogress,finished",
        "exclude_amateur": "true",
        "fields": (
            "id,status,dateEvent,startTimestamp,homeTeam,awayTeam,"
            "homeScore,awayScore,scoreStatus"
        ),
        "page_size": 500,
        "sort": "desc",
        "tz": "Etc/UTC",
    }
    matches: list[dict] = []
    page = 1
    while True:
        page_params = {**params, "page": page}
        response = session.get(URL, params=page_params, timeout=TIMEOUT)
        rows, total_pages = response_matches(response)
        matches.extend(rows)
        if page >= total_pages:
            break
        page += 1
    return matches


def score_value(match: dict, side: str) -> int | None:
    score = match.get(f"{side}Score")
    if isinstance(score, dict):
        # Model markets are full-time (90 minutes), not extra-time markets.
        # API-Sport exposes each football half separately; summing period1+2
        # excludes extra time while current may include it.
        first_half = score.get("period1")
        second_half = score.get("period2")
        if (not isinstance(first_half, bool) and isinstance(first_half, int)
                and not isinstance(second_half, bool) and isinstance(second_half, int)
                and first_half >= 0 and second_half >= 0):
            return first_half + second_half
        score = score.get("current")
    if isinstance(score, bool):
        return None
    try:
        value = int(score)
    except (TypeError, ValueError, OverflowError):
        return None
    return value if value >= 0 else None


def provider_status(match: dict) -> str:
    return normalized_status(match)


def apply_provider_match(target: dict, source: dict, updated_at: str) -> bool:
    status = provider_status(source)
    if not is_finished_status(status) and not is_live_status(status):
        return False

    final_status = target.get("scoreStatusFinal")
    # A final score is immutable for this settlement pipeline; ignore stale snapshots.
    if final_status is True and target.get("status") == "FINISHED":
        return False

    home_score = score_value(source, "home")
    away_score = score_value(source, "away")
    # A top-level finished status is authoritative even when the optional nested
    # scoreStatus.final field is absent or null. A live status must never settle.
    final = True if is_finished_status(status) else False

    mapped_status = "FINISHED" if is_finished_status(status) else "IN_PLAY"
    changed = False
    values = {
        "status": mapped_status,
        "scoreStatusFinal": final,
        "resultProvider": "API-Sport",
    }
    if home_score is not None and away_score is not None:
        values["homeScore"] = home_score
        values["awayScore"] = away_score

    for key, value in values.items():
        # Always write an explicit boolean so Android can distinguish live from final.
        if key not in target or target.get(key) != value:
            target[key] = value
            changed = True
    if changed:
        target["resultUpdatedAt"] = updated_at
    return changed


def sync_feed(payload: dict, provider_matches: list[dict],
              updated_at: str | None = None) -> dict:
    updated_at = updated_at or datetime.now(timezone.utc).isoformat()
    matched = changed = 0
    samples = []
    for competition in payload.get("competitions", []):
        for target in competition.get("matches", []):
            source = match_fixture(target, provider_matches)
            if source is None:
                continue
            status = provider_status(source)
            if status not in FINISHED | LIVE:
                continue
            matched += 1
            if apply_provider_match(target, source, updated_at):
                changed += 1
                if len(samples) < 20:
                    samples.append({
                        "competition": competition.get("code"),
                        "home": target.get("home"),
                        "away": target.get("away"),
                        "status": target.get("status"),
                        "score": [target.get("homeScore"), target.get("awayScore")],
                        "scoreStatusFinal": target.get("scoreStatusFinal"),
                    })
    return {"matched": matched, "changed": changed, "samples": samples}


def main() -> int:
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise RuntimeError("API_SPORT_KEY missing from GitHub Secrets")
    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    today = datetime.now(timezone.utc).date()
    provider_matches = fetch_recent_matches(session, today)
    updated_at = datetime.now(timezone.utc).isoformat()
    summary = {"providerMatches": len(provider_matches), "feeds": {}}
    for path in FEEDS:
        if not path.exists():
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        result = sync_feed(payload, provider_matches, updated_at)
        summary["feeds"][path.name] = result
        if result["changed"]:
            tmp = path.with_suffix(path.suffix + ".tmp")
            tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp.replace(path)
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
