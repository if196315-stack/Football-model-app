#!/usr/bin/env python3
"""Apply the unified active/fresh MelBet snapshot archive to the five domestic feeds.

Upcoming fixtures are retained only when they have a usable model probability and
at least one matching strategy market with a fresh, active MelBet prematch price.
"""
from __future__ import annotations

# Re-applied snapshots always operate on the latest main feed to preserve calibrated model outputs.

import argparse
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from build_uefa_app_feed import build_odds, read_jsonl

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs" / "data.json"
SNAPSHOTS = ROOT / "artifacts" / "all-eight-competitions-melbet-snapshots.jsonl"
DOMESTIC_CODES = {"PL": "ENG", "PD": "ESP", "SA": "ITA", "BL1": "GER", "FL1": "FRA"}
PROBABILITY_FIELDS = (
    "1x", "x2", "over25", "homeOver05",
    "homeOver15", "awayOver05", "awayOver15", "bttsYes",
)


def _valid_probability(prediction: dict, field: str) -> bool:
    value = prediction.get(field)
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(float(value)) and 0.0 <= float(value) <= 1.0)


def has_complete_strategy_prediction(match: dict) -> bool:
    prediction = match.get("prediction")
    return isinstance(prediction, dict) and any(
        _valid_probability(prediction, field) for field in PROBABILITY_FIELDS
    )


def has_matching_strategy_line(match: dict, odds: dict) -> bool:
    prediction = match.get("prediction") or {}
    best = odds.get("best") or {}

    double_chance = best.get("doubleChance") or {}
    if (_valid_probability(prediction, "1x")
            and isinstance(double_chance.get("1x"), (int, float))
            and double_chance["1x"] > 1.0):
        return True
    if (_valid_probability(prediction, "x2")
            and isinstance(double_chance.get("x2"), (int, float))
            and double_chance["x2"] > 1.0):
        return True

    btts = best.get("btts") or {}
    if (_valid_probability(prediction, "bttsYes")
            and isinstance(btts.get("yes"), (int, float)) and btts["yes"] > 1.0):
        return True

    for row in best.get("totals") or []:
        point = row.get("point")
        field = {2.5: "over25"}.get(point)
        if (row.get("side") == "over" and field
                and _valid_probability(prediction, field)
                and isinstance(row.get("price"), (int, float)) and row["price"] > 1.0):
            return True

    for row in best.get("teamTotals") or []:
        point, team = row.get("point"), row.get("team")
        field = {
            ("home", 0.5): "homeOver05", ("home", 1.5): "homeOver15",
            ("away", 0.5): "awayOver05", ("away", 1.5): "awayOver15",
        }.get((team, point))
        if (row.get("side") == "over" and field
                and _valid_probability(prediction, field)
                and isinstance(row.get("price"), (int, float)) and row["price"] > 1.0):
            return True
    return False


def apply_snapshots(data: dict, rows: list[dict],
                    now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    by_fixture: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in rows:
        league = str(row.get("league_code") or "")
        fixture_id = str(row.get("fixture_id") or "")
        if (league in set(DOMESTIC_CODES.values()) and fixture_id
                and row.get("fixture_namespace") == "DOM"):
            by_fixture[(league, fixture_id)].append(row)

    coverage = {}
    kept_total = eligible_total = 0
    for competition in data.get("competitions", []):
        source_code = str(competition.get("code") or "")
        league = DOMESTIC_CODES.get(source_code)
        if not league:
            continue
        kept = eligible = no_prediction = no_quote = no_matching_line = 0
        kept_matches = []
        for match in competition.get("matches", []):
            if match.get("status") not in ("SCHEDULED", "TIMED"):
                match.pop("odds", None)
                kept_matches.append(match)
                continue
            eligible += 1
            if not has_complete_strategy_prediction(match):
                no_prediction += 1
                continue
            odds = build_odds(by_fixture.get((league, str(match.get("id") or "")), []), now)
            if odds is None:
                no_quote += 1
                continue
            if not has_matching_strategy_line(match, odds):
                no_matching_line += 1
                continue
            match["odds"] = odds
            kept_matches.append(match)
            kept += 1
        competition["matches"] = kept_matches
        coverage[source_code] = {
            "eligible": eligible, "visible": kept,
            "noCompletePrediction": no_prediction,
            "noFreshActiveMelBet": no_quote,
            "noMatchingStrategyLine": no_matching_line,
        }
        eligible_total += eligible
        kept_total += kept

    data["oddsProvider"] = "API-Sport / MelBet unified snapshots"
    data["oddsBookmaker"] = "melbet"
    data["oddsUpdatedAt"] = now.isoformat()
    data["oddsCoverage"] = {
        "eligible": eligible_total, "visible": kept_total,
        "excluded": max(0, eligible_total - kept_total), "byLeague": coverage,
    }
    data["oddsStatus"] = "ready" if kept_total else "no_eligible_matches"
    data["oddsNotice"] = (
        f"В ленте {kept_total} матчей с полным прогнозом и свежей активной линией MelBet."
        if kept_total else
        "Нет матчей, одновременно прошедших проверку полного прогноза и свежей активной линии MelBet."
    )
    return data


def apply_uefa_snapshots(data: dict, rows: list[dict],
                           now: datetime | None = None) -> dict:
    """Apply the same fresh MelBet snapshot and strategy-line gate to UEFA."""
    now = now or datetime.now(timezone.utc)
    codes = {"UCL", "UEL", "UECL"}
    by_fixture: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in rows:
        league = str(row.get("league_code") or "").upper()
        fixture_id = str(row.get("fixture_id") or "")
        if league in codes and fixture_id and row.get("fixture_namespace") == "UEFA":
            by_fixture[(league, fixture_id)].append(row)

    coverage = {}
    eligible_total = visible_total = 0
    for competition in data.get("competitions", []):
        code = str(competition.get("code") or competition.get("id") or "").upper()
        if code not in codes:
            continue
        eligible = visible = no_prediction = no_quote = no_matching_line = 0
        for match in competition.get("matches", []):
            if match.get("status") not in ("SCHEDULED", "TIMED"):
                match.pop("odds", None)
                continue
            eligible += 1
            if not has_complete_strategy_prediction(match):
                match.pop("odds", None)
                no_prediction += 1
                continue
            odds = build_odds(by_fixture.get((code, str(match.get("id") or "")), []), now)
            if odds is None:
                match.pop("odds", None)
                no_quote += 1
                continue
            if not has_matching_strategy_line(match, odds):
                match.pop("odds", None)
                no_matching_line += 1
                continue
            match["odds"] = odds
            visible += 1
        coverage[code] = {
            "eligible": eligible, "visible": visible,
            "noCompletePrediction": no_prediction,
            "noFreshActiveMelBet": no_quote,
            "noMatchingStrategyLine": no_matching_line,
        }
        eligible_total += eligible
        visible_total += visible

    data["oddsProvider"] = "API-Sport / MelBet unified snapshots"
    data["oddsBookmaker"] = "melbet"
    data["oddsUpdatedAt"] = now.isoformat()
    data["oddsCoverage"] = {
        "eligible": eligible_total, "visible": visible_total,
        "excluded": max(0, eligible_total - visible_total), "byLeague": coverage,
    }
    data["oddsStatus"] = "ready" if visible_total else "no_eligible_matches"
    data["oddsNotice"] = (
        f"В UEFA feed {visible_total} матчей с прогнозом и свежей активной линией MelBet."
        if visible_total else
        "В UEFA feed нет матчей, прошедших проверку прогноза и свежей активной линии MelBet."
    )
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default=str(DATA))
    parser.add_argument("--snapshots", default=str(SNAPSHOTS))
    parser.add_argument("--output", default=None)
    parser.add_argument("--uefa-input", default=None)
    parser.add_argument("--uefa-output", default=None)
    args = parser.parse_args()
    input_path = Path(args.input)
    output_path = Path(args.output) if args.output else input_path
    data = json.loads(input_path.read_text(encoding="utf-8"))
    updated = apply_snapshots(data, read_jsonl(args.snapshots))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_text(json.dumps(updated, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(output_path)
    report = {
        "output": str(output_path), "oddsStatus": updated["oddsStatus"],
        "coverage": updated["oddsCoverage"],
    }
    if args.uefa_input:
        uefa_input = Path(args.uefa_input)
        uefa_output = Path(args.uefa_output) if args.uefa_output else uefa_input
        uefa_data = json.loads(uefa_input.read_text(encoding="utf-8"))
        uefa_updated = apply_uefa_snapshots(uefa_data, read_jsonl(args.snapshots))
        uefa_output.parent.mkdir(parents=True, exist_ok=True)
        uefa_temporary = uefa_output.with_suffix(uefa_output.suffix + ".tmp")
        uefa_temporary.write_text(json.dumps(uefa_updated, ensure_ascii=False, indent=2), encoding="utf-8")
        uefa_temporary.replace(uefa_output)
        report["uefa"] = {
            "output": str(uefa_output), "oddsStatus": uefa_updated["oddsStatus"],
            "coverage": uefa_updated["oddsCoverage"],
        }
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
