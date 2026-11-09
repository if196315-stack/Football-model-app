#!/usr/bin/env python3
"""Evaluate UEFA price snapshots into auditable BET/WAIT/REJECT paper signals.

No bet placement occurs. Reference-only UNDER and DC12 rows are used only for
price comparison and are never emitted as candidate signals.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
ODDS_ARCHIVE = ROOT / "artifacts" / "uefa-betboom-odds-snapshots.jsonl"
SIGNAL_ARCHIVE = ROOT / "artifacts" / "uefa-paper-signals.jsonl"
PAPER_BETS_ARCHIVE = ROOT / "artifacts" / "uefa-paper-bets.jsonl"
MIN_PROBABILITY = 0.60
MIN_EDGE = 0.05
MIN_EV = 0.05
MAX_QUOTE_AGE_SECONDS = 300


def _valid_probability(value: Any) -> bool:
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and 0.0 <= float(value) <= 1.0)


def _market_pair(row: dict, rows: list[dict]) -> dict | None:
    market = row.get("market")
    if market == "match_total_over":
        opposite_market = "match_total_under_reference"
        return next((x for x in rows if x.get("market") == opposite_market
                     and x.get("point") == row.get("point")
                     and x.get("provider_market_group") == row.get("provider_market_group")
                     and x.get("provider_market_group")), None)
    if market == "team_total_over":
        opposite_market = "team_total_under_reference"
        return next((x for x in rows if x.get("market") == opposite_market
                     and x.get("team") == row.get("team")
                     and x.get("point") == row.get("point")
                     and x.get("provider_market_group") == row.get("provider_market_group")
                     and x.get("provider_market_group")), None)
    if market == "btts":
        opposite = "no" if row.get("outcome") == "yes" else "yes"
        return next((x for x in rows if x.get("market") == "btts"
                     and x.get("outcome") == opposite), None)
    if market == "handicap":
        opposite = "away" if row.get("team") == "home" else "home"
        return next((x for x in rows if x.get("market") == "handicap"
                     and x.get("team") == opposite
                     and x.get("point") == row.get("point")
                     and x.get("provider_market_group") == row.get("provider_market_group")), None)
    return None


def evaluate_candidate(row: dict, snapshot_rows: list[dict]) -> dict:
    price = float(row["price"])
    probability = row.get("model_probability")
    opposite = _market_pair(row, snapshot_rows)
    fair_probability = None
    reference_price = None
    edge_method = None
    edge = ev = None
    reasons = []

    if not _valid_probability(probability):
        reasons.append("model_probability_unavailable")
    quote_age = row.get("bookmaker_update_age_seconds")
    if quote_age is None or row.get("freshness_status") == "unknown":
        reasons.append("quote_freshness_unknown")
    elif row.get("freshness_status") == "clock_skew":
        reasons.append("bookmaker_timestamp_clock_skew")
    elif row.get("freshness_status") != "fresh" or float(quote_age) > MAX_QUOTE_AGE_SECONDS:
        reasons.append("quote_older_than_5_minutes")
    if row.get("model_history_max_date") and row.get("kickoff_utc"):
        if str(row["model_history_max_date"])[:10] >= str(row["kickoff_utc"])[:10]:
            reasons.append("same_day_or_future_xg_history")

    if row.get("market") == "double_chance":
        fair_probability = 1.0 / price
        edge_method = "raw_implied_conservative_no_devig"
    elif opposite is None:
        reasons.append("complementary_price_missing")
    elif not _valid_probability(opposite.get("model_probability")):
        reasons.append("complementary_model_probability_unavailable")
    else:
        reference_price = float(opposite["price"])
        if row.get("market") == "handicap":
            push = float(row.get("model_push_probability") or 0.0)
            loss = float(row.get("model_loss_probability") or 0.0)
            win = float(probability) if _valid_probability(probability) else 0.0
            conditional_win = win / (win + loss) if win + loss > 0 else None
            q1, q2 = 1.0 / price, 1.0 / reference_price
            fair_probability = q1 / (q1 + q2) if q1 + q2 > 0 else None
            edge = conditional_win - fair_probability if conditional_win is not None and fair_probability is not None else None
            ev = win * (price - 1.0) - loss
            edge_method = "dnb_conditional_no_vig"
        else:
            q1, q2 = 1.0 / price, 1.0 / reference_price
            fair_probability = q1 / (q1 + q2) if q1 + q2 > 0 else None
            edge_method = "two_way_overround_removed"

    if row.get("market") != "handicap" and _valid_probability(probability):
        ev = float(probability) * price - 1.0
    if edge is None and fair_probability is not None and _valid_probability(probability):
        edge = float(probability) - fair_probability

    if not reasons:
        if float(probability) < MIN_PROBABILITY:
            reasons.append("model_probability_below_60_percent")
        if edge is None or edge < MIN_EDGE:
            reasons.append("edge_below_5_percentage_points")
        if ev is None or ev < MIN_EV:
            reasons.append("expected_value_below_5_percent")

    if any(reason in reasons for reason in (
        "model_probability_unavailable", "quote_freshness_unknown",
        "bookmaker_timestamp_clock_skew", "quote_older_than_5_minutes", "same_day_or_future_xg_history",
        "complementary_price_missing", "complementary_model_probability_unavailable",
    )):
        decision = "WAIT"
    elif reasons:
        decision = "REJECT"
    else:
        decision = "BET"

    identity = "|".join(str(row.get(key) or "") for key in (
        "fixture_id", "captured_at", "market", "provider_market_group", "point", "team", "outcome", "price"
    ))
    signal_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:20]
    return {
        "signal_id": signal_id,
        "captured_at": row.get("captured_at"),
        "fixture_id": row.get("fixture_id"),
        "provider_event_id": row.get("provider_event_id"),
        "league_code": row.get("league_code"),
        "season": row.get("season"),
        "kickoff_utc": row.get("kickoff_utc"),
        "home_team": row.get("home_team"),
        "away_team": row.get("away_team"),
        "market": row.get("market"),
        "market_parser_version": row.get("market_parser_version"),
        "provider_market_slug": row.get("provider_market_slug"),
        "provider_market_group": row.get("provider_market_group"),
        "provider_market_name": row.get("provider_market_name"),
        "point": row.get("point"),
        "team": row.get("team"),
        "outcome": row.get("outcome"),
        "price": price,
        "reference_price": reference_price,
        "model_probability": probability,
        "fair_probability": round(fair_probability, 8) if fair_probability is not None else None,
        "edge": round(edge, 8) if edge is not None else None,
        "expected_value": round(ev, 8) if ev is not None else None,
        "edge_method": edge_method,
        "bookmaker_update_age_seconds": row.get("bookmaker_update_age_seconds"),
        "freshness_status": row.get("freshness_status"),
        "model_version": row.get("model_version"),
        "model_prediction_time": row.get("model_prediction_time"),
        "model_history_max_date": row.get("model_history_max_date"),
        "decision": decision,
        "reasons": reasons or ["all_strategy_thresholds_passed"],
        "paper_only": True,
    }


def read_jsonl(path: str | Path) -> list[dict]:
    rows = []
    source = Path(path)
    if not source.exists():
        return rows
    with source.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def build_signals(odds_rows: list[dict]) -> list[dict]:
    grouped = defaultdict(list)
    for row in odds_rows:
        grouped[(str(row.get("fixture_id")), str(row.get("captured_at")))].append(row)
    signals = []
    for snapshot in grouped.values():
        for row in snapshot:
            if row.get("candidate_market") is not True or row.get("reference_only") is True:
                continue
            if row.get("market_parser_version") != "betboom-fulltime-v2":
                continue
            if row.get("market") not in (
                "double_chance", "match_total_over", "team_total_over", "btts", "handicap"
            ):
                continue
            signals.append(evaluate_candidate(row, snapshot))
    return sorted(signals, key=lambda row: (
        row.get("captured_at") or "", row.get("league_code") or "",
        row.get("fixture_id") or "", row.get("market") or "",
        str(row.get("point") or ""), row.get("team") or "", row.get("outcome") or "",
    ))


def append_new_signals(signals: list[dict], output_path: str | Path = SIGNAL_ARCHIVE) -> dict:
    output = Path(output_path)
    existing = {row["signal_id"] for row in read_jsonl(output)}
    new_rows = [row for row in signals if row["signal_id"] not in existing]
    if new_rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in new_rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    decisions = Counter(row["decision"] for row in new_rows)
    markets = Counter(row["market"] for row in new_rows)
    summary = {
        "signals_evaluated": len(signals),
        "new_signals_appended": len(new_rows),
        "by_decision": dict(sorted(decisions.items())),
        "by_market": dict(sorted(markets.items())),
        "signal_archive": str(output),
    }
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def _paper_bet_key(row: dict) -> tuple:
    return (
        str(row.get("fixture_id") or ""),
        str(row.get("market") or ""),
        str(row.get("point") or ""),
        str(row.get("provider_market_group") or ""),
        str(row.get("team") or ""),
        str(row.get("outcome") or ""),
    )


def append_unique_paper_bets(signals: list[dict],
                             output_path: str | Path = PAPER_BETS_ARCHIVE) -> dict:
    output = Path(output_path)
    existing_rows = read_jsonl(output)
    seen = {_paper_bet_key(row) for row in existing_rows}
    new_rows = []
    for signal in sorted(signals, key=lambda row: (
        row.get("captured_at") or "", row.get("fixture_id") or "",
        row.get("market") or "", str(row.get("point") or ""),
        row.get("team") or "", row.get("outcome") or "",
    )):
        if signal.get("decision") != "BET":
            continue
        key = _paper_bet_key(signal)
        if key in seen:
            continue
        new_rows.append({
            **signal,
            "paper_entry": True,
            "paper_entry_policy": "first_qualifying_snapshot_per_fixture_market",
        })
        seen.add(key)
    if new_rows:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as handle:
            for row in new_rows:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {
        "unique_paper_bets_added": len(new_rows),
        "unique_paper_bets_total": len(seen),
        "paper_bets_archive": str(output),
    }
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--odds", default=str(ODDS_ARCHIVE))
    parser.add_argument("--output", default=str(SIGNAL_ARCHIVE))
    parser.add_argument("--paper-bets-output", default=str(PAPER_BETS_ARCHIVE))
    args = parser.parse_args()
    signals = build_signals(read_jsonl(args.odds))
    append_new_signals(signals, args.output)
    append_unique_paper_bets(signals, args.paper_bets_output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
