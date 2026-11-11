"""Compare API-Sport bulk-by-ID and per-match MelBet odds for a few fixtures.

This is a manual diagnostic only. It never writes model data and never prints credentials.
"""
import json
import os
import re

import requests

BASE_URL = "https://api.api-sport.ru/v2/football/matches"
DEFAULT_IDS = (16363661,)  # Manchester City - Fulham, API-Sport fixture ID
BASE_PARAMS = {
    "with_bk_odds": "true",
    "odds_stage": "all",
    "bookmaker_ids": "melbet",
}

# Deliberately classify by known market slugs only. Display names such as
# "Both Teams to Score" contain the word "Teams" but are not team totals.
TEAM_TOTAL_SLUG = re.compile(
    r"^(?:(?:1st|2nd)_half_)?(?:total_team_[12]|team_total_(?:home|away)_goals)$"
)


def is_team_total_market_slug(slug):
    return bool(TEAM_TOTAL_SLUG.fullmatch(str(slug).strip().lower()))


def extract_match(payload):
    if isinstance(payload, list):
        return payload[0] if payload else {}
    if not isinstance(payload, dict):
        return {}
    if isinstance(payload.get("match"), dict):
        return payload["match"]
    matches = payload.get("matches")
    if isinstance(matches, list):
        return matches[0] if matches else {}
    return payload


def summarize(match):
    if not isinstance(match, dict):
        match = {}
    flags = match.get("hasBkOdds") or {}
    board = (match.get("oddsBk") or {}).get("melbet")
    markets = board.get("markets") if isinstance(board, dict) else {}
    result = {
        "id": match.get("id"),
        "home": (match.get("homeTeam") or {}).get("name"),
        "away": (match.get("awayTeam") or {}).get("name"),
        "startTimestamp": match.get("startTimestamp"),
        "hasBkOddsBetboom": flags.get("melbet") if isinstance(flags, dict) else None,
        "hasBetboomBoard": isinstance(board, dict),
        "teamTotalMarkets": [],
    }
    for slug, market in (markets or {}).items():
        if not isinstance(market, dict) or not is_team_total_market_slug(slug):
            continue
        name = market.get("name") or {}
        if isinstance(name, dict):
            name = name.get("en") or name.get("ru") or ""
        outcomes = {}
        for outcome, stake in (market.get("stakes") or {}).items():
            if not isinstance(stake, dict):
                continue
            rows = stake.get("lines") or []
            if not rows and stake.get("factor") is not None:
                rows = [{"argument": None, "factor": stake.get("factor")}]
            outcomes[str(outcome)] = [
                {"line": row.get("argument"), "factor": row.get("factor"),
                 "suspended": bool(row.get("suspended") or stake.get("suspended"))}
                for row in rows[:8] if isinstance(row, dict)
            ]
        result["teamTotalMarkets"].append({
            "slug": str(slug), "name": str(name),
            "status": market.get("status"), "stage": market.get("stage"),
            "stakes": outcomes,
        })
    return result


def request_json(session, url, params):
    response = session.get(url, params=params, timeout=45)
    if not response.ok:
        raise RuntimeError(f"API-Sport HTTP {response.status_code}: {response.text[:500]}")
    return response.json()


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise RuntimeError("API_SPORT_KEY missing from environment")
    raw_ids = os.getenv("API_SPORT_DIAGNOSTIC_IDS", "")
    ids = [int(value.strip()) for value in raw_ids.split(",") if value.strip()] if raw_ids else list(DEFAULT_IDS)
    ids = list(dict.fromkeys(ids))[:5]
    if not ids:
        raise RuntimeError("No fixture IDs supplied")

    session = requests.Session()
    session.headers.update({"Authorization": key})
    bulk_payload = request_json(session, BASE_URL, {
        **BASE_PARAMS, "ids": ",".join(map(str, ids)), "view": "odds",
    })
    bulk_matches = bulk_payload if isinstance(bulk_payload, list) else bulk_payload.get("matches", [])
    bulk_by_id = {str(match.get("id")): match for match in bulk_matches if isinstance(match, dict)}

    report = {"requestedIds": ids, "bulkByIds": [], "detailById": []}
    for fixture_id in ids:
        report["bulkByIds"].append(summarize(bulk_by_id.get(str(fixture_id), {})))
        detail = request_json(session, f"{BASE_URL}/{fixture_id}", BASE_PARAMS)
        report["detailById"].append(summarize(extract_match(detail)))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
