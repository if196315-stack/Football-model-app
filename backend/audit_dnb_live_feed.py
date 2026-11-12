"""Read-only live MelBet DNB feed audit; never writes to repository data."""
from __future__ import annotations

import argparse
import json
import math
import os
from datetime import datetime, timezone
from pathlib import Path

from collect_uefa_melbet_snapshots import fetch_provider_fixtures
from update_odds_api_sport import is_supported_full_time_market, market_kind, parse_odds


def _calibrated_conditional_probability(probability: float, params: dict | None) -> float | None:
    if not params or not params.get("available"):
        return None
    classes = params.get("classes") or []
    if set(classes) != {"W", "L"}:
        return None
    temperature = float(params["temperature"])
    logits = {
        "W": math.log(max(1e-8, min(1.0, float(probability)))) / temperature + float(params["bias"]["W"]),
        "L": math.log(max(1e-8, min(1.0, 1.0 - float(probability)))) / temperature + float(params["bias"]["L"]),
    }
    peak = max(logits.values())
    win = math.exp(max(-60.0, logits["W"] - peak))
    lose = math.exp(max(-60.0, logits["L"] - peak))
    return win / (win + lose)


def build_report(fixtures: list[dict], captured_at: str | None = None,
                 model_predictions: dict | None = None,
                 dnb_calibrator: dict | None = None) -> dict:
    captured_at = captured_at or datetime.now(timezone.utc).isoformat()
    model_predictions = model_predictions or {}
    events = []
    counts = {
        "provider_fixtures": len(fixtures),
        "fixtures_with_melbet_board": 0,
        "open_prematch_fulltime_handicap_markets": 0,
        "zero_line_quotes": 0,
        "complete_home_away_pairs": 0,
        "incomplete_pairs": 0,
    }
    for fixture in fixtures:
        board = (fixture.get("oddsBk") or {}).get("melbet")
        if not isinstance(board, dict):
            continue
        counts["fixtures_with_melbet_board"] += 1
        raw_markets = []
        for slug, market in (board.get("markets") or {}).items():
            if (not isinstance(market, dict) or market.get("status") != "open"
                    or market.get("stage") != "prematch"
                    or market_kind(slug, market) != "spread"
                    or not is_supported_full_time_market(slug, market)):
                continue
            counts["open_prematch_fulltime_handicap_markets"] += 1
            raw_markets.append({
                "slug": slug,
                "name": (market.get("name") or {}).get("en")
                    if isinstance(market.get("name"), dict) else market.get("name"),
                "outcomes": {
                    outcome: [
                        {"argument": line.get("argument"), "price": line.get("factor")}
                        for line in (stake.get("lines") or [])
                        if isinstance(stake, dict) and isinstance(line, dict)
                    ] + ([{"argument": None, "price": stake.get("factor")}]
                         if isinstance(stake, dict) and stake.get("factor") else [])
                    for outcome, stake in (market.get("stakes") or {}).items()
                    if isinstance(stake, dict)
                },
            })

        parsed = parse_odds(fixture)
        quotes = (parsed.get("best") or {}).get("spreads") or []
        counts["zero_line_quotes"] += len(quotes)
        grouped = {}
        for quote in quotes:
            group = str(quote.get("market_group") or quote.get("market_slug") or "unknown")
            grouped.setdefault(group, {})[quote.get("team")] = quote
        paired_groups = [group for group, sides in grouped.items()
                         if "home" in sides and "away" in sides]
        incomplete_groups = [group for group, sides in grouped.items()
                             if ("home" in sides) != ("away" in sides)]
        counts["complete_home_away_pairs"] += len(paired_groups)
        counts["incomplete_pairs"] += len(incomplete_groups)
        if raw_markets or quotes:
            stamp = fixture.get("startTimestamp")
            kickoff = None
            if isinstance(stamp, (int, float)):
                kickoff = datetime.fromtimestamp(stamp / 1000, tz=timezone.utc).isoformat()
            event_id = str(fixture.get("id") or "")
            prediction = model_predictions.get(event_id) or {}
            handicap_model = (prediction.get("markets") or {}).get("handicap_zero") or {}
            paired_analysis = []
            for group in paired_groups:
                sides = grouped[group]
                home_quote, away_quote = sides["home"], sides["away"]
                home_odds, away_odds = float(home_quote["price"]), float(away_quote["price"])
                home_implied, away_implied = 1.0 / home_odds, 1.0 / away_odds
                overround = home_implied + away_implied
                home_fair = home_implied / overround if overround > 0 else None
                away_fair = away_implied / overround if overround > 0 else None
                home_prob = handicap_model.get("home") or {}
                away_prob = handicap_model.get("away") or {}
                home_decisive = float(home_prob.get("win", 0.0)) + float(home_prob.get("lose", 0.0))
                away_decisive = float(away_prob.get("win", 0.0)) + float(away_prob.get("lose", 0.0))
                raw_home_cond = float(home_prob["win"]) / home_decisive if home_decisive > 0 else None
                raw_away_cond = float(away_prob["win"]) / away_decisive if away_decisive > 0 else None
                cal_home_cond = (_calibrated_conditional_probability(raw_home_cond, dnb_calibrator)
                                 if raw_home_cond is not None else None)
                cal_away_cond = (1.0 - cal_home_cond if cal_home_cond is not None else None)
                paired_analysis.append({
                    "market_group": group,
                    "home_odds": home_odds,
                    "away_odds": away_odds,
                    "overround": overround,
                    "no_vig_home_probability": home_fair,
                    "no_vig_away_probability": away_fair,
                    "raw_model_home_win_given_decisive": raw_home_cond,
                    "raw_model_away_win_given_decisive": raw_away_cond,
                    "calibrated_home_win_given_decisive_experimental": cal_home_cond,
                    "calibrated_away_win_given_decisive_experimental": cal_away_cond,
                    "home_model_ev_pct_raw": (
                        (float(home_prob["win"]) * (home_odds - 1.0) - float(home_prob["lose"])) * 100.0
                        if home_prob and "win" in home_prob and "lose" in home_prob else None
                    ),
                    "away_model_ev_pct_raw": (
                        (float(away_prob["win"]) * (away_odds - 1.0) - float(away_prob["lose"])) * 100.0
                        if away_prob and "win" in away_prob and "lose" in away_prob else None
                    ),
                    "home_model_edge_vs_no_vig_pp_experimental": (
                        (cal_home_cond - home_fair) * 100.0
                        if cal_home_cond is not None and home_fair is not None else None
                    ),
                    "away_model_edge_vs_no_vig_pp_experimental": (
                        (cal_away_cond - away_fair) * 100.0
                        if cal_away_cond is not None and away_fair is not None else None
                    ),
                })
            events.append({
                "event_id": event_id,
                "home": (fixture.get("homeTeam") or {}).get("name"),
                "away": (fixture.get("awayTeam") or {}).get("name"),
                "kickoff_utc": kickoff,
                "bookmaker_updated_at": parsed.get("bookmakerUpdatedAt"),
                "freshness_status": parsed.get("freshnessStatus"),
                "raw_open_prematch_fulltime_handicap_markets": raw_markets,
                "parsed_zero_handicap_quotes": quotes,
                "paired_market_groups": paired_groups,
                "incomplete_market_groups": incomplete_groups,
                "dnb_market_analysis": paired_analysis,
                "model_prediction_available": bool(handicap_model),
            })
    return {
        "captured_at": captured_at,
        "source": "API-Sport / MelBet",
        "mode": "read_only_no_repository_writes",
        "parser_scope": "full-time prematch Asian handicap 0 only",
        "counts": counts,
        "events_with_handicap_data": events,
    }


def map_model_predictions(cache: dict, provider_fixtures: list[dict],
                            predictions: dict) -> dict:
    """Join PitchAPI predictions to API-Sport event IDs using the collector's exact match rules."""
    from update_odds_api_sport import match_fixture

    mapped = {}
    for fixture_id, match in (cache.get("matches") or {}).items():
        prediction = predictions.get(str(fixture_id))
        if not prediction:
            continue
        provider_id = match.get("providerEventId")
        source = next((row for row in provider_fixtures
                       if provider_id is not None and str(row.get("id")) == str(provider_id)), None)
        if source is None:
            target = {
                "utcDate": match.get("date"),
                "home": match.get("home"),
                "away": match.get("away"),
            }
            source = match_fixture(target, provider_fixtures)
        if source and source.get("id") is not None:
            mapped[str(source["id"])] = prediction
    return mapped


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="dnb-live-feed-audit.json")
    parser.add_argument("--fixture-cache", default="backend/cache/pitchapi_uefa_fixtures.json")
    parser.add_argument("--xg-history", default="artifacts/uefa-xg-history.json")
    parser.add_argument("--calibration-report", default="artifacts/seven-competition-all-market-report.json")
    args = parser.parse_args()
    api_key = os.environ.get("API_SPORT_KEY", "").strip()
    if not api_key:
        raise RuntimeError("API_SPORT_KEY is not configured")
    fixtures = fetch_provider_fixtures(api_key)
    model_predictions = {}
    cache_path = Path(args.fixture_cache)
    history_path = Path(args.xg_history)
    cache_history_loaded = cache_path.exists() and history_path.exists()
    if cache_history_loaded:
        from predict_uefa_markets import predict_upcoming
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
        history = json.loads(history_path.read_text(encoding="utf-8"))
        by_fixture = predict_upcoming(history, cache)
        model_predictions = map_model_predictions(cache, fixtures, by_fixture)
    dnb_calibrator = None
    calibration_path = Path(args.calibration_report)
    if calibration_path.exists():
        calibration = json.loads(calibration_path.read_text(encoding="utf-8"))
        dnb_calibrator = (((calibration.get("markets") or {}).get("DNB_HOME_CONDITIONAL") or {})
                          .get("calibration_global") or {}).get("calibrator")
    report = build_report(fixtures, model_predictions=model_predictions,
                          dnb_calibrator=dnb_calibrator)
    report["model_prediction_source"] = (
        "strict-date walk-forward UEFA xG/Poisson model; raw model probabilities are not claimed calibrated"
        if model_predictions else "not available; no matching fixture cache and xG history"
    )
    report["calibration_source"] = (
        "pre-holdout DNB decisive-only vector scaling; experimental only, not production-approved"
        if dnb_calibrator and dnb_calibrator.get("available") else "not available"
    )
    Path(args.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report["counts"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
