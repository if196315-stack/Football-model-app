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
ODDS_ARCHIVE = ROOT / "artifacts" / "all-eight-competitions-melbet-snapshots.jsonl"
PAPER_BETS = ROOT / "artifacts" / "uefa-paper-bets.jsonl"
SETTLEMENTS = ROOT / "artifacts" / "uefa-paper-settlements.jsonl"
OUTPUT = ROOT / "docs" / "uefa-data.json"
LEAGUES = {
    "UCL": "Лига чемпионов УЕФА",
    "UEL": "Лига Европы УЕФА",
    "UECL": "Лига конференций УЕФА",
}
MAX_QUOTE_AGE_SECONDS = 12 * 60 * 60
EXCLUDED_PAPER_SIGNAL_IDS = {"c3b279f109074fcbbf0c", "28ecfccda8f24626566b", "a4492587108500498f80", "865839eb433e43cd854d", "f1fba97b5a003a622528", "d23779ab177afc4ee37f", "16998bfe36cf27b25488", "6e819e3d7241a187e90b", "49b06e91a7cdf8f8d5f7", "cccf6eeab148e7fa93c1", "cc5974e18a0d3dac1d31", "1764459527a687260408"}


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
                or row.get("prematch_active") is not True
                or row.get("bookmaker") != "MELBET"
                or row.get("source") != "API-Sport"
                or row.get("market_parser_version") not in ("melbet-fulltime-v2", "melbet-fulltime-v3")
                or source_time is None):
            continue
        current_age = (now - source_time).total_seconds()
        if -300 <= current_age <= MAX_QUOTE_AGE_SECONDS:
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
        "bookmaker": "MELBET",
        "best": best,
        "updatedAt": board_time or captured_at,
        "source": "API-Sport / MelBet",
        "freshnessStatus": "fresh",
        "prematchActive": True,
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


def app_match_status(raw: Any) -> str:
    """Normalize PitchAPI fixture states without collapsing live play into FINISHED."""
    value = str(raw or "").strip().casefold().replace("-", "_").replace(" ", "_")
    return {
        "not_started": "SCHEDULED",
        "scheduled": "SCHEDULED",
        "timed": "TIMED",
        "in_play": "IN_PLAY",
        "in_play_first_half": "IN_PLAY",
        "in_play_second_half": "IN_PLAY",
        "first_half": "IN_PLAY",
        "second_half": "IN_PLAY",
        "in_progress": "IN_PLAY",
        "inprogress": "IN_PLAY",
        "in progress": "IN_PLAY",
        "live": "IN_PLAY",
        "1h": "IN_PLAY",
        "2h": "IN_PLAY",
        "first_half": "IN_PLAY",
        "second_half": "IN_PLAY",
        "paused": "PAUSED",
        "halftime": "PAUSED",
        "half_time": "PAUSED",
        "break": "PAUSED",
        "finished": "FINISHED",
        "full_time": "FINISHED",
        "ft": "FINISHED",
        "after_extra_time": "FINISHED",
        "after_penalties": "FINISHED",
        "postponed": "POSTPONED",
        "cancelled": "CANCELED",
        "canceled": "CANCELED",
        "awarded": "AWARDED",
    }.get(value, value.upper())


LIVE_MATCH_STATUSES = {"IN_PLAY", "LIVE", "PAUSED"}


def _reuse_previous_fresh_odds(previous_match: dict | None, now: datetime) -> dict | None:
    """Keep published MelBet quotes through fixture-only refreshes, but never past 12h."""
    odds = (previous_match or {}).get("odds")
    if not isinstance(odds, dict) or odds.get("freshnessStatus") != "fresh" or odds.get("prematchActive") is not True:
        return None
    source_time = _parse_time(odds.get("updatedAt"))
    if source_time is None:
        return None
    age = (now - source_time).total_seconds()
    if age < -300 or age > MAX_QUOTE_AGE_SECONDS:
        return None
    return {**odds, "bookmakerUpdateAgeSeconds": round(age, 3)}


def _is_active_top_paper_market(bet: dict) -> bool:
    market = bet.get("market")
    if market == "double_chance":
        return bet.get("outcome") in ("1x", "x2")
    if market == "match_total_over":
        return bet.get("point") == 2.5
    if market == "team_total_over":
        return bet.get("point") in (0.5, 1.5)
    if market == "btts":
        return bet.get("outcome") == "yes"
    return False


def build_feed(
    fixture_cache: dict,
    xg_history: dict,
    odds_rows: list[dict],
    paper_bets: list[dict],
    settlements: list[dict],
    predictions: dict | None = None,
    updated_at: str | None = None,
    previous_archive: dict | None = None,
    now: datetime | None = None,
    previous_feed: dict | None = None,
) -> dict:
    predictions = predictions if predictions is not None else predict_upcoming(xg_history, fixture_cache)
    now = now or datetime.now(timezone.utc)
    archive = {"version": 1, "matches": dict((previous_archive or {}).get("matches") or {})}
    previous_matches: dict[str, dict] = {}
    for competition in (previous_feed or {}).get("competitions") or []:
        code = str(competition.get("code") or competition.get("id") or "").upper()
        for old_match in competition.get("matches") or []:
            previous_matches[f"{code}:{old_match.get('id')}"] = old_match
    # Freeze UEFA forecasts before kickoff, then attach results without rewriting them.
    for fixture_id, fixture in (fixture_cache.get("matches") or {}).items():
        league_code = str(fixture.get("leagueCode") or "").upper()
        if league_code not in LEAGUES:
            continue
        key = f"{league_code}:{fixture_id}"
        existing = archive["matches"].get(key)
        status = str(fixture.get("status") or "").casefold()
        if existing:
            if status == "finished" and fixture.get("homeScore") is not None and fixture.get("awayScore") is not None:
                existing["status"] = "FINISHED"
                existing["homeScore"] = fixture["homeScore"]
                existing["awayScore"] = fixture["awayScore"]
                existing["resultUpdatedAt"] = now.isoformat()
            continue
        model = predictions.get(str(fixture_id))
        if status not in ("not_started", "scheduled", "timed") or model is None:
            continue
        kickoff = _parse_time(fixture.get("date"))
        if kickoff is None or kickoff <= now:
            continue
        archive["matches"][key] = {
            "id": str(fixture_id), "leagueCode": league_code,
            "league": LEAGUES[league_code], "home": fixture.get("home", ""),
            "away": fixture.get("away", ""), "utcDate": fixture.get("date"),
            "predictionCapturedAt": now.isoformat(), "status": "SCHEDULED",
            "prediction": _app_prediction(model), "homeScore": None, "awayScore": None,
        }
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
        # Explicitly retired signal IDs are blocked regardless of whether a
        # settlement exists. They must never reappear from the archived paper
        # bets file, regenerated feed, or an old settlement record.
        if signal_id in EXCLUDED_PAPER_SIGNAL_IDS:
            continue
        settled = settlement_by_signal.get(signal_id)
        # Only publish active supported markets to upcoming TOP. Keep settled
        # legacy records for other markets in the historical feed.
        if settled is None and not _is_active_top_paper_market(bet):
            continue
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
        app_status = app_match_status(status)
        is_upcoming = app_status in ("SCHEDULED", "TIMED")
        is_live = app_status in LIVE_MATCH_STATUSES
        fixture_papers = paper_by_fixture.get(str(fixture_id), [])
        model = predictions.get(str(fixture_id))
        archived = archive["matches"].get(f"{league_code}:{fixture_id}")
        # Show ordinary upcoming fixtures with a model and every fixture carrying
        # a historical paper entry, including after its final score is known.
        # Keep the frozen pre-kickoff prediction visible if kickoff has passed but
        # the provider still reports the fixture as scheduled/timed.
        if is_upcoming and model is None and not fixture_papers and not archived and league_code != "UECL":
            continue
        if not is_upcoming and not is_live and not fixture_papers and not archived:
            continue
        match = {
            "id": str(fixture_id),
            "utcDate": fixture.get("date"),
            "home": fixture.get("home"),
            "away": fixture.get("away"),
            "status": app_status,
            "homeScore": fixture.get("homeScore"),
            "awayScore": fixture.get("awayScore"),
            "elapsed": fixture.get("elapsed"),
            "elapsedExtra": fixture.get("elapsedExtra"),
            "period": ("SECOND_HALF" if status in ("second_half", "in_play_second_half", "2h")
                       else "FIRST_HALF" if status in ("first_half", "in_play_first_half", "1h")
                       else fixture.get("period")),
            "prediction": _app_prediction(model) if model else (archived.get("prediction") if archived else None),
            "odds": (
                build_odds(odds_by_fixture.get(str(fixture_id), []), now)
                or (
                    _reuse_previous_fresh_odds(previous_matches.get(f"{league_code}:{fixture_id}"), now)
                    if not odds_by_fixture.get(str(fixture_id)) else None
                )
            ),
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
        "source": "PitchAPI fixtures/xG + API-Sport fixtures/MelBet",
        "paperOnly": True,
        "predictionArchive": archive,
        "evaluationMatches": [
            item for item in archive["matches"].values()
            if item.get("status") == "FINISHED"
            and item.get("homeScore") is not None and item.get("awayScore") is not None
            and isinstance(item.get("prediction"), dict)
        ],
        "competitions": competitions,
    }


def main() -> int:
    fixture_cache = json.loads(FIXTURE_CACHE.read_text(encoding="utf-8"))
    history = json.loads(XG_HISTORY.read_text(encoding="utf-8"))
    previous_archive = {}
    previous_feed = {}
    if OUTPUT.exists():
        try:
            previous_feed = json.loads(OUTPUT.read_text(encoding="utf-8"))
            previous_archive = previous_feed.get("predictionArchive") or {}
        except (OSError, json.JSONDecodeError):
            previous_feed = {}
            previous_archive = {}
    feed = build_feed(
        fixture_cache, history, read_jsonl(ODDS_ARCHIVE),
        read_jsonl(PAPER_BETS), read_jsonl(SETTLEMENTS),
        previous_archive=previous_archive,
        previous_feed=previous_feed,
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
