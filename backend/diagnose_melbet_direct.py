"""Read-only probe of MelBet's public pre-match event feed and league discovery.

This diagnostic uses public match IDs only. It never writes model files or
snapshots and never prints credentials or cookies.
"""
import json
from typing import Any

import requests

BASE_URL = "https://melbet.mobi/service-api/LineFeed"
TIMEOUT = 30
EVENTS = [
    {"id": 385744564, "home": "Manchester City", "away": "Fulham"},
    {"id": 385744560, "home": "Aston Villa", "away": "Sunderland"},
    {"id": 385744574, "home": "Bournemouth", "away": "Nottingham Forest"},
    {"id": 385744556, "home": "Chelsea", "away": "Leeds United"},
    {"id": 385744566, "home": "Coventry City", "away": "Crystal Palace"},
]
BASE_PARAMS = {
    "lng": "en", "mode": "4", "country": "87", "top": "true",
    "virtualSports": "true", "partner": "61", "gr": "6",
    "isSubGames": "true", "grMode": "4",
}


def unwrap_value(payload: Any) -> Any:
    return payload.get("Value", payload) if isinstance(payload, dict) else payload


def iter_market_rows(value: Any) -> list[dict]:
    rows: list[dict] = []
    seen: set[int] = set()

    def walk(node: Any, depth: int = 0) -> None:
        if depth > 10:
            return
        if isinstance(node, list):
            for item in node:
                walk(item, depth + 1)
            return
        if not isinstance(node, dict):
            return
        if any(key in node for key in ("T", "G", "C", "CV")):
            marker = id(node)
            if marker not in seen:
                seen.add(marker)
                rows.append(node)
        for child in node.values():
            if isinstance(child, (dict, list)):
                walk(child, depth + 1)

    walk(value)
    return rows


def compact_market(row: dict) -> dict:
    return {key: row.get(key) for key in
            ("G", "GS", "T", "C", "CV", "P", "B") if key in row}


def collect_market_rows(value: Any, limit: int = 20) -> tuple[int, list[dict]]:
    rows = iter_market_rows(value)
    return len(rows), [compact_market(row) for row in rows[:limit]]


def market_group_summary(value: Any, limit: int = 220) -> dict:
    rows = iter_market_rows(value)
    counts: dict[str, int] = {}
    sample = []
    one_x_two = {}
    for row in rows:
        try:
            group_id = int(row.get("G"))
        except (TypeError, ValueError):
            continue
        key = str(group_id)
        counts[key] = counts.get(key, 0) + 1
        if len(sample) < limit:
            sample.append(compact_market(row))
        if group_id == 1:
            try:
                outcome_id = int(row.get("T"))
            except (TypeError, ValueError):
                outcome_id = None
            outcome = {1: "home", 2: "draw", 3: "away"}.get(outcome_id)
            if outcome and row.get("C") is not None:
                one_x_two[outcome] = row.get("C")
    return {
        "oneXTwo": one_x_two,
        "marketGroupCounts": dict(sorted(counts.items(), key=lambda item: int(item[0]))),
        "marketGroupSample": sample,
    }


def summarize_response(event: dict, status: int, content_type: str,
                       payload: Any = None, text_body: str = "") -> dict:
    value = unwrap_value(payload)
    event_obj = value[0] if isinstance(value, list) and value and isinstance(value[0], dict) else value
    if not isinstance(event_obj, dict):
        event_obj = {}
    market_count, market_sample = collect_market_rows(value)
    result = {
        "eventId": event["id"],
        "expected": f'{event["home"]} - {event["away"]}',
        "httpStatus": status,
        "contentType": content_type,
        "success": payload.get("Success") if isinstance(payload, dict) else None,
        "error": payload.get("Error") if isinstance(payload, dict) else None,
        "topLevelKeys": sorted(payload.keys())[:30] if isinstance(payload, dict) else [],
        "valueType": type(value).__name__,
        "valueKeys": sorted(event_obj.keys())[:50],
        "sourceHome": event_obj.get("O1") or event_obj.get("O1E"),
        "sourceAway": event_obj.get("O2") or event_obj.get("O2E"),
        "marketRecordsFound": market_count,
        "marketSample": market_sample,
        **market_group_summary(value),
    }
    if not isinstance(payload, (dict, list)) and text_body:
        result["bodyPreview"] = text_body[:300]
    return result


def extract_football_leagues(payload: Any) -> list[dict]:
    """Extract MelBet football leagues from GetSportsZip's Value[].L[]."""
    value = unwrap_value(payload)
    if not isinstance(value, list):
        return []
    leagues = []
    for sport in value:
        if not isinstance(sport, dict) or int(sport.get("I") or 0) != 1:
            continue
        for league in sport.get("L") or []:
            if not isinstance(league, dict):
                continue
            try:
                league_id = int(league.get("LI") or 0)
                game_count = int(league.get("GC") or 0)
            except (TypeError, ValueError):
                continue
            if league_id and game_count > 0:
                leagues.append({
                    "id": league_id,
                    "name": league.get("L") or "",
                    "games": game_count,
                })
    return sorted(leagues, key=lambda item: item["games"], reverse=True)


def extract_champ_fixtures(payload: Any, limit: int = 20) -> tuple[int, list[dict]]:
    """Extract event identity sample from GetChampZip's Value.G[]."""
    value = unwrap_value(payload)
    if isinstance(value, list):
        value = value[0] if value and isinstance(value[0], dict) else {}
    games = value.get("G") if isinstance(value, dict) else None
    if not isinstance(games, list):
        return 0, []
    sample = []
    for game in games[:limit]:
        if not isinstance(game, dict):
            continue
        sample.append({
            "id": game.get("I"),
            "home": game.get("O1") or game.get("O1E"),
            "away": game.get("O2") or game.get("O2E"),
            "start": game.get("S"),
            "leagueId": game.get("LI"),
            "league": game.get("L"),
            "hasMarkets": bool(game.get("E") or game.get("AE")),
        })
    return len(games), sample


def request_json(session: requests.Session, endpoint: str, params: dict,
                 referer: str) -> tuple[int, str, Any, str]:
    session.headers["referer"] = referer
    response = session.get(f"{BASE_URL}/{endpoint}", params=params, timeout=TIMEOUT)
    try:
        payload = response.json()
    except (ValueError, json.JSONDecodeError):
        payload = None
    return response.status_code, response.headers.get("content-type", ""), payload, response.text


def main() -> None:
    session = requests.Session()
    session.headers.update({
        "accept": "application/json, text/plain, */*",
        "content-type": "application/json",
        "is-srv": "false",
        "x-app-n": "__BETTING_APP__",
        "x-svc-source": "__BETTING_APP__",
        "x-requested-with": "XMLHttpRequest",
        "x-mobile-project-id": "0",
        "user-agent": ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                       "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"),
        "origin": "https://melbet.mobi",
    })
    rows = []
    for event in EVENTS:
        params = {**BASE_PARAMS, "id": str(event["id"])}
        referer = (
            "https://melbet.mobi/en/line/football/88637-england-premier-league/"
            f'{event["id"]}-event'
        )
        try:
            status, content_type, payload, body = request_json(
                session, "GetGameZip", params, referer
            )
            rows.append(summarize_response(
                event, status, content_type, payload=payload, text_body=body
            ))
        except requests.RequestException as exc:
            rows.append({
                "eventId": event["id"],
                "expected": f'{event["home"]} - {event["away"]}',
                "requestError": type(exc).__name__,
                "message": str(exc)[:240],
            })
    print("MELBET_DIRECT_FEED=" + json.dumps(rows, ensure_ascii=False))

    try:
        params = {key: value for key, value in BASE_PARAMS.items()
                  if key not in ("isSubGames", "grMode")}
        params["top"] = "false"
        status, content_type, payload, body = request_json(
            session, "GetSportsZip", params, "https://melbet.mobi/en/line/football"
        )
        leagues = extract_football_leagues(payload)
        print("MELBET_FOOTBALL_LEAGUES=" + json.dumps({
            "httpStatus": status,
            "contentType": content_type,
            "success": payload.get("Success") if isinstance(payload, dict) else None,
            "count": len(leagues),
            "topLeagues": leagues[:80],
            "bodyPreview": body[:200] if not isinstance(payload, (dict, list)) else None,
        }, ensure_ascii=False))
    except requests.RequestException as exc:
        print("MELBET_FOOTBALL_LEAGUES=" + json.dumps({
            "requestError": type(exc).__name__, "message": str(exc)[:240]
        }))

    try:
        params = {**BASE_PARAMS, "champ": "88637", "top": "false"}
        params.pop("isSubGames", None)
        params.pop("grMode", None)
        status, content_type, payload, body = request_json(
            session, "GetChampZip", params,
            "https://melbet.mobi/en/line/football/88637-england-premier-league"
        )
        count, sample = extract_champ_fixtures(payload)
        print("MELBET_EPL_FIXTURES=" + json.dumps({
            "httpStatus": status,
            "contentType": content_type,
            "success": payload.get("Success") if isinstance(payload, dict) else None,
            "count": count,
            "sample": sample,
            "bodyPreview": body[:200] if not isinstance(payload, (dict, list)) else None,
        }, ensure_ascii=False))
    except requests.RequestException as exc:
        print("MELBET_EPL_FIXTURES=" + json.dumps({
            "requestError": type(exc).__name__, "message": str(exc)[:240]
        }))

    print("DIAGNOSTIC_DONE=read_only; no model files or snapshots changed")


if __name__ == "__main__":
    main()
