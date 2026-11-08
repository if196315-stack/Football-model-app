import json
import os
import re
import time
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
DATA_FILE = ROOT / "docs" / "data.json"
BASE_URL = "https://api.the-odds-api.com/v4/sports"
LEAGUES = {
    "PL": "soccer_epl",
    "PD": "soccer_spain_la_liga",
    "SA": "soccer_italy_serie_a",
    "BL1": "soccer_germany_bundesliga",
    "FL1": "soccer_france_ligue_one",
}


def norm(value):
    value = str(value or "").lower().replace("&", " and ")
    value = re.sub(r"\b(fc|afc|cf|sc|ac|club|football|the)\b", " ", value)
    return " ".join(re.findall(r"[a-z0-9]+", value))


def similarity(a, b):
    aa, bb = norm(a), norm(b)
    if not aa or not bb:
        return 0.0
    if aa == bb:
        return 1.0
    return SequenceMatcher(None, aa, bb).ratio()


def parse_time(value):
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except Exception:
        return None


def read_market(bookmaker, key, home, away):
    market = next((x for x in bookmaker.get("markets", []) if x.get("key") == key), None)
    if not market:
        return None
    out = {}
    for outcome in market.get("outcomes", []):
        name = str(outcome.get("name", ""))
        price = outcome.get("price")
        if not isinstance(price, (int, float)) or price <= 1:
            continue
        if key == "h2h":
            if name.lower() == "draw":
                out["draw"] = float(price)
            elif norm(name) == norm(home):
                out["home"] = float(price)
            elif norm(name) == norm(away):
                out["away"] = float(price)
        elif key == "totals":
            point = outcome.get("point")
            if point is not None and abs(float(point) - 2.5) > 0.001:
                continue
            if name.lower() == "over":
                out["over25"] = float(price)
            elif name.lower() == "under":
                out["under25"] = float(price)
    return out or None


def event_for_match(match, events):
    target_time = parse_time(match.get("utcDate", ""))
    if not target_time:
        return None
    ranked = []
    for event in events:
        event_time = parse_time(event.get("commence_time", ""))
        if not event_time or abs((event_time - target_time).total_seconds()) > 18 * 3600:
            continue
        h = similarity(match.get("home"), event.get("home_team"))
        a = similarity(match.get("away"), event.get("away_team"))
        score = h + a
        if h >= 0.78 and a >= 0.78:
            ranked.append((score, event))
    ranked.sort(key=lambda item: item[0], reverse=True)
    if not ranked:
        return None
    if len(ranked) > 1 and ranked[0][0] - ranked[1][0] < 0.04:
        return None
    return ranked[0][1]


def convert_event(event):
    books = []
    best = {"h2h": {}, "totals": {}}
    for raw in event.get("bookmakers", []):
        h2h = read_market(raw, "h2h", event.get("home_team", ""), event.get("away_team", "")) or {}
        totals = read_market(raw, "totals", event.get("home_team", ""), event.get("away_team", "")) or {}
        item = {
            "key": raw.get("key"),
            "title": raw.get("title"),
            "lastUpdate": raw.get("last_update"),
            "h2h": h2h,
            "totals": totals,
        }
        if h2h or totals:
            books.append(item)
        for market_key, values in (("h2h", h2h), ("totals", totals)):
            for outcome, price in values.items():
                old = best[market_key].get(outcome)
                if old is None or price > old["price"]:
                    best[market_key][outcome] = {
                        "price": price,
                        "bookmaker": raw.get("title", raw.get("key", ""))
                    }
    compact_best = {
        market: {key: value["price"] for key, value in values.items()}
        for market, values in best.items() if values
    }
    best_books = {
        market: {key: value["bookmaker"] for key, value in values.items()}
        for market, values in best.items() if values
    }
    return {
        "eventId": event.get("id"),
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "bookmakers": books,
        "best": compact_best,
        "bestBookmakers": best_books,
    }


def main():
    if not DATA_FILE.exists():
        raise RuntimeError("docs/data.json does not exist")
    data = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    api_key = os.getenv("ODDS_API_KEY", "").strip()
    if not api_key:
        data["oddsStatus"] = "missing_api_key"
        data["oddsNotice"] = "Добавьте ODDS_API_KEY в GitHub Secrets, чтобы получать коэффициенты."
        DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print("ODDS_API_KEY is not configured; model data left unchanged.")
        return

    quota_remaining = None
    quota_used = None
    updated = 0
    errors = []
    competitions = {x.get("code"): x for x in data.get("competitions", [])}

    for code, sport in LEAGUES.items():
        competition = competitions.get(code)
        if not competition:
            continue
        try:
            response = requests.get(
                f"{BASE_URL}/{sport}/odds",
                params={
                    "apiKey": api_key,
                    "regions": "eu",
                    "markets": "h2h,totals",
                    "oddsFormat": "decimal",
                    "dateFormat": "iso",
                },
                timeout=35,
            )
            quota_remaining = response.headers.get("x-requests-remaining", quota_remaining)
            quota_used = response.headers.get("x-requests-used", quota_used)
            if response.status_code == 429:
                raise RuntimeError("The Odds API rate limit (429)")
            response.raise_for_status()
            events = response.json()
            for match in competition.get("matches", []):
                match.pop("odds", None)
                if match.get("status") not in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                    continue
                event = event_for_match(match, events)
                if event:
                    quote = convert_event(event)
                    if quote.get("best"):
                        match["odds"] = quote
                        updated += 1
        except Exception as exc:
            errors.append(f"{code}: {str(exc)[:180]}")
        time.sleep(1)

    data["oddsUpdatedAt"] = datetime.now(timezone.utc).isoformat()
    data["oddsCreditsRemaining"] = int(quota_remaining) if str(quota_remaining or "").isdigit() else None
    data["oddsCreditsUsed"] = int(quota_used) if str(quota_used or "").isdigit() else None
    data["oddsStatus"] = "ready" if updated else ("partial_error" if errors else "no_quotes")
    data["oddsNotice"] = (
        f"Коэффициенты найдены для {updated} матчей. Обновление раз в сутки."
        if updated else "На момент последнего обновления подходящих коэффициентов не найдено."
    )
    if errors:
        data["oddsErrors"] = errors
    else:
        data.pop("oddsErrors", None)
    DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Odds updated for {updated} matches; remaining credits: {data['oddsCreditsRemaining']}")


if __name__ == "__main__":
    main()
