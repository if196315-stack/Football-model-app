"""PitchAPI fixture/results and shot-level xG cache.

This module is deliberately separate from the production updater until its
coverage and model integration have passed validation. API keys are read only
from PITCHAPI_API_KEY and are never logged or persisted.
"""
from __future__ import annotations

import json
import os
import re
import time
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

BASE_URL = "https://api.pitchapi.dev/v1"
ROOT = Path(__file__).resolve().parents[1]
CACHE_PATH = ROOT / "backend" / "cache" / "pitchapi_history.json"
TIMEOUT = 30
TARGETS = {
    "ENG": {"premier league"},
    "ESP": {"laliga", "la liga"},
    "ITA": {"serie a"},
    "GER": {"bundesliga"},
    "FRA": {"ligue 1"},
}
COMPETITIONS = {
    "ENG": ("Англия · Премьер-лига", "eng"),
    "ESP": ("Испания · Ла Лига", "esp"),
    "ITA": ("Италия · Серия A", "ita"),
    "GER": ("Германия · Бундеслига", "ger"),
    "FRA": ("Франция · Лига 1", "fra"),
}


def norm(value: Any) -> str:
    return " ".join(str(value or "").casefold().replace("-", " ").split())


def fetch(session: Any, path: str, api_key: str, params: dict | None = None) -> Any:
    retryable = {429, 500, 502, 503, 504, 520, 521, 522, 523, 524}
    for attempt in range(4):
        try:
            response = session.get(
                f"{BASE_URL}{path}", params=params or {},
                headers={"X-API-KEY": api_key}, timeout=TIMEOUT,
            )
            if response.status_code in retryable and attempt < 3:
                retry_after = response.headers.get("Retry-After")
                try:
                    delay = min(60, max(1, int(retry_after))) if retry_after else 2 ** attempt
                except ValueError:
                    delay = 2 ** attempt
                time.sleep(delay)
                continue
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "data" not in body or "error" in body:
                raise ValueError(f"Unexpected PitchAPI response for {path}")
            return body["data"]
        except requests.RequestException:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("PitchAPI retry loop exhausted")


def load_cache(path: Path = CACHE_PATH) -> dict:
    if not path.exists():
        return {"version": 1, "provider": "PitchAPI", "matches": {}}
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("provider") != "PitchAPI" or not isinstance(data.get("matches"), dict):
        raise ValueError("PitchAPI cache has an unexpected schema")
    return data


def save_cache(cache: dict, path: Path = CACHE_PATH) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    cache["updatedAt"] = datetime.now(timezone.utc).isoformat()
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(cache, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(path)


def flatten_shots(data: Any) -> list[dict]:
    if not isinstance(data, dict):
        return []
    if isinstance(data.get("shots"), list):
        return [shot for shot in data["shots"] if isinstance(shot, dict)]
    periods = data.get("periods")
    if not isinstance(periods, list):
        return []
    return [
        shot for period in periods if isinstance(period, dict)
        for shot in (period.get("shots") or []) if isinstance(shot, dict)
    ]


def shot_xg(data: Any, home_id: Any, away_id: Any) -> tuple[float | None, float | None, int]:
    totals: dict[Any, float] = defaultdict(float)
    valid = 0
    for shot in flatten_shots(data):
        team_id = shot.get("team_id")
        value = shot.get("expected_goals")
        if team_id not in (home_id, away_id):
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1:
            continue
        totals[team_id] += float(value)
        valid += 1
    # No valid shots means unknown, not 0.0. A side with no shots is also
    # unknown unless the endpoint returned at least one valid shot overall.
    if valid == 0:
        return None, None, 0
    home_xg = round(totals[home_id], 4) if home_id in totals else None
    away_xg = round(totals[away_id], 4) if away_id in totals else None
    return home_xg, away_xg, valid


def season_year(value: Any) -> int | None:
    if not isinstance(value, str):
        return None
    try:
        year = int(value.split("/", 1)[0])
    except ValueError:
        return None
    return year if year >= 2021 else None


def discover_leagues(session: Any, api_key: str) -> dict[str, dict]:
    payload = fetch(session, "/leagues", api_key)
    rows = payload.get("leagues", []) if isinstance(payload, dict) else []
    result = {}
    for league in rows:
        code = league.get("country_code")
        if code in TARGETS and norm(league.get("name")) in TARGETS[code]:
            result[code] = league
    missing = sorted(set(TARGETS) - set(result))
    if missing:
        raise RuntimeError("PitchAPI target leagues missing: " + ", ".join(missing))
    return result


def normalize_match(match: dict, league_code: str, league: dict, season: str) -> dict | None:
    match_id = match.get("id")
    if match_id is None:
        return None
    home = match.get("home_team") or {}
    away = match.get("away_team") or {}
    date = match.get("date")
    return {
        "id": str(match_id), "provider": "PitchAPI",
        "leagueCode": league_code, "leagueId": league.get("id"),
        "leagueName": league.get("name"), "season": season,
        "date": date, "timeUtc": match.get("time_utc"), "status": str(match.get("status") or "").casefold(),
        "homeTeamId": home.get("id"), "home": home.get("name") or "TBD",
        "awayTeamId": away.get("id"), "away": away.get("name") or "TBD",
        "homeScore": match.get("score_home"), "awayScore": match.get("score_away"),
        "roundName": match.get("round_name"), "hasPlayoff": match.get("has_playoff"),
    }


def normalize_lineups(data: Any) -> dict | None:
    """Keep only documented lineup fields; preserve predicted vs confirmed status."""
    if not isinstance(data, dict):
        return None

    def side_payload(side: Any) -> dict | None:
        if not isinstance(side, dict):
            return None
        def players(field: str) -> list[dict]:
            rows = side.get(field)
            if not isinstance(rows, list):
                return []
            result = []
            for player in rows:
                if not isinstance(player, dict) or not player.get("name"):
                    continue
                result.append({
                    "id": player.get("player_id"),
                    "name": str(player["name"]),
                    "number": player.get("shirt_number"),
                    "positionId": player.get("position_id"),
                    "captain": bool(player.get("is_captain", False)),
                })
            return result
        return {
            "formation": side.get("formation"),
            "confirmed": side.get("confirmed") is True,
            "lineupType": side.get("lineup_type"),
            "coach": (side.get("coach") or {}).get("name") if isinstance(side.get("coach"), dict) else None,
            "starters": players("starters"),
            "subs": players("subs"),
        }

    home, away = side_payload(data.get("home")), side_payload(data.get("away"))
    if home is None or away is None:
        return None
    return {"source": "PitchAPI", "home": home, "away": away}


def refresh_upcoming_lineups(cache: dict, api_key: str, session: Any = requests,
                             now: datetime | None = None, pause_seconds: float = 0.12,
                             horizon_hours: int = 48, retry_hours: int = 6) -> dict:
    """Cache predicted/confirmed lineups only for fixtures in the next 48 hours.

    A confirmed lineup is immutable for this purpose. Missing/unavailable lineups
    are retried after a cooldown; no injury status is inferred from absent players.
    """
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    current = current.astimezone(timezone.utc)
    cutoff = current + timedelta(hours=max(0, horizon_hours))
    attempted = fetched = errors = 0
    for match_id, row in cache.get("matches", {}).items():
        if row.get("status") not in {"scheduled", "upcoming", "not_started"}:
            continue
        raw_kickoff = row.get("timeUtc") or row.get("date")
        try:
            kickoff = datetime.fromisoformat(str(raw_kickoff).replace("Z", "+00:00"))
            if kickoff.tzinfo is None:
                kickoff = kickoff.replace(tzinfo=timezone.utc)
            kickoff = kickoff.astimezone(timezone.utc)
        except (TypeError, ValueError):
            continue
        if kickoff < current - timedelta(hours=2) or kickoff > cutoff:
            continue
        previous = row.get("lineups")
        if isinstance(previous, dict):
            home = previous.get("home") or {}
            away = previous.get("away") or {}
            if home.get("confirmed") is True and away.get("confirmed") is True:
                continue
        attempted_at = row.get("lineupsAttemptedAt") or row.get("lineupsFetchedAt")
        if attempted_at:
            try:
                stamp = datetime.fromisoformat(str(attempted_at).replace("Z", "+00:00"))
                if stamp.tzinfo is None:
                    stamp = stamp.replace(tzinfo=timezone.utc)
                if current - stamp.astimezone(timezone.utc) < timedelta(hours=retry_hours):
                    continue
            except (TypeError, ValueError):
                pass
        attempted += 1
        try:
            payload = fetch(session, f"/matches/{match_id}/lineups", api_key)
            lineups = normalize_lineups(payload)
            if lineups is not None:
                row["lineups"] = lineups
                if not (lineups["home"]["confirmed"] and lineups["away"]["confirmed"]):
                    row["predictedLineups"] = lineups
                row["lineupsFetchedAt"] = current.isoformat()
                row.pop("lineupsFetchError", None)
                fetched += 1
            else:
                row["lineupsFetchError"] = "invalid_response"
            row["lineupsAttemptedAt"] = current.isoformat()
        except (requests.RequestException, ValueError, RuntimeError, TypeError) as exc:
            row["lineupsFetchError"] = type(exc).__name__
            row["lineupsAttemptedAt"] = current.isoformat()
            errors += 1
        if pause_seconds:
            time.sleep(pause_seconds)
    return {"lineupAttempts": attempted, "lineupsFetched": fetched, "lineupErrors": errors}


def refresh_history(api_key: str, session: Any = requests, cache_path: Path = CACHE_PATH,
                    pause_seconds: float = 0.12, max_shot_requests: int = 5000) -> dict:
    """Refresh fixture/result listings and fetch missing finished-match shot xG.

    Existing shot results are reused. A failed shot request is left retryable
    and never converted to zero xG. This is intended for a persisted Actions
    cache; do not run a cold full-history bootstrap without checking API quota.
    """
    cache = load_cache(cache_path)
    stored = cache["matches"]
    leagues = discover_leagues(session, api_key)
    listing_count = 0
    shot_requests = 0
    shot_attempts = 0
    errors = []
    for code in sorted(leagues):
        league = leagues[code]
        seasons = sorted(
            {s for s in (league.get("seasons") or []) if season_year(s) is not None},
            key=lambda s: season_year(s) or 0,
        )
        for season in seasons:
            try:
                payload = fetch(session, f"/leagues/{league['id']}/matches", api_key, {"season": season, "status": "all"})
                rows = payload.get("matches", []) if isinstance(payload, dict) else []
                for raw in rows:
                    row = normalize_match(raw, code, league, season)
                    if not row:
                        continue
                    listing_count += 1
                    previous = stored.get(row["id"], {})
                    # Preserve validated xG only for the exact provider match ID.
                    for field in ("homeXg", "awayXg", "xgShotCount", "xgFetchedAt", "xgAttemptedAt", "xgFetchError",
                                  "lineups", "predictedLineups", "lineupsFetchedAt", "lineupsAttemptedAt", "lineupsFetchError"):
                        if field in previous:
                            row[field] = previous[field]
                    stored[row["id"]] = row
            except (requests.RequestException, ValueError, RuntimeError, TypeError) as exc:
                errors.append(f"{code} {season}: {type(exc).__name__}")
            if pause_seconds:
                time.sleep(pause_seconds)

    lineup_summary = refresh_upcoming_lineups(
        cache, api_key, session=session, pause_seconds=pause_seconds
    )

    # Backfill chronologically from the oldest finished matches first. Recent
    # xG is already well covered; older seasons are needed for a longer,
    # reproducible walk-forward history. The per-run cap bounds API usage.
    pending = sorted(
        stored.items(),
        key=lambda item: (not bool(item[1].get("date")), str(item[1].get("date") or "")),
    )
    for match_id, row in pending:
        if shot_attempts >= max(0, int(max_shot_requests)):
            break
        if row.get("status") != "finished" or (
            isinstance(row.get("homeXg"), (int, float))
            and isinstance(row.get("awayXg"), (int, float))
        ):
            continue
        # Empty/partial shot feeds must not consume the request budget on every
        # scheduled preview run. Retry unresolved matches after a 7-day cooldown.
        attempted_at = row.get("xgAttemptedAt") or row.get("xgFetchedAt")
        if attempted_at and os.environ.get("PITCHAPI_RETRY_UNRESOLVED", "").strip().casefold() not in {"1", "true", "yes"}:
            try:
                attempted = datetime.fromisoformat(str(attempted_at).replace("Z", "+00:00"))
                if attempted.tzinfo is None:
                    attempted = attempted.replace(tzinfo=timezone.utc)
                if (datetime.now(timezone.utc) - attempted) < timedelta(days=7):
                    continue
            except (TypeError, ValueError):
                pass
        if row.get("homeTeamId") is None or row.get("awayTeamId") is None:
            continue
        shot_attempts += 1
        try:
            payload = fetch(session, f"/matches/{match_id}/shots", api_key)
            home_xg, away_xg, count = shot_xg(payload, row["homeTeamId"], row["awayTeamId"])
            row["homeXg"], row["awayXg"], row["xgShotCount"] = home_xg, away_xg, count
            row["xgFetchedAt"] = datetime.now(timezone.utc).isoformat()
            row["xgAttemptedAt"] = row["xgFetchedAt"]
            shot_requests += 1
        except (requests.RequestException, ValueError, RuntimeError, TypeError) as exc:
            row["xgFetchError"] = type(exc).__name__
            row["xgAttemptedAt"] = datetime.now(timezone.utc).isoformat()
            errors.append(f"shots {match_id}: {type(exc).__name__}")
        if pause_seconds:
            time.sleep(pause_seconds)

    cache["matches"] = stored
    cache["lastRefresh"] = datetime.now(timezone.utc).isoformat()
    cache["lastRefreshSummary"] = {
        "listedMatches": listing_count, "cachedMatches": len(stored),
        "shotRequests": shot_requests, "shotAttempts": shot_attempts,
        **lineup_summary, "pendingShotXg": sum(
            1 for row in stored.values()
            if row.get("status") == "finished"
            and (row.get("homeXg") is None or row.get("awayXg") is None)
        ), "errors": len(errors),
    }
    save_cache(cache, cache_path)
    return {"cache": cache, "summary": cache["lastRefreshSummary"], "errors": errors}


def model_history_rows(cache: dict) -> list[dict]:
    """Return only finished regular-season matches with paired non-null xG."""
    rows = []
    for row in cache.get("matches", {}).values():
        if row.get("status") != "finished" or row.get("hasPlayoff") is True:
            continue
        if row.get("homeScore") is None or row.get("awayScore") is None:
            continue
        if row.get("homeXg") is None or row.get("awayXg") is None:
            continue
        rows.append(row)
    return sorted(rows, key=lambda row: (str(row.get("date") or ""), row["id"]))


def main() -> int:
    key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    if not key:
        raise SystemExit("Set PITCHAPI_API_KEY in the environment")
    limit = int(os.environ.get("PITCHAPI_MAX_SHOT_REQUESTS", "5000"))
    result = refresh_history(key, max_shot_requests=limit)
    print(json.dumps({"provider": "PitchAPI", **result["summary"]}, ensure_ascii=False))
    if result["errors"]:
        print(f"PitchAPI refresh had {len(result['errors'])} retryable errors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
