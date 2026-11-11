#!/usr/bin/env python3
"""Build the isolated UEFA competition feed consumed by the Android match centre.

This feed is separate from docs/data.json so the UEFA collector never mutates the
production domestic model data. It reuses the normal competition/match schema.
"""
from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from predict_uefa_markets import predict_upcoming

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
XG_HISTORY = ROOT / "artifacts" / "uefa-xg-history.json"
ODDS_ARCHIVE = ROOT / "artifacts" / "uefa-betboom-odds-snapshots.jsonl"
PAPER_BETS = ROOT / "artifacts" / "uefa-paper-bets.jsonl"
SETTLEMENTS = ROOT / "artifacts" / "uefa-paper-settlements.jsonl"
OUTPUT = ROOT / "docs" / "uefa-data.json"
LEAGUES = {
    "UCL": "Лига чемпионов УЕФА",
    "UEL": "Лига Европы УЕФА",
    "UECL": "Лига конференций УЕФА",
}
MAX_QUOTE_AGE_SECONDS = 300


def read_jsonl(path: str | Path) -> list[dict]:
    source = Path(path)
    if not source.exists():
        return []
    with source.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
    except ValueError:
        return None


def _valid_fresh_snapshot(rows: list[dict], now: datetime) -> list[dict]:
    if not rows:
        return []
    latest = max(str(row.get("captured_at") or "") for row in rows)
    selected = [row for row in rows if str(row.get("captured_at") or "") == latest]
    fresh = []
    for row in selected:
        source_time = _parse_time(row.get("bookmaker_updated_at"))
        if (row.get("freshness_status") != "fresh"
                or row.get("market_parser_version") != "betboom-fulltime-v2"
                or source_time is None):
            continue
        current_age = (now - source_time).total_seconds()
        if 0 <= current_age <= MAX_QUOTE_AGE_SECONDS:
            fresh.append(row)
    return fresh


def build_odds(rows: list[dict], now: datetime | None = None) -> dict | None:
    now = now or datetime.now(timezone.utc)
    fresh = _valid_fresh_snapshot(rows, now)
    if not fresh:
        return None
    best: dict[str, Any] = {
        "doubleChance": {}, "totals": [], "teamTotals": [], "btts": {}, "spreads": []
    }
    for row in fresh:
        market = row.get("market")
        price = row.get("price")
        if not isinstance(price, (int, float)) or price <= 1:
            continue
        if market == "double_chance" and row.get("outcome") in ("1x", "x2"):
            best["doubleChance"][row["outcome"]] = float(price)
        elif market == "match_total_over" and row.get("side") == "over":
            best["totals"].append({
                "point": float(row["point"]), "side": "over", "price": float(price)
            })
        elif market == "team_total_over" and row.get("side") == "over":
            best["teamTotals"].append({
                "team": row["team"], "point": float(row["point"]),
                "side": "over", "price": float(price)
            })
        elif market == "btts" and row.get("outcome") in ("yes", "no"):
            best["btts"][row["outcome"]] = float(price)
        elif market == "handicap" and row.get("team") in ("home", "away"):
            best["spreads"].append({
                "team": row["team"], "point": float(row.get("point") or 0),
                "price": float(price)
            })
    best["totals"].sort(key=lambda x: x["point"])
    best["teamTotals"].sort(key=lambda x: (x["team"], x["point"]))
    if not any((best["doubleChance"], best["totals"], best["teamTotals"], best["btts"], best["spreads"])):
        return None
    board_time = next((r.get("bookmaker_updated_at") for r in fresh if r.get("bookmaker_updated_at")), None)
    captured_at = max(str(r.get("captured_at") or "") for r in fresh)
    return {
        "best": best,
        "updatedAt": board_time or captured_at,
        "source": "API-Sport / BetBoom",
        "freshnessStatus": "fresh",
        "bookmakerUpdateAgeSeconds": round(max((now - _parse_time(r["bookmaker_updated_at"])).total_seconds() for r in fresh), 3),
    }


def _app_prediction(model: dict) -> dict:
    markets = model["markets"]
    match_total = markets["match_total"]["over"]
    home_total = markets["team_total"]["home"]["over"]
    away_total = markets["team_total"]["away"]["over"]
    return {
        "1x": markets["double_chance"]["1x"],
        "x2": markets["double_chance"]["x2"],
        "over15": match_total["1.5"],
        "over25": match_total["2.5"],
        "over35": match_total["3.5"],
        "homeOver05": home_total["0.5"],
        "homeOver15": home_total["1.5"],
        "homeOver25": home_total["2.5"],
        "homeOver35": home_total["3.5"],
        "awayOver05": away_total["0.5"],
        "awayOver15": away_total["1.5"],
        "awayOver25": away_total["2.5"],
        "awayOver35": away_total["3.5"],
        "bttsYes": markets["btts"]["yes"],
        "bttsNo": markets["btts"]["no"],
        "expectedHomeGoals": model["home_lambda"],
        "expectedAwayGoals": model["away_lambda"],
        "strongestSignal": None,
        "modelVersion": model["model_version"],
        "historyMaxDate": model.get("history_max_date"),
    }


def build_feed(
    fixture_cache: dict,
    xg_history: dict,
    odds_rows: list[dict],
    paper_bets: list[dict],
    settlements: list[dict],
    predictions: dict | None = None,
    updated_at: str | None = None,
) -> dict:
    predictions = predictions if predictions is not None else predict_upcoming(xg_history, fixture_cache)
    odds_by_fixture: dict[str, list[dict]] = defaultdict(list)
    for row in odds_rows:
        if row.get("league_code") in LEAGUES:
            odds_by_fixture[str(row.get("fixture_id"))].append(row)

    settlement_by_signal = {str(row.get("signal_id")): row for row in settlements}
    paper_by_fixture: dict[str, list[dict]] = defaultdict(list)
    for bet in paper_bets:
        if bet.get("paper_only") is not True or bet.get("decision", "BET") != "BET":
            continue
        signal_id = str(bet.get("signal_id") or "")
        settled = settlement_by_signal.get(signal_id)
        item = {
            "signal_id": signal_id,
            "market": bet.get("market"),
            "point": bet.get("point"),
            "team": bet.get("team"),
            "outcome": bet.get("outcome"),
            "price": bet.get("price"),
            "model_probability": bet.get("model_probability"),
            "edge": bet.get("edge"),
            "expected_value": bet.get("expected_value"),
            "result": settled.get("result") if settled else None,
            "profit_units": settled.get("profit_units") if settled else None,
            "home_score": settled.get("home_score") if settled else None,
            "away_score": settled.get("away_score") if settled else None,
        }
        paper_by_fixture[str(bet.get("fixture_id"))].append(item)

    grouped: dict[str, list[dict]] = {code: [] for code in LEAGUES}
    for fixture_id, fixture in (fixture_cache.get("matches") or {}).items():
        league_code = str(fixture.get("leagueCode") or "").upper()
        if league_code not in LEAGUES:
            continue
        status = str(fixture.get("status") or "").casefold()
        is_upcoming = status in ("not_started", "scheduled", "timed")
        fixture_papers = paper_by_fixture.get(str(fixture_id), [])
        model = predictions.get(str(fixture_id))
        # Show ordinary upcoming fixtures with a model and every fixture carrying
        # a historical paper entry, including after its final score is known.
        if is_upcoming and model is None and not fixture_papers and league_code != "UECL":
            continue
        if not is_upcoming and not fixture_papers:
            continue
        match = {
            "id": str(fixture_id),
            "utcDate": fixture.get("date"),
            "home": fixture.get("home"),
            "away": fixture.get("away"),
            "status": "SCHEDULED" if status in ("not_started", "scheduled") else (
                "TIMED" if status == "timed" else "FINISHED"
            ),
            "homeScore": fixture.get("homeScore"),
            "awayScore": fixture.get("awayScore"),
            "prediction": _app_prediction(model) if model else None,
            "odds": build_odds(odds_by_fixture.get(str(fixture_id), [])),
            "paperSignals": fixture_papers,
            "paperOnly": True,
        }
        grouped[league_code].append(match)

    competitions = []
    for code, name in LEAGUES.items():
        matches = sorted(grouped[code], key=lambda row: (str(row.get("utcDate") or ""), row["home"] or ""))
        competitions.append({
            "id": code,
            "code": code,
            "name": name,
            "matches": matches,
        })
    return {
        "schemaVersion": 1,
        "updatedAt": updated_at or datetime.now(timezone.utc).isoformat(),
        "source": "PitchAPI fixtures/xG + API-Sport fixtures/BetBoom",
        "paperOnly": True,
        "competitions": competitions,
    }


def main() -> int:
    fixture_cache = json.loads(FIXTURE_CACHE.read_text(encoding="utf-8"))
    history = json.loads(XG_HISTORY.read_text(encoding="utf-8"))
    feed = build_feed(
        fixture_cache, history, read_jsonl(ODDS_ARCHIVE),
        read_jsonl(PAPER_BETS), read_jsonl(SETTLEMENTS),
    )
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(OUTPUT)
    counts = {
        code: {
            "matches": len(next(c for c in feed["competitions"] if c["code"] == code)["matches"]),
            "paper_signals": sum(len(m["paperSignals"]) for m in next(c for c in feed["competitions"] if c["code"] == code)["matches"]),
        }
        for code in LEAGUES
    }
    print(json.dumps({"output": str(OUTPUT), "updated_at": feed["updatedAt"], "competitions": counts}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
