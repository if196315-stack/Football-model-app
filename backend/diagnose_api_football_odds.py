#!/usr/bin/env python3
"""Read-only API-Football odds coverage diagnostic: exactly one request per league."""
import os
import sys
import requests

BASE = "https://v3.football.api-sports.io/odds"
LEAGUES = {"PL": 39, "PD": 140, "SA": 135, "BL1": 78, "FL1": 61}
SEASON = 2026
TIMEOUT = 30


def classify(name):
    value = str(name or "").strip().lower().replace("-", " ")
    if "double chance" in value:
        return "double_chance"
    if "asian handicap" in value:
        return "asian_handicap"
    if "team total" in value or "team goals" in value or "goals home team" in value or "goals away team" in value:
        return "team_totals"
    return None


def main():
    key = os.environ.get("API_FOOTBALL_KEY", "").strip()
    if not key:
        print("ERROR: API_FOOTBALL_KEY secret is missing.")
        return 2
    session = requests.Session()
    session.headers.update({"x-apisports-key": key})
    print("Read-only API-Football odds check: one /odds request per league; max 5.")
    print(f"Season={SEASON}; page=1 only (10 results per league).")
    calls = 0
    for code, league_id in LEAGUES.items():
        calls += 1
        try:
            response = session.get(BASE, params={"league": league_id, "season": SEASON, "page": 1}, timeout=TIMEOUT)
            print(f"\n[{code}] HTTP {response.status_code}")
            if response.status_code != 200:
                print("ERROR:", response.text[:400])
                continue
            payload = response.json()
            if payload.get("errors"):
                print("API errors:", payload["errors"])
            print(f"Results={payload.get('results', 0)}; pages={payload.get('paging', {}).get('total', '?')}")
            fixtures = payload.get("response", [])
            if not fixtures:
                print("No odds returned on page 1.")
                continue
            counts = {"double_chance": 0, "asian_handicap": 0, "team_totals": 0}
            books = {k: set() for k in counts}
            samples = {k: [] for k in counts}
            observed_names = set()
            for item in fixtures:
                fixture = item.get("fixture", {})
                teams = item.get("teams", {})
                matchup = "{} vs {}".format(teams.get("home", {}).get("name", "?"), teams.get("away", {}).get("name", "?"))
                for bookmaker in item.get("bookmakers", []):
                    book = bookmaker.get("name", "Unknown")
                    for bet in bookmaker.get("bets", []):
                        name = str(bet.get("name", ""))
                        if name:
                            observed_names.add(name)
                        market = classify(name)
                        if not market:
                            continue
                        counts[market] += 1
                        books[market].add(book)
                        if len(samples[market]) < 3:
                            samples[market].append({
                                "match": matchup,
                                "date": fixture.get("date"),
                                "bookmaker": book,
                                "market_name": name,
                                "values": [
                                    {"label": v.get("value"), "odd": v.get("odd"), "handicap": v.get("handicap")}
                                    for v in bet.get("values", [])[:6]
                                ],
                            })
            for market in counts:
                print(f"{market}: bookmaker_entries={counts[market]}, bookmakers={sorted(books[market])}")
                for sample in samples[market]:
                    print(" SAMPLE", sample)
            print("Observed bet names:", "; ".join(sorted(observed_names)[:50]) or "none")
        except requests.RequestException as exc:
            print("REQUEST ERROR:", exc)
        except (ValueError, TypeError, KeyError) as exc:
            print("PARSE ERROR:", exc)
    print(f"\nRequests made: {calls} (hard cap 5)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
