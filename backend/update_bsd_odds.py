import json
import os
import re
import unicodedata
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
DATA_FILE = ROOT / "docs" / "data.json"
BASE = "https://sports.bzzoiro.com/api/v2/"
LEAGUES = {
    "PL": ("England", ("premier league",)),
    "PD": ("Spain", ("la liga", "laliga")),
    "SA": ("Italy", ("serie a",)),
    "BL1": ("Germany", ("bundesliga",)),
    "FL1": ("France", ("ligue 1",)),
}


def norm(value):
    value = unicodedata.normalize("NFKD", str(value or "").lower())
    value = "".join(c for c in value if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def score(a, b):
    a, b = norm(a), norm(b)
    if not a or not b:
        return 0
    if a == b:
        return 1
    return SequenceMatcher(None, a, b).ratio()


def rows(payload):
    if isinstance(payload, list):
        return payload
    return payload.get("results", []) if isinstance(payload, dict) else []


def get_json(session, path, params=None):
    response = session.get(BASE + path, params=params, timeout=30)
    response.raise_for_status()
    return response.json()


def match_event(match, events):
    raw_kickoff = str(match.get("utcDate", ""))
    try:
        kickoff = datetime.fromisoformat(raw_kickoff.replace("Z", "+00:00"))
        if kickoff.tzinfo is None:
            kickoff = kickoff.replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    date_only = "T" not in raw_kickoff
    ranked = []
    for event in events:
        raw_date = event.get("event_date") or event.get("date") or event.get("kickoff")
        try:
            event_date = datetime.fromisoformat(str(raw_date).replace("Z", "+00:00"))
            if event_date.tzinfo is None:
                event_date = event_date.replace(tzinfo=timezone.utc)
        except (TypeError, ValueError):
            continue
        if date_only:
            if event_date.date() != kickoff.date():
                continue
        elif abs((event_date - kickoff).total_seconds()) > 18 * 3600:
            continue
        home = event.get("home_team") or event.get("home") or ""
        away = event.get("away_team") or event.get("away") or ""
        hs, aws = score(match.get("home"), home), score(match.get("away"), away)
        if hs >= 0.86 and aws >= 0.86:
            ranked.append((hs + aws, event))
    ranked.sort(key=lambda item: item[0], reverse=True)
    if not ranked or (len(ranked) > 1 and ranked[0][0] - ranked[1][0] < 0.10):
        return None
    return ranked[0][1]


def make_quote(event, payload):
    odds = payload.get("odds") or {}
    markets = {
        "h2h": {"home": odds.get("home_win"), "draw": odds.get("draw"), "away": odds.get("away_win")},
        "totals": {"over25": odds.get("over_25_goals"), "under25": odds.get("under_25_goals")},
        "btts": {"yes": odds.get("btts_yes"), "no": odds.get("btts_no")},
    }
    best = {}
    for market, values in markets.items():
        clean = {key: float(value) for key, value in values.items()
                 if isinstance(value, (int, float)) and value > 1}
        if clean:
            best[market] = clean
    if not best:
        return None
    source = "BSD consensus"
    best_books = {market: {key: source for key in values} for market, values in best.items()}
    return {
        "eventId": str(event.get("id")),
        "updatedAt": payload.get("last_update_at") or datetime.now(timezone.utc).isoformat(),
        "bookmakers": [{"key": "bsd_consensus", "title": source, **best}],
        "best": best,
        "bestBookmakers": best_books,
    }


def main():
    token = os.getenv("BSD_API_KEY", "").strip()
    if not token:
        print("BSD_API_KEY is not configured; BSD odds skipped.")
        return
    data = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    session = requests.Session()
    session.headers.update({"Authorization": f"Token {token}"})
    today = datetime.now(timezone.utc).date()
    date_from, date_to = today.isoformat(), (today + timedelta(days=10)).isoformat()
    updated, errors = 0, []
    competitions = {item.get("code"): item for item in data.get("competitions", [])}
    for code, (country, aliases) in LEAGUES.items():
        competition = competitions.get(code)
        if not competition:
            continue
        try:
            league_rows = rows(get_json(session, "leagues/", {"country": country, "limit": 200}))
            league = next((item for item in league_rows
                           if any(alias in norm(item.get("name") or item.get("league_name")) for alias in aliases)), None)
            if not league:
                raise RuntimeError(f"BSD league not found: {country}")
            events = rows(get_json(session, "events/", {
                "league_id": league["id"], "status": "upcoming",
                "date_from": date_from, "date_to": date_to, "limit": 200,
            }))
            for match in competition.get("matches", []):
                if match.get("status") not in ("SCHEDULED", "TIMED"):
                    continue
                event = match_event(match, events)
                if not event or not event.get("id"):
                    continue
                odds_payload = get_json(session, f"events/{event['id']}/odds/")
                quote = make_quote(event, odds_payload)
                if not quote:
                    continue
                old = match.get("odds") or {}
                merged = dict(old)
                merged["best"] = dict(old.get("best") or {})
                merged["bestBookmakers"] = dict(old.get("bestBookmakers") or {})
                for market, values in quote["best"].items():
                    merged["best"].setdefault(market, {}).update(values)
                    merged["bestBookmakers"].setdefault(market, {}).update(quote["bestBookmakers"][market])
                # Keep one current BSD consensus entry per fixture across scheduled runs.
                other_books = [book for book in (old.get("bookmakers") or [])
                               if book.get("key") != "bsd_consensus"]
                merged["bookmakers"] = other_books + quote["bookmakers"]
                merged["eventId"] = old.get("eventId") or quote["eventId"]
                merged["updatedAt"] = quote["updatedAt"]
                match["odds"] = merged
                updated += 1
        except Exception as exc:
            errors.append(f"{code}: {str(exc)[:160]}")
    data["bsdOddsUpdatedAt"] = datetime.now(timezone.utc).isoformat()
    data["bsdOddsUpdated"] = updated
    if errors:
        data["bsdOddsErrors"] = errors
    else:
        data.pop("bsdOddsErrors", None)
    DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"BSD consensus odds updated for {updated} matches.")


if __name__ == "__main__":
    main()
