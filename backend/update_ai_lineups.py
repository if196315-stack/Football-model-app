"""Refresh API-Football lineups for matches that qualify for the AI betting filter.

PitchAPI remains the only fixture/xG source. API-Sport remains the only odds source.
API-Football is queried only for non-paper AI candidates and is quota-limited.
"""
from __future__ import annotations

import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from football_api_lineups import refresh_lineups
from pitchapi_data_source import CACHE_PATH, load_cache, save_cache
from update_data_pitchapi import build_model_frames, comp_for_code
from update_data import MAP, match_name, strengths, predict

DATA_PATH = ROOT / "docs" / "data.json"


def signal_price(signal: dict, match: dict) -> float | None:
    side = str(signal.get("side") or "")
    best = ((match.get("odds") or {}).get("best") or {})
    if side in ("1X", "X2"):
        value = ((best.get("doubleChance") or {}).get("1x" if side == "1X" else "x2"))
        return float(value) if isinstance(value, (int, float)) else None
    if side.startswith("ИТБ "):
        found = re.search(r"ИТБ\\s+([0-9]+(?:\\.[0-9]+)?)", side)
        if not found:
            return None
        team_name = side.split("·", 1)[1].strip() if "·" in side else ""
        team = "home" if team_name == match.get("home") else "away" if team_name == match.get("away") else None
        if not team:
            return None
        point = float(found.group(1))
        for row in best.get("teamTotals") or []:
            if row.get("team") == team and row.get("side") == "over":
                try:
                    if abs(float(row.get("point")) - point) < 0.001:
                        return float(row.get("price"))
                except (TypeError, ValueError):
                    pass
        return None
    if side.startswith("ТБ "):
        try:
            point = float(side.removeprefix("ТБ ").strip())
        except ValueError:
            return None
        for row in best.get("totals") or []:
            if row.get("side") == "over":
                try:
                    if abs(float(row.get("point")) - point) < 0.001:
                        return float(row.get("price"))
                except (TypeError, ValueError):
                    pass
    if side in ("ОЗ ДА", "ОЗ НЕТ"):
        value = (best.get("btts") or {}).get("yes" if side == "ОЗ ДА" else "no")
        return float(value) if isinstance(value, (int, float)) else None
    return None


def ai_candidate(match: dict, now: datetime) -> bool:
    if match.get("status") not in ("SCHEDULED", "TIMED"):
        return False
    try:
        kickoff = datetime.fromisoformat(str(match.get("utcDate") or "").replace("Z", "+00:00"))
        if kickoff.tzinfo is None:
            kickoff = kickoff.replace(tzinfo=timezone.utc)
        kickoff = kickoff.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return False
    if kickoff < now or kickoff > now + timedelta(days=30):
        return False

    prediction = match.get("prediction") or {}
    signal = prediction.get("strongestSignal")
    if not isinstance(signal, dict) or signal.get("paperOnly") is True:
        # UEFA paper-only signals do not use the model's lineup adjustment.
        return False
    if signal.get("noPositiveSignal") or str(signal.get("side") or "").upper() == "ОЗ НЕТ":
        return False
    try:
        probability = float(signal.get("modelProbability"))
        price = signal_price(signal, match)
        if price is None or price < 1.50:
            return False
        ev = probability * price - 1.0
        implied_edge = probability - 1.0 / price + (
            float(signal.get("formAdjustmentPp") or 0.0)
            + float(signal.get("lineupAdjustmentPp") or 0.0)
        ) / 100.0
        selection_edge = float(signal.get("selectionEdgePp", signal.get("edgePp", 0.0)) or 0.0)
        return probability >= 0.60 and selection_edge > 0.0 and implied_edge >= 0.05 and ev >= 0.05
    except (TypeError, ValueError, ZeroDivisionError):
        return False


def run() -> dict:
    api_key = os.environ.get("API_FOOTBALL_KEY", "").strip()
    if not api_key:
        raise RuntimeError("API_FOOTBALL_KEY is not configured")
    if not DATA_PATH.exists():
        raise RuntimeError("docs/data.json is missing")
    payload = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    cache = load_cache(CACHE_PATH)
    now = datetime.now(timezone.utc)

    candidates = {}
    for competition in payload.get("competitions", []):
        for match in competition.get("matches", []):
            if ai_candidate(match, now) and match.get("id") is not None:
                candidates[str(match["id"])] = match

    if not candidates:
        return {"aiCandidates": 0, "apiFootballRequestsThisRun": 0,
                "message": "No upcoming AI candidates qualify for lineup lookup"}

    run_limit = int(os.environ.get("API_FOOTBALL_MAX_REQUESTS_PER_RUN", "20"))
    daily_limit = int(os.environ.get("API_FOOTBALL_DAILY_REQUEST_LIMIT", "100"))
    summary = refresh_lineups(
        cache, api_key,
        eligible_match_ids=set(candidates),
        max_lineup_requests=run_limit,
        max_api_requests=run_limit,
        daily_request_limit=daily_limit,
        horizon_hours=30 * 24,
    )
    save_cache(cache, CACHE_PATH)

    s, base, results = build_model_frames(cache)
    st = strengths(s, base) if not s.empty else {}
    names = {code: sorted(s.loc[s.comp == code, "team"].dropna().unique().tolist())
             for code in MAP} if not s.empty else {}

    changed = 0
    for match_id, match in candidates.items():
        row = cache.get("matches", {}).get(match_id)
        if not isinstance(row, dict) or not isinstance(row.get("lineups"), dict):
            continue
        match["lineups"] = row["lineups"]
        if row.get("lineupsFetchedAt"):
            match["lineupsFetchedAt"] = row["lineupsFetchedAt"]
        league = next((item.get("league") for item in payload.get("competitions", [])
                       if any(str(m.get("id")) == match_id for m in item.get("matches", []))), None)
        if league and not s.empty:
            prediction, warning = predict(
                league, match.get("home"), match.get("away"), st, base, names,
                results, str(match.get("utcDate") or "")[:10],
                lineups=row.get("lineups"), predicted_lineups=row.get("predictedLineups"),
            )
            if prediction is not None:
                match["prediction"] = prediction
                match["predictionWarning"] = warning
        changed += 1

    if changed:
        payload["lineupsUpdatedAt"] = datetime.now(timezone.utc).isoformat()
        tmp = DATA_PATH.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(DATA_PATH)

    return {"aiCandidates": len(candidates), "matchesWithLineups": changed,
            **summary}


if __name__ == "__main__":
    print(json.dumps(run(), ensure_ascii=False))
