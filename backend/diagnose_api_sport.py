"""Read-only API-Sport quota and MelBet market diagnostics.

This script never writes model data or odds snapshots and never prints credentials.
Run only as a bounded, one-off audit: it checks quota, a capped fixture sample,
exact matched-fixture boards, and a capped MelBet market sample.
"""
import json
import os
from datetime import date, datetime, timedelta, timezone

import requests

BASE = "https://api.api-sport.ru/v2"
USAGE_URL = f"{BASE}/account/usage"
MATCHES_URL = f"{BASE}/football/matches"
TIMEOUT = 45
SAMPLE_SIZE = 100
IDENTITY_SAMPLE_SIZE = 20


def response_items(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("matches", "data", "items", "results"):
            value = payload.get(key)
            if isinstance(value, list):
                return value
    return []


def exact_match_payload(payload):
    if isinstance(payload, dict):
        for key in ("match", "data", "result"):
            value = payload.get(key)
            if isinstance(value, dict):
                return value
        return payload
    return {}


def exact_fixture_odds_summary(payload):
    match = exact_match_payload(payload)
    flags = match.get("hasBkOdds") or {}
    books = match.get("oddsBk") or {}
    melbet = books.get("melbet") if isinstance(books, dict) else None
    markets = market_summary(melbet) if isinstance(melbet, dict) else []
    return {
        "id": match.get("id"),
        "home": (match.get("homeTeam") or {}).get("name"),
        "away": (match.get("awayTeam") or {}).get("name"),
        "hasBkOdds": flags,
        "bookmakerBoards": sorted(books.keys()) if isinstance(books, dict) else [],
        "melbetBoard": isinstance(melbet, dict),
        "melbetMarkets": markets,
    }


def quota_summary(payload):
    usage = payload.get("usage") or {}
    if not isinstance(usage, dict):
        usage = {}
    return {
        "plan": payload.get("plan") or payload.get("planName"),
        "dailyRequestLimit": payload.get("dailyRequestLimit"),
        "today": usage.get("today"),
        "remaining": usage.get("remaining"),
        "resetAt": usage.get("resetAt"),
        "services": payload.get("services"),
    }


def fixture_identity_sample(match):
    home = match.get("homeTeam") or {}
    away = match.get("awayTeam") or {}
    return {
        "id": match.get("id"),
        "startTimestamp": match.get("startTimestamp"),
        "dateEvent": match.get("dateEvent"),
        "home": home.get("name") if isinstance(home, dict) else home,
        "homeTranslations": home.get("translations") if isinstance(home, dict) else None,
        "away": away.get("name") if isinstance(away, dict) else away,
        "awayTranslations": away.get("translations") if isinstance(away, dict) else None,
    }


def model_fixture_sample(data, limit=5):
    result = []
    today = datetime.now(timezone.utc).date()
    for competition in data.get("competitions", []):
        for match in competition.get("matches", []):
            if match.get("status") not in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                continue
            raw = match.get("utcDate")
            try:
                dt = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
                if dt.date() < today:
                    continue
            except (TypeError, ValueError):
                continue
            result.append({
                "competition": competition.get("code"),
                "id": match.get("id"),
                "utcDate": raw,
                "home": match.get("home"),
                "away": match.get("away"),
                "status": match.get("status"),
            })
            if len(result) >= limit:
                return result
    return result


def market_summary(board):
    markets = board.get("markets") or {}
    result = []
    for slug, market in markets.items():
        if not isinstance(market, dict):
            continue
        stakes = market.get("stakes") or {}
        outcomes = {}
        for outcome, stake in stakes.items():
            if not isinstance(stake, dict):
                continue
            lines = []
            for line in stake.get("lines") or []:
                if not isinstance(line, dict):
                    continue
                lines.append({
                    "argument": line.get("argument"),
                    "factor": line.get("factor"),
                    "suspended": line.get("suspended", False),
                })
            if not lines and stake.get("factor") is not None:
                lines.append({"argument": None, "factor": stake.get("factor"),
                              "suspended": stake.get("suspended", False)})
            outcomes[outcome] = lines
        result.append({
            "slug": slug,
            "name": market.get("name"),
            "status": market.get("status"),
            "stage": market.get("stage"),
            "isBettingActiveByStage": market.get("isBettingActiveByStage"),
            "outcomes": outcomes,
        })
    return result


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY is missing; no request was made.")

    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})

    usage_response = session.get(USAGE_URL, timeout=TIMEOUT)
    usage_response.raise_for_status()
    usage_payload = usage_response.json()
    print("ACCOUNT_USAGE=" + json.dumps(quota_summary(usage_payload), ensure_ascii=False))

    today = datetime.now(timezone.utc).date()
    try:
        model_data = json.loads((__import__("pathlib").Path(__file__).resolve().parents[1] / "docs" / "data.json").read_text(encoding="utf-8"))
        model_samples = model_fixture_sample(model_data)
        print("MODEL_FIXTURE_SAMPLE=" + json.dumps(model_samples, ensure_ascii=False))
    except (OSError, json.JSONDecodeError):
        model_samples = []
        print("MODEL_FIXTURE_SAMPLE=[]")
    # Query the date of the model's nearest upcoming fixture, not today's
    # unrelated API fixtures. This keeps the one-page diagnostic relevant.
    target_date = (model_samples[0].get("utcDate") if model_samples else None) or today.isoformat()
    params = {
        "date_from": target_date,
        "date_to": target_date,
        "exclude_amateur": "true",
        "view": "odds",
        "with_bk_odds": "true",
        "has_bk_odds": "true",
        "odds_stage": "all",
        "bookmaker_ids": "melbet",
        "page": 1,
        "page_size": SAMPLE_SIZE,
        "sort": "asc",
    }
    # First inspect fixture availability without bookmaker filters. A zero
    # result here means the upstream fixture feed has not published this date;
    # it must not be misdiagnosed as missing MelBet prices.
    fixture_params = {
        "date_from": target_date,
        "date_to": target_date,
        "exclude_amateur": "true",
        "page_size": 500,
        "sort": "asc",
    }
    # The date can contain far more than the first 100 global fixtures.
    # Scan a hard-bounded five pages so early leagues do not hide model leagues.
    fixture_matches = []
    for page in range(1, 6):
        page_params = {**fixture_params, "page": page}
        fixture_response = session.get(MATCHES_URL, params=page_params, timeout=TIMEOUT)
        fixture_response.raise_for_status()
        page_matches = response_items(fixture_response.json())
        fixture_matches.extend(page_matches)
        if len(page_matches) < fixture_params["page_size"]:
            break

    def team_key(value):
        import re
        return re.sub(r"[^a-z0-9]", "", str(value or "").lower())

    model_pairs = [
        (team_key(item.get("home")), team_key(item.get("away")),
         item.get("home"), item.get("away"))
        for item in model_samples
    ]
    matched_rows = []
    for match in fixture_matches:
        identity = fixture_identity_sample(match)
        api_home, api_away = team_key(identity.get("home")), team_key(identity.get("away"))
        for home_key, away_key, home_name, away_name in model_pairs:
            if (home_key and away_key and
                (home_key in api_home or api_home in home_key) and
                (away_key in api_away or api_away in away_key)):
                flags = match.get("hasBkOdds") or {}
                matched_rows.append({
                    **identity,
                    "modelHome": home_name,
                    "modelAway": away_name,
                    "hasMelBetOdds": flags.get("melbet") if isinstance(flags, dict) else None,
                })
                break

    fixture_rows = []
    for match in fixture_matches[:IDENTITY_SAMPLE_SIZE]:
        flags = match.get("hasBkOdds") or {}
        fixture_rows.append({
            **fixture_identity_sample(match),
            "hasMelBetOdds": flags.get("melbet") if isinstance(flags, dict) else None,
        })
    print(f"FIXTURE_ONLY_SCANNED={len(fixture_matches)} (maximum 2500; five pages)")
    print("FIXTURE_ONLY_SAMPLE=" + json.dumps(fixture_rows, ensure_ascii=False))
    print("MODEL_FIXTURE_MATCHES_IN_API=" + json.dumps(matched_rows, ensure_ascii=False))

    # Fetch each exact model fixture by ID, without a bookmaker filter, to
    # distinguish missing MelBet from missing odds payload / other book boards.
    exact_rows = []
    for row in matched_rows:
        fixture_id = row.get("id")
        if fixture_id is None:
            continue
        exact_response = session.get(
            f"{MATCHES_URL}/{fixture_id}",
            params={"with_bk_odds": "true"},
            timeout=TIMEOUT,
        )
        exact_response.raise_for_status()
        exact_rows.append(exact_fixture_odds_summary(exact_response.json()))
    print("EXACT_MODEL_FIXTURE_ODDS=" + json.dumps(exact_rows, ensure_ascii=False))
    matches_response = session.get(MATCHES_URL, params=params, timeout=TIMEOUT)
    matches_response.raise_for_status()
    payload = matches_response.json()
    matches = response_items(payload)

    header_limits = {
        name: matches_response.headers.get(name)
        for name in ("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset")
        if matches_response.headers.get(name) is not None
    }
    print("REQUEST_RATE_LIMIT_HEADERS=" + json.dumps(header_limits, ensure_ascii=False))
    print(f"TARGET_DATE={target_date}")
    print(f"MATCH_SAMPLE_COUNT={len(matches)} (requested max {SAMPLE_SIZE}; one page only)")
    identity_rows = []
    for match in matches[:IDENTITY_SAMPLE_SIZE]:
        board = ((match.get("oddsBk") or {}).get("melbet") or {})
        stages = {}
        prematch_slugs = []
        for slug, market in (board.get("markets") or {}).items():
            if not isinstance(market, dict):
                continue
            stage = market.get("stage") or "missing"
            stages[stage] = stages.get(stage, 0) + 1
            if stage == "prematch" and market.get("status") == "open":
                prematch_slugs.append(slug)
        identity_rows.append({
            **fixture_identity_sample(match),
            "melbetMarketStages": stages,
            "openPrematchSlugs": prematch_slugs,
        })
    print("API_TARGET_DATE_SAMPLE=" + json.dumps(identity_rows, ensure_ascii=False))

    totals = {"matches": 0, "hasMelBetFlag": 0, "melbetBoard": 0,
              "marketSlugs": {}, "openMarketSlugs": {}}
    for match in matches[:SAMPLE_SIZE]:
        totals["matches"] += 1
        flags = match.get("hasBkOdds") or {}
        if isinstance(flags, dict) and flags.get("melbet") is True:
            totals["hasMelBetFlag"] += 1
        books = match.get("oddsBk") or {}
        board = books.get("melbet") if isinstance(books, dict) else None
        if not isinstance(board, dict):
            print("MATCH=" + json.dumps({
                "id": match.get("id"),
                "home": (match.get("homeTeam") or {}).get("name"),
                "away": (match.get("awayTeam") or {}).get("name"),
                "hasBkOddsMelBet": flags.get("melbet") if isinstance(flags, dict) else None,
                "melbetBoard": False,
            }, ensure_ascii=False))
            continue
        totals["melbetBoard"] += 1
        markets = market_summary(board)
        for market in markets:
            totals["marketSlugs"][market["slug"]] = totals["marketSlugs"].get(market["slug"], 0) + 1
            if market["status"] == "open":
                totals["openMarketSlugs"][market["slug"]] = totals["openMarketSlugs"].get(market["slug"], 0) + 1
        print("MATCH=" + json.dumps({
            "id": match.get("id"),
            "home": (match.get("homeTeam") or {}).get("name"),
            "away": (match.get("awayTeam") or {}).get("name"),
            "hasBkOddsMelBet": flags.get("melbet") if isinstance(flags, dict) else None,
            "boardUpdatedAt": board.get("updatedAt"),
            "isBettingActiveByStage": board.get("isBettingActiveByStage"),
            "markets": markets,
        }, ensure_ascii=False))

    print("SUMMARY=" + json.dumps(totals, ensure_ascii=False))
    print("DIAGNOSTIC_DONE=read_only; no model files or snapshots changed")


if __name__ == "__main__":
    main()
