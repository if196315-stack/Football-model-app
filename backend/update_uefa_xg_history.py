#!/usr/bin/env python3
"""Incrementally add newly finished UEFA matches to the paired shot-xG history."""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

from diagnose_pitchapi_uefa_xg import get_shots, summarize_shots

ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / "artifacts" / "uefa-xg-history.json"
FIXTURE_CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
RETRY_UNPAIRED_AFTER = timedelta(hours=24)


def update_history(cache: dict, history: dict, fetch_shots,
                   now: datetime | None = None) -> tuple[dict, dict]:
    now = now or datetime.now(timezone.utc)
    existing = {str(row.get("id")): row for row in (history.get("matches") or [])}
    attempted = added_or_updated = skipped_recent_unpaired = 0
    errors = []
    for fixture in (cache.get("matches") or {}).values():
        if (fixture.get("leagueCode") not in ("UCL", "UEL", "UECL")
                or fixture.get("provider") not in (None, "PitchAPI")
                or fixture.get("status") != "finished"):
            continue
        match_id = str(fixture.get("id") or "")
        if not match_id or fixture.get("homeTeamId") is None or fixture.get("awayTeamId") is None:
            continue
        old = existing.get(match_id)
        if old and old.get("pairedXg") is True:
            continue
        if old and old.get("lastCheckedAt"):
            try:
                checked = datetime.fromisoformat(old["lastCheckedAt"].replace("Z", "+00:00"))
                if now - checked < RETRY_UNPAIRED_AFTER:
                    skipped_recent_unpaired += 1
                    continue
            except (TypeError, ValueError):
                pass
        attempted += 1
        try:
            data = fetch_shots(match_id)
            xg = summarize_shots(data, fixture["homeTeamId"], fixture["awayTeamId"])
            home_score, away_score = fixture.get("homeScore"), fixture.get("awayScore")
            if not isinstance(home_score, int) or not isinstance(away_score, int):
                errors.append({"id": match_id, "error": "missing_final_score"})
                continue
            existing[match_id] = {
                "id": match_id,
                "leagueCode": fixture.get("leagueCode"),
                "season": fixture.get("season"),
                "date": str(fixture.get("date") or "")[:10],
                "home": fixture.get("home"),
                "away": fixture.get("away"),
                "homeTeamId": fixture.get("homeTeamId"),
                "awayTeamId": fixture.get("awayTeamId"),
                "homeScore": home_score,
                "awayScore": away_score,
                **xg,
                "pairedXg": bool(xg.get("pairedXg")),
                "lastCheckedAt": now.isoformat(),
            }
            added_or_updated += 1
        except Exception as exc:
            errors.append({"id": match_id, "error": f"{type(exc).__name__}: {str(exc)[:180]}"})
    matches = sorted(existing.values(), key=lambda row: (
        str(row.get("date") or ""), str(row.get("leagueCode") or ""), str(row.get("id") or "")
    ))
    updated = {
        **history,
        "schema_version": history.get("schema_version", 1),
        "provider": "PitchAPI",
        "updated_at": now.isoformat(),
        "matches": matches,
    }
    summary = {
        "attempted": attempted,
        "added_or_updated": added_or_updated,
        "skipped_recent_unpaired": skipped_recent_unpaired,
        "total_history_rows": len(matches),
        "paired_xg_rows": sum(row.get("pairedXg") is True for row in matches),
        "errors": errors,
    }
    return updated, summary


def write_atomic(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", default=str(HISTORY))
    parser.add_argument("--fixture-cache", default=str(FIXTURE_CACHE))
    args = parser.parse_args()
    history_path, cache_path = Path(args.history), Path(args.fixture_cache)
    if not history_path.exists():
        print(json.dumps({"status": "history_not_seeded", "attempted": 0}))
        return 0
    if not cache_path.exists():
        raise RuntimeError(f"UEFA fixture cache is missing: {cache_path}")
    api_key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("PITCHAPI_API_KEY is not configured")
    history = json.loads(history_path.read_text(encoding="utf-8"))
    cache = json.loads(cache_path.read_text(encoding="utf-8"))
    def fetch_one(match_id):
        return get_shots(requests, match_id, api_key)
    updated, summary = update_history(cache, history, fetch_one)
    write_atomic(history_path, updated)
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
