"""Read-only probe of MelBet's public pre-match event feed.

This diagnostic uses public match URLs/event IDs only. It never writes model
files or snapshots and never prints credentials or cookies.
"""
import json
from typing import Any

import requests

BASE_URL = "https://melbet.mobi/service-api/LineFeed/GetGameZip"
TIMEOUT = 30
SUPPORTED_GROUPS = {2, 3, 4, 6, 9, 15, 17, 62}
EVENTS = [
    {"id": 385744564, "home": "Manchester City", "away": "Fulham"},
    {"id": 385744560, "home": "Aston Villa", "away": "Sunderland"},
    {"id": 385744574, "home": "Bournemouth", "away": "Nottingham Forest"},
    {"id": 385744556, "home": "Chelsea", "away": "Leeds United"},
    {"id": 385744566, "home": "Coventry City", "away": "Crystal Palace"},
]
BASE_PARAMS = {
    "lng": "en",
    "mode": "4",
    "country": "87",
    "top": "true",
    "virtualSports": "true",
    "partner": "61",
    "gr": "6",
    "isSubGames": "true",
    "grMode": "4",
}


def unwrap_value(payload: Any) -> Any:
    if isinstance(payload, dict):
        return payload.get("Value", payload)
    return payload


def iter_market_rows(value: Any) -> list[dict]:
    """Find terse market records without assuming one exact nesting shape."""
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


def supported_market_summary(value: Any, limit: int = 160) -> dict:
    rows = iter_market_rows(value)
    counts: dict[str, int] = {}
    supported = []
    one_x_two = {}
    for row in rows:
        group = row.get("G")
        try:
            group_id = int(group)
        except (TypeError, ValueError):
            continue
        if group_id == 1:
            try:
                outcome_id = int(row.get("T"))
            except (TypeError, ValueError):
                outcome_id = None
            outcome = {1: "home", 2: "draw", 3: "away"}.get(outcome_id)
            if outcome and row.get("C") is not None:
                one_x_two[outcome] = row.get("C")
        if group_id in SUPPORTED_GROUPS:
            key = str(group_id)
            counts[key] = counts.get(key, 0) + 1
            if len(supported) < limit:
                supported.append(compact_market(row))
    return {
        "oneXTwo": one_x_two,
        "supportedMarketGroupCounts": dict(sorted(counts.items())),
        "supportedMarketSample": supported,
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
        "valueKeys": sorted(event_obj.keys())[:40],
        "sourceHome": event_obj.get("O1") or event_obj.get("O1E"),
        "sourceAway": event_obj.get("O2") or event_obj.get("O2E"),
        "marketRecordsFound": market_count,
        "marketSample": market_sample,
        **supported_market_summary(value),
    }
    if not isinstance(payload, (dict, list)) and text_body:
        result["bodyPreview"] = text_body[:300]
    return result


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
        session.headers["referer"] = referer
        try:
            response = session.get(BASE_URL, params=params, timeout=TIMEOUT)
            try:
                payload = response.json()
            except (ValueError, json.JSONDecodeError):
                payload = None
            rows.append(summarize_response(
                event, response.status_code,
                response.headers.get("content-type", ""),
                payload=payload, text_body=response.text,
            ))
        except requests.RequestException as exc:
            rows.append({
                "eventId": event["id"],
                "expected": f'{event["home"]} - {event["away"]}',
                "requestError": type(exc).__name__,
                "message": str(exc)[:240],
            })
    print("MELBET_DIRECT_FEED=" + json.dumps(rows, ensure_ascii=False))
    print("DIAGNOSTIC_DONE=read_only; no model files or snapshots changed")


if __name__ == "__main__":
    main()
