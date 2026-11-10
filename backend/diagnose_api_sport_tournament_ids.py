"""Read-only lookup of API-Sport tournament IDs for IQ Match's seven competitions.

This makes a bounded set of search requests and does not touch production
fixtures, odds ingestion, caches, predictions, or paper bets.
"""
import json
import os

import requests

BASE = "https://api.api-sport.ru/v2/football/tournaments"
TIMEOUT = 30
TARGETS = {
    "PL": ["Premier League"],
    "PD": ["LaLiga", "Primera Division"],
    "SA": ["Serie A"],
    "BL1": ["Bundesliga"],
    "FL1": ["Ligue 1"],
    "UCL": ["UEFA Champions League"],
    "UEL": ["UEFA Europa League"],
}


def tournament_items(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("tournaments", "data", "items", "results"):
            value = payload.get(key)
            if isinstance(value, list):
                return value
    return []


def compact(item):
    category = item.get("category") or {}
    return {
        "id": item.get("id"),
        "name": item.get("name"),
        "translations": item.get("translations") or {},
        "category": {
            "id": category.get("id"),
            "name": category.get("name"),
            "translations": category.get("translations") or {},
        } if isinstance(category, dict) else category,
    }


def main():
    key = os.getenv("API_SPORT_KEY", "").strip()
    if not key:
        raise SystemExit("API_SPORT_KEY is missing; no request was made.")
    session = requests.Session()
    session.headers.update({"Authorization": key, "Accept": "application/json"})
    output = {}
    request_count = 0
    for code, queries in TARGETS.items():
        searches = {}
        for query in queries:
            response = session.get(BASE, params={"q": query}, timeout=TIMEOUT)
            response.raise_for_status()
            request_count += 1
            rows = [compact(row) for row in tournament_items(response.json()) if isinstance(row, dict)]
            searches[query] = rows[:30]
        output[code] = {"searches": searches}
    print("TOURNAMENT_ID_LOOKUP=" + json.dumps(output, ensure_ascii=False))
    print("SUMMARY=" + json.dumps({
        "competitionsQueried": len(TARGETS),
        "apiRequests": request_count,
        "readOnly": True,
        "note": "Search results identify candidate tournament IDs; fixture-to-competition linkage still requires checking tournament.id in each matched match."
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
