#!/usr/bin/env python3
"""Capture timestamped MelBet odds for all five domestic leagues and three UEFA club competitions.

The archive stores only the strategy's supported markets and exact line values.
It records both capture time and the bookmaker board's source-side prematch update
timestamp and attaches pre-match probabilities from the UEFA xG/Poisson model
when the competition-local paired shot-xG history is available.
"""
from __future__ import annotations

import argparse
import json
import os
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

from update_odds_api_sport import URL, match_fixture, parse_odds
from predict_uefa_markets import predict_upcoming

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_CACHE = ROOT / "backend" / "cache" / "pitchapi_uefa_fixtures.json"
ARCHIVE = ROOT / "artifacts" / "all-eight-competitions-melbet-snapshots.jsonl"
DOMESTIC_CACHE = ROOT / "backend" / "cache" / "pitchapi_history.json"
ALLOWED_TOTALS = {1.5, 2.5, 3.5}
ALLOWED_TEAM_TOTALS = {0.5, 1.0, 1.5, 2.5, 3.5}
LOOKAHEAD_DAYS = 30
MAX_QUOTE_AGE_SECONDS = 12 * 60 * 60
MARKET_PARSER_VERSION = "melbet-fulltime-v3"


def valid_price(value: Any) -> bool:
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and 1.0 < float(value) < 1000.0)


def _board_timestamp(book: dict) -> str | None:
    by_stage = book.get("updatedAtByStage") or {}
    raw = by_stage.get("prematch") or book.get("updatedAt")
    try:
        return datetime.fromtimestamp(int(raw) / 1000, tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def _row(common: dict, market: str, price: Any, **fields) -> dict | None:
    if not valid_price(price):
        return None
    candidate_market = fields.pop("candidate_market", True)
    reference_only = fields.pop("reference_only", False)
    model_probability = fields.pop("model_probability", None)
    model_push_probability = fields.pop("model_push_probability", None)
    model_loss_probability = fields.pop("model_loss_probability", None)
    probability_valid = (
        isinstance(model_probability, (int, float)) and not isinstance(model_probability, bool)
        and 0.0 <= float(model_probability) <= 1.0
    )
    return {
        **common, "market": market, **fields,
        "candidate_market": candidate_market,
        "reference_only": reference_only,
        "price": round(float(price), 6),
        "model_probability": round(float(model_probability), 8) if probability_valid else None,
        "model_push_probability": round(float(model_push_probability), 8)
            if isinstance(model_push_probability, (int, float)) else None,
        "model_loss_probability": round(float(model_loss_probability), 8)
            if isinstance(model_loss_probability, (int, float)) else None,
        "model_probability_status": "available" if probability_valid else "not_available_yet",
    }


def snapshot_rows(cache: dict, provider_fixtures: list[dict],
                  captured_at: str | None = None,
                  diagnostics: dict | None = None,
                  predictions: dict | None = None) -> list[dict]:
    captured_at = captured_at or datetime.now(timezone.utc).isoformat()
    rows = []
    predictions = predictions or {}
    eligible_fixtures = matched_fixtures = melbet_boards = active_prematch_boards = fixtures_with_selected_markets = matches_with_model_prediction = 0
    model_predictions_by_league = Counter()
    for fixture_id, match in (cache.get("matches") or {}).items():
        league = str(match.get("leagueCode") or "").upper()
        if league not in ("UCL", "UEL", "UECL", "ENG", "ESP", "ITA", "GER", "FRA"):
            continue
        if str(match.get("status") or "").casefold() not in ("not_started", "scheduled", "timed"):
            continue
        eligible_fixtures += 1
        target = {
            "utcDate": match.get("date"),
            "home": match.get("home"),
            "away": match.get("away"),
        }
        if not target["utcDate"] or not target["home"] or not target["away"]:
            continue
        source = None
        if match.get("provider") == "API-Sport" and match.get("providerEventId") is not None:
            provider_id = str(match.get("providerEventId"))
            source = next((row for row in provider_fixtures
                           if str(row.get("id")) == provider_id), None)
        if source is None:
            source = match_fixture(target, provider_fixtures)
        if not source:
            continue
        matched_fixtures += 1
        prediction = predictions.get(str(fixture_id))
        if prediction:
            matches_with_model_prediction += 1
            model_predictions_by_league[league] += 1
        model_markets = (prediction or {}).get("markets") or {}
        bookmaker_board = (source.get("oddsBk") or {}).get("melbet") or {}
        if bookmaker_board:
            melbet_boards += 1
        quote = parse_odds(source)
        prematch_active = quote.get("prematchActive") is True
        if prematch_active:
            active_prematch_boards += 1
        board_updated_at = _board_timestamp(bookmaker_board)
        try:
            board_age_seconds = (
                datetime.fromisoformat(captured_at.replace("Z", "+00:00"))
                - datetime.fromisoformat(board_updated_at.replace("Z", "+00:00"))
            ).total_seconds() if board_updated_at else None
        except ValueError:
            board_age_seconds = None
        freshness_status = (
            "unknown" if board_age_seconds is None else
            "clock_skew" if board_age_seconds < -300 else
            "fresh" if board_age_seconds <= MAX_QUOTE_AGE_SECONDS else "stale"
        )
        best = quote.get("best") or {}
        if best:
            fixtures_with_selected_markets += 1
        common = {
            "schema_version": 1,
            "captured_at": captured_at,
            "bookmaker_updated_at": board_updated_at,
            "bookmaker_update_age_seconds": round(board_age_seconds, 3) if board_age_seconds is not None else None,
            "freshness_status": freshness_status,
            "source": "API-Sport",
            "bookmaker": "MELBET",
            "prematch_active": prematch_active,
            "market_parser_version": MARKET_PARSER_VERSION,
            "league_code": league,
            "season": match.get("season"),
            "fixture_id": str(match.get("sourceFixtureId") or fixture_id),
            "fixture_namespace": str(match.get("fixtureNamespace") or ("UEFA" if league in ("UCL", "UEL", "UECL") else "DOM")),
            "provider_event_id": str(source.get("id") or ""),
            "kickoff_utc": match.get("date"),
            "home_team": match.get("home"),
            "away_team": match.get("away"),
            "model_version": (prediction or {}).get("model_version"),
            "model_prediction_time": (prediction or {}).get("predicted_at"),
            "model_history_max_date": (prediction or {}).get("history_max_date"),
        }
        # A status-only row supersedes older active quotes if the board becomes
        # inactive, has no supported lines, or no longer has a fresh timestamp.
        if not prematch_active or not best:
            rows.append({**common, "market": "board_status", "price": None})
            continue
        for outcome in ("1x", "x2", "12"):
            price = (best.get("doubleChance") or {}).get(outcome)
            is_reference = outcome == "12"
            item = _row(common, "double_chance", price, outcome=outcome,
                        candidate_market=not is_reference, reference_only=is_reference,
                        model_probability=(model_markets.get("double_chance") or {}).get(outcome))
            if item:
                rows.append(item)

        for item in best.get("totals") or []:
            point = item.get("point")
            side = item.get("side")
            if side not in ("over", "under") or point not in ALLOWED_TOTALS:
                continue
            is_reference = side == "under"
            market = "match_total_under_reference" if is_reference else "match_total_over"
            row = _row(common, market, item.get("price"), point=float(point), side=side,
                       candidate_market=not is_reference, reference_only=is_reference,
                       model_probability=((model_markets.get("match_total") or {}).get(side) or {}).get(str(float(point))),
                       provider_market_slug=item.get("market_slug"),
                       provider_market_group=item.get("market_group"),
                       provider_market_name=item.get("market_name"))
            if row:
                rows.append(row)

        for item in best.get("teamTotals") or []:
            point = item.get("point")
            team = item.get("team")
            side = item.get("side")
            if (side not in ("over", "under") or team not in ("home", "away")
                    or point not in ALLOWED_TEAM_TOTALS):
                continue
            is_reference = side == "under"
            market = "team_total_under_reference" if is_reference else "team_total_over"
            asian_team = (model_markets.get("team_total_asian") or {}).get(team) or {}
            is_asian_one = abs(float(point) - 1.0) < 0.001 and side == "over"
            row = _row(
                common, market, item.get("price"), team=team, point=float(point),
                side=side, candidate_market=not is_reference, reference_only=is_reference,
                model_probability=(asian_team.get("win") if is_asian_one else
                    (((model_markets.get("team_total") or {}).get(team) or {}).get(side) or {}).get(str(float(point)))),
                model_push_probability=(asian_team.get("push") if is_asian_one else None),
                model_loss_probability=(asian_team.get("lose") if is_asian_one else None),
                provider_market_slug=item.get("market_slug"),
                provider_market_group=item.get("market_group"),
                provider_market_name=item.get("market_name"),
            )
            if row:
                rows.append(row)

        for outcome in ("yes", "no"):
            price = (best.get("btts") or {}).get(outcome)
            row = _row(common, "btts", price, outcome=outcome,
                       model_probability=(model_markets.get("btts") or {}).get(outcome))
            if row:
                rows.append(row)

        # The current parser supports only the explicitly ingested zero line.
        for item in best.get("spreads") or []:
            if item.get("team") not in ("home", "away"):
                continue
            try:
                point = float(item.get("point"))
            except (TypeError, ValueError):
                continue
            if min(abs(point), abs(point - 1.0)) > 0.001:
                continue
            handicap_model = ((model_markets.get("handicap_asian") or {}).get(item["team"]) or {}).get(str(point)) or {}
            if not handicap_model and abs(point) < 0.001:
                handicap_model = (model_markets.get("handicap_zero") or {}).get(item["team"]) or {}
            row = _row(common, "handicap", item.get("price"),
                       team=item["team"], point=point,
                       model_probability=handicap_model.get("win"),
                       model_push_probability=handicap_model.get("push"),
                       model_loss_probability=handicap_model.get("lose"),
                       provider_market_slug=item.get("market_slug"),
                       provider_market_group=item.get("market_group"),
                       provider_market_name=item.get("market_name"))
            if row:
                rows.append(row)

    if diagnostics is not None:
        diagnostics.update({
            "eligible_supported_upcoming_fixtures_in_cache": eligible_fixtures,
            "eligible_uefa_upcoming_fixtures_in_cache": eligible_fixtures,
            "api_sport_provider_fixtures": len(provider_fixtures),
            "matched_pitchapi_fixtures": matched_fixtures,
            "matches_with_melbet_board": melbet_boards,
            "matches_with_active_prematch_board": active_prematch_boards,
            "matches_with_selected_markets": fixtures_with_selected_markets,
            "matches_with_model_prediction": matches_with_model_prediction,
            "matches_without_model_prediction": max(0, matched_fixtures - matches_with_model_prediction),
            "model_predictions_by_competition": dict(sorted(model_predictions_by_league.items())),
        })
    return rows


def fetch_provider_fixtures(api_key: str, session: Any = requests,
                            today=None) -> list[dict]:
    if not api_key:
        raise RuntimeError("API_SPORT_KEY is not configured")
    start = today or datetime.now(timezone.utc).date()
    params = {
        "date_from": start.isoformat(),
        "date_to": (start + timedelta(days=LOOKAHEAD_DAYS)).isoformat(),
        "exclude_amateur": "true",
        "view": "odds",
        "with_bk_odds": "true",
        "odds_stage": "all",
        "bookmaker_ids": "melbet",
        "page_size": 500,
        "sort": "asc",
        "page": 1,
    }
    # API-Sport caps date ranges at 31 inclusive days. Fetch the full 180-day
    # domestic calendar in bounded windows, then add tournament-specific UEFA
    # queries because some European fixtures are omitted by generic date search.
    fixtures = []
    final_date = start + timedelta(days=180)
    cursor = start
    while cursor <= final_date:
        window_end = min(cursor + timedelta(days=30), final_date)
        window_params = {
            **params,
            "date_from": cursor.isoformat(),
            "date_to": window_end.isoformat(),
        }
        response = session.get(URL, params=window_params,
                               headers={"Authorization": api_key}, timeout=60)
        response.raise_for_status()
        payload = response.json()
        fixtures.extend(list(payload) if isinstance(payload, list) else list(payload.get("matches") or []))
        total_pages = min(int(payload.get("totalPages") or 1), 100) if isinstance(payload, dict) else 1
        for page in range(2, total_pages + 1):
            page_params = {**window_params, "page": page}
            page_response = session.get(URL, params=page_params,
                                        headers={"Authorization": api_key}, timeout=60)
            page_response.raise_for_status()
            page_payload = page_response.json()
            fixtures.extend(page_payload if isinstance(page_payload, list)
                            else page_payload.get("matches") or [])
        cursor = window_end + timedelta(days=1)
    # UEFA competitions are also fetched by tournament ID to cover fixtures
    # omitted from generic date search. IDs: UCL 7, UEL 679, UECL 17015.
    tournament_ids = {"UCL": 7, "UEL": 679, "UECL": 17015}
    cursor = start
    for tournament_id in tournament_ids.values():
        cursor = start
        while cursor <= final_date:
            window_end = min(cursor + timedelta(days=30), final_date)
            tournament_params = {
                **params,
                "tournament_id": str(tournament_id),
                "date_from": cursor.isoformat(),
                "date_to": window_end.isoformat(),
            }
            tournament_response = session.get(
                URL, params=tournament_params,
                headers={"Authorization": api_key}, timeout=60,
            )
            tournament_response.raise_for_status()
            tournament_payload = tournament_response.json()
            fixtures.extend(tournament_payload if isinstance(tournament_payload, list)
                            else tournament_payload.get("matches") or [])
            cursor = window_end + timedelta(days=1)
    unique = {}
    for row in fixtures:
        if row.get("id") is None:
            continue
        fixture_id = str(row["id"])
        if fixture_id in unique:
            unique[fixture_id] = merge_duplicate_fixture_rows(unique[fixture_id], row)
        else:
            unique[fixture_id] = row
    return list(unique.values())


def _board_quote_count(board: Any) -> int:
    """Count usable quote lines on a bookmaker board, ignoring suspended prices."""
    if not isinstance(board, dict):
        return 0
    count = 0
    for market in (board.get("markets") or {}).values():
        if not isinstance(market, dict):
            continue
        for stake in (market.get("stakes") or {}).values():
            if not isinstance(stake, dict) or stake.get("suspended"):
                continue
            lines = stake.get("lines") or []
            if lines:
                count += sum(
                    1 for line in lines
                    if isinstance(line, dict) and not line.get("suspended")
                    and valid_price(line.get("factor"))
                )
            elif valid_price(stake.get("factor")):
                count += 1
    return count


def _board_update_ms(board: dict) -> int | None:
    raw = ((board.get("updatedAtByStage") or {}).get("prematch")
           or board.get("updatedAt"))
    try:
        return int(raw)
    except (TypeError, ValueError, OverflowError):
        return None


def merge_duplicate_fixture_rows(existing: dict, incoming: dict) -> dict:
    """Merge duplicate fixtures without allowing an older active board to beat a newer status."""
    merged = {**existing, **incoming}
    books = {}
    old_books = existing.get("oddsBk") or {}
    new_books = incoming.get("oddsBk") or {}
    for bookmaker in set(old_books) | set(new_books):
        old_board = old_books.get(bookmaker)
        new_board = new_books.get(bookmaker)
        if isinstance(old_board, dict) and isinstance(new_board, dict):
            old_time, new_time = _board_update_ms(old_board), _board_update_ms(new_board)
            old_active = (old_board.get("isBettingActiveByStage") or {}).get("prematch")
            new_active = (new_board.get("isBettingActiveByStage") or {}).get("prematch")
            if old_time is not None and new_time is not None and old_time != new_time:
                chosen = new_board if new_time > old_time else old_board
            elif old_active is False or new_active is False:
                # With equal/unknown source times, fail closed on an explicit inactive flag.
                chosen = new_board if new_active is False else old_board
            elif old_active is True or new_active is True:
                # Prefer an explicitly active board over an unknown-state duplicate.
                chosen = new_board if new_active is True else old_board
            else:
                # Legacy duplicates without board timestamps retain the richest payload.
                chosen = new_board if _board_quote_count(new_board) >= _board_quote_count(old_board) else old_board
            books[bookmaker] = chosen
        elif isinstance(new_board, dict):
            books[bookmaker] = new_board
        elif isinstance(old_board, dict):
            books[bookmaker] = old_board
    if books:
        merged["oddsBk"] = books
    else:
        merged.pop("oddsBk", None)
    return merged


def append_rows(rows: list[dict], output_path: str | Path = ARCHIVE,
                diagnostics: dict | None = None) -> dict:
    output = Path(output_path)
    if rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    by_league = Counter(row["league_code"] for row in rows)
    by_market = Counter(row["market"] for row in rows)
    summary = {
        "captured_at": rows[0]["captured_at"] if rows else datetime.now(timezone.utc).isoformat(),
        "records_appended": len(rows),
        "by_competition": dict(sorted(by_league.items())),
        "by_market": dict(sorted(by_market.items())),
        "archive": str(output),
        "archive_written": bool(rows),
        **(diagnostics or {}),
    }
    print(json.dumps(summary, ensure_ascii=False))
    return summary



def combine_pitchapi_sources(uefa_cache: dict, domestic_cache: dict,
                             uefa_history: dict | None = None) -> tuple[dict, dict]:
    """Build one namespaced fixture view and paired-xG history for all eight competitions."""
    combined_matches = {}
    history_rows = []
    for fixture_id, row in (uefa_cache.get("matches") or {}).items():
        copied = {**row, "id": f"UEFA:{row.get('id', fixture_id)}", "sourceFixtureId": str(row.get("id", fixture_id)), "fixtureNamespace": "UEFA"}
        combined_matches[copied["id"]] = copied
    for fixture_id, row in (domestic_cache.get("matches") or {}).items():
        copied = {**row, "id": f"DOM:{row.get('id', fixture_id)}", "sourceFixtureId": str(row.get("id", fixture_id)), "fixtureNamespace": "DOM"}
        combined_matches[copied["id"]] = copied
        if (row.get("status") == "finished" and row.get("hasPlayoff") is not True
                and row.get("homeScore") is not None and row.get("awayScore") is not None
                and row.get("homeXg") is not None and row.get("awayXg") is not None):
            try:
                hx, ax = float(row["homeXg"]), float(row["awayXg"])
            except (TypeError, ValueError, OverflowError):
                continue
            history_rows.append({**row, "id": f"DOM:{row.get('id', fixture_id)}",
                                 "pairedXg": True, "homeXg": hx, "awayXg": ax})
    history_rows.extend((uefa_history or {}).get("matches") or [])
    return {"matches": combined_matches}, {"matches": history_rows}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-cache", default=str(FIXTURE_CACHE))
    parser.add_argument("--output", default=str(ARCHIVE))
    parser.add_argument("--xg-history", default="artifacts/uefa-xg-history.json")
    parser.add_argument("--domestic-cache", default=str(DOMESTIC_CACHE))
    args = parser.parse_args()
    cache_path = Path(args.fixture_cache)
    if not cache_path.exists():
        raise RuntimeError(f"UEFA fixture cache is missing: {cache_path}")
    uefa_cache = json.loads(cache_path.read_text(encoding="utf-8"))
    domestic_path = Path(args.domestic_cache)
    domestic_cache = json.loads(domestic_path.read_text(encoding="utf-8")) if domestic_path.exists() else {"matches": {}}

    history_path = ROOT / args.xg_history
    uefa_history = json.loads(history_path.read_text(encoding="utf-8")) if history_path.exists() else {"matches": []}
    cache, history = combine_pitchapi_sources(uefa_cache, domestic_cache, uefa_history)
    provider_fixtures = fetch_provider_fixtures(os.environ.get("API_SPORT_KEY", "").strip())
    captured_at = datetime.now(timezone.utc).isoformat()
    diagnostics = {}
    predictions = predict_upcoming(history, cache, predicted_at=captured_at)
    diagnostics["predicted_upcoming_fixtures"] = len(predictions)
    rows = snapshot_rows(cache, provider_fixtures, captured_at, diagnostics, predictions)
    append_rows(rows, args.output, diagnostics)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
