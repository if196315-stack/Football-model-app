"""Read-only lookup of API-Sport tournament IDs for IQ Match's seven competitions.

This deliberately makes only seven search requests and does not touch production
fixtures, odds ingestion, caches, predictions, or paper bets.
"""
import json
import os
import sys
from pathlib import Path

import requests

BASE = "https://api.api-sport.ru/v2/football/tournaments"
TIMEOUT = 30
TARGETS = {
    "PL": "Premier League",
    "PD": "La Liga",
    "SA": "Serie A",
    "BL1": "Bundesliga",
    "FL1": "Ligue 1",
    "UCL": "UEFA Champions League",
    "UEL": "UEFA Europa League",
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
    for code, query in TARGETS.items():
        response = session.get(BASE, params={"q": query}, timeout=TIMEOUT)
        response.raise_for_status()
        payload = response.json()
        rows = [compact(row) for row in tournament_items(payload) if isinstance(row, dict)]
        output[code] = {"query": query, "results": rows[:20], "totalReturned": len(rows)}
    print("TOURNAMENT_ID_LOOKUP=" + json.dumps(output, ensure_ascii=False))
    print("SUMMARY=" + json.dumps({
        "competitionsQueried": len(TARGETS),
        "queries": list(TARGETS),
        "apiRequests": len(TARGETS),
        "readOnly": True,
        "note": "Search results identify candidate tournament IDs; they do not prove fixture-to-competition linkage."
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
