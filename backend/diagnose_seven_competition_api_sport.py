"""Bounded read-only API-Sport/BetBoom coverage check for the seven IQ Match competitions."""
import json
import os
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://api.api-sport.ru/v2"
MATCHES_URL = f"{BASE}/football/matches"
USAGE_URL = f"{BASE}/account/usage"
TIMEOUT = 45
PAGE_SIZE = 500
MAX_PAGES_PER_DATE = 5

def upcoming_one_per_competition(domestic, uefa, today=None):
    today = today or datetime.now(timezone.utc).date()
    chosen = {}
    for source in (domestic, uefa):
        for comp in source.get("competitions", []):
            code = comp.get("code") or comp.get("id")
            if code not in {"PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL"}:
                continue
            candidates = []
            for match in comp.get("matches", []):
                if match.get("status") not in ("SCHEDULED", "TIMED"):
                    continue
                raw = match.get("utcDate")
                try:
                    dt = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
                    if dt.tzinfo is None:
                        dt = dt.replace(tzinfo=timezone.utc)
                    if dt.date() < today:
                        continue
                except (TypeError, ValueError):
                    continue
                candidates.append((dt, match))
            if candidates:
                dt, match = min(candidates, key=lambda item: item[0])
                chosen[code] = {
                    "competition": code, "id": match.get("id"),
                    "utcDate": (str(match.get("utcDate")).strip() if len(str(match.get("utcDate") or "").strip()) == 10 else dt.isoformat()), "date": dt.date().isoformat(),
                    "home": match.get("home"), "away": match.get("away"),
                }
    return [chosen[code] for code in ("PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL") if code in chosen]

def response_items(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("matches", "data", "items", "results"):
            if isinstance(payload.get(key), list):
                return payload[key]
    return []

def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY is missing; no request was made.")
    domestic = json.loads((ROOT / "docs" / "data.json").read_text(encoding="utf-8"))
    uefa = json.loads((ROOT / "docs" / "uefa-data.json").read_text(encoding="utf-8"))
    samples = upcoming_one_per_competition(domestic, uefa)
    print("MODEL_FIXTURES=" + json.dumps(samples, ensure_ascii=False))
    if not samples:
        raise SystemExit("No upcoming fixtures found in the seven target competitions.")

    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    usage = session.get(USAGE_URL, timeout=TIMEOUT)
    usage.raise_for_status()
    usage_payload = usage.json()
    usage_info = usage_payload.get("usage") or {}
    print("ACCOUNT_USAGE=" + json.dumps({
        "plan": usage_payload.get("plan") or usage_payload.get("planName"),
        "dailyRequestLimit": usage_payload.get("dailyRequestLimit"),
        "today": usage_info.get("today"),
        "remaining": usage_info.get("remaining"),
    }, ensure_ascii=False))

    by_date = defaultdict(list)
    for sample in samples:
        by_date[sample["date"]].append(sample)

    from update_odds_api_sport import match_fixture, parse_odds
    exact_rows = []
    request_count = 1
    for date, targets in sorted(by_date.items()):
        candidates = []
        for page in range(1, MAX_PAGES_PER_DATE + 1):
            response = session.get(MATCHES_URL, params={
                "date_from": date, "date_to": date,
                "exclude_amateur": "true", "page": page,
                "page_size": PAGE_SIZE, "sort": "asc",
            }, timeout=TIMEOUT)
            response.raise_for_status()
            request_count += 1
            rows = response_items(response.json())
            candidates.extend(rows)
            if len(rows) < PAGE_SIZE:
                break
        for target in targets:
            matched = match_fixture({
                "utcDate": target["utcDate"],
                "home": target["home"], "away": target["away"],
            }, candidates)
            if not matched:
                exact_rows.append({
                    "competition": target["competition"], "home": target["home"],
                    "away": target["away"], "matched": False,
                    "betboomBoard": False, "openMarkets": [], "marketStageCounts": {}, "prematchActiveFlag": False,
                })
                continue
            fixture_id = matched.get("id")
            detail = session.get(f"{MATCHES_URL}/{fixture_id}",
                                  params={"with_bk_odds": "true"}, timeout=TIMEOUT)
            detail.raise_for_status()
            request_count += 1
            payload = detail.json()
            match = payload.get("match") or payload.get("data") or payload.get("result") or payload
            books = match.get("oddsBk") or {}
            board = books.get("betboom") if isinstance(books, dict) else None
            markets = board.get("markets") or {} if isinstance(board, dict) else {}
            prematch_active = (match.get("isBettingActiveByStage") or {}).get("prematch") is True
            open_markets = sorted(
                slug for slug, market in markets.items()
                if isinstance(market, dict) and market.get("status") == "open"
            )
            market_stages = {}
            for market in markets.values():
                if isinstance(market, dict):
                    stage = str(market.get("stage") or "unspecified")
                    market_stages[stage] = market_stages.get(stage, 0) + 1
            # Run the exact production parser against the raw detail payload. It only
            # accepts open BetBoom markets explicitly tagged stage=prematch.
            parsed = parse_odds(match)
            parsed_best = parsed.get("best") or {}
            production_quote_counts = {
                "doubleChance": len(parsed_best.get("doubleChance") or {}),
                "spreads": len(parsed_best.get("spreads") or []),
                "teamTotals": len(parsed_best.get("teamTotals") or []),
                "totals": len(parsed_best.get("totals") or []),
                "btts": len(parsed_best.get("btts") or {}),
            }
            exact_rows.append({
                "competition": target["competition"], "home": target["home"],
                "away": target["away"], "matched": True, "apiSportFixtureId": fixture_id,
                "apiSportHome": (match.get("homeTeam") or {}).get("name"),
                "apiSportAway": (match.get("awayTeam") or {}).get("name"),
                "betboomFlag": (match.get("hasBkOdds") or {}).get("betboom"),
                "betboomBoard": isinstance(board, dict),
                "openMarkets": open_markets, "marketStageCounts": market_stages,
                "prematchActiveFlag": prematch_active,
                "productionParserQuoteCounts": production_quote_counts,
                "productionParserAccepted": sum(production_quote_counts.values()) > 0,
            })
    print("COMPETITION_RESULTS=" + json.dumps(exact_rows, ensure_ascii=False))
    print("SUMMARY=" + json.dumps({
        "requestedCompetitions": len(samples),
        "matchedFixtures": sum(row["matched"] for row in exact_rows),
        "betboomBoards": sum(row["betboomBoard"] for row in exact_rows),
        "productionParserAccepted": sum(row.get("productionParserAccepted", False) for row in exact_rows),
        "competitionsWithNoSample": sorted({"PL", "PD", "SA", "BL1", "FL1", "UCL", "UEL"} - {x["competition"] for x in samples}),
        "apiRequests": request_count,
        "readOnly": True,
    }, ensure_ascii=False))
    print("DIAGNOSTIC_DONE=read_only; no model files or odds snapshots changed")

if __name__ == "__main__":
    main()
