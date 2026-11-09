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
    import unicodedata
    value = unicodedata.normalize("NFKD", str(value or "").lower())
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    value = value.replace("&", " and ")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    ignored = {"fc","afc","cf","sc","ac","club","football","the","de","del","cd","ud","sd","fk","sk","sv","vfl","tsv"}
    return " ".join(x for x in value.split() if x not in ignored)


# Known naming variants used by fixture and bookmaker feeds.
ALIASES = {
    "PL": {
        "man utd":"manchester united", "man united":"manchester united",
        "man city":"manchester city", "spurs":"tottenham hotspur",
        "wolves":"wolverhampton wanderers", "west ham":"west ham united",
        "newcastle":"newcastle united", "brighton":"brighton hove albion",
        "nottm forest":"nottingham forest", "forest":"nottingham forest",
    },
    "PD": {
        "atletico madrid":"atletico de madrid", "athletic bilbao":"athletic club",
        "real betis":"real betis", "celta vigo":"celta",
        "sociedad":"real sociedad", "espanyol":"espanyol",
    },
    "SA": {
        "inter":"internazionale", "inter milan":"internazionale",
        "milan":"milan", "roma":"roma", "lazio":"lazio",
        "napoli":"napoli",
    },
    "BL1": {
        "bayern":"bayern munich", "bayern munchen":"bayern munich",
        "dortmund":"borussia dortmund", "gladbach":"borussia monchengladbach",
        "leverkusen":"bayer leverkusen", "koln":"koln",
    },
    "FL1": {
        "psg":"paris saint germain", "paris sg":"paris saint germain",
        "olympique lyon":"lyon", "olympique marseille":"marseille",
        "st etienne":"saint etienne",
    },
}


def canonical(value, league):
    value = norm(value)
    aliases = ALIASES.get(league, {})
    return norm(aliases.get(value, value))


def similarity(a, b, league=None):
    aa, bb = canonical(a, league), canonical(b, league)
    if not aa or not bb:
        return 0.0
    if aa == bb:
        return 1.0
    seq = SequenceMatcher(None, aa, bb).ratio()
    ta, tb = set(aa.split()), set(bb.split())
    token = 2 * len(ta & tb) / max(1, len(ta) + len(tb))
    return 0.72 * seq + 0.28 * token

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
        elif key == "double_chance":
            label = re.sub(r"\\s+", "", name.lower()).replace("home", "1").replace("draw", "x").replace("away", "2")
            if label in ("1x", "x2", "12"):
                out[label] = float(price)
        elif key == "spreads":
            point = outcome.get("point")
            if point is None:
                continue
            try:
                point = float(point)
            except (TypeError, ValueError):
                continue
            # AH0 / DNB is deliberately excluded.
            if abs(point) < 0.001:
                continue
            if norm(name) in (norm(home), norm(away)):
                out.append({"team": "home" if norm(name) == norm(home) else "away",
                            "point": point, "price": float(price)})
    return out or None

def event_for_match(match, events, league):
    target_time = parse_time(match.get("utcDate", ""))
    if not target_time:
        return None
    ranked = []
    for event in events:
        event_time = parse_time(event.get("commence_time", ""))
        if not event_time or abs((event_time - target_time).total_seconds()) > 18 * 3600:
            continue
        h = similarity(match.get("home"), event.get("home_team"), league)
        a = similarity(match.get("away"), event.get("away_team"), league)
        score = h + a
        if h >= 0.86 and a >= 0.86 and min(h, a) >= 0.86:
            ranked.append((score, event))
    ranked.sort(key=lambda item: item[0], reverse=True)
    if not ranked:
        return None
    if len(ranked) > 1 and ranked[0][0] - ranked[1][0] < 0.10:
        return None
    return ranked[0][1]


def convert_event(event):
    books = []
    best = {"h2h": {}, "totals": {}, "doubleChance": {}, "spreads": []}
    for raw in event.get("bookmakers", []):
        markets = {
            "h2h": read_market(raw, "h2h", event.get("home_team", ""), event.get("away_team", "")) or {},
            "totals": read_market(raw, "totals", event.get("home_team", ""), event.get("away_team", "")) or {},
            "doubleChance": read_market(raw, "double_chance", event.get("home_team", ""), event.get("away_team", "")) or {},
            "spreads": read_market(raw, "spreads", event.get("home_team", ""), event.get("away_team", "")) or [],
        }
        item = {
            "key": raw.get("key"), "title": raw.get("title"),
            "lastUpdate": raw.get("last_update"),
            **markets,
        }
        if any(markets.values()):
            books.append(item)
        for market_key in ("h2h", "totals", "doubleChance"):
            for outcome, price in markets[market_key].items():
                old = best[market_key].get(outcome)
                if old is None or price > old["price"]:
                    best[market_key][outcome] = {
                        "price": price, "bookmaker": raw.get("title", raw.get("key", ""))
                    }
        for quote in markets["spreads"]:
            candidate = {**quote, "bookmaker": raw.get("title", raw.get("key", ""))}
            old = next((x for x in best["spreads"]
                        if x["team"] == candidate["team"] and abs(x["point"] - candidate["point"]) < 0.001), None)
            if old is None:
                best["spreads"].append(candidate)
            elif candidate["price"] > old["price"]:
                old.update(candidate)
    compact_best = {
        market: {key: value["price"] for key, value in values.items()}
        for market, values in best.items() if market != "spreads" and values
    }
    compact_best["spreads"] = best["spreads"]
    best_books = {
        market: {key: value["bookmaker"] for key, value in values.items()}
        for market, values in best.items() if market != "spreads" and values
    }
    if best["spreads"]:
        best_books["spreads"] = [
            {"team": x["team"], "point": x["point"], "bookmaker": x["bookmaker"]}
            for x in best["spreads"]
        ]
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
    retained = 0
    matched = 0
    eligible = 0
    coverage = {}
    errors = []
    competitions = {x.get("code"): x for x in data.get("competitions", [])}

    for code, sport in LEAGUES.items():
        competition = competitions.get(code)
        if not competition:
            continue
        league_eligible = 0
        league_matched = 0
        league_updated = 0
        league_retained = 0
        try:
            response = requests.get(
                f"{BASE_URL}/{sport}/odds",
                params={
                    "apiKey": api_key,
                    "regions": "eu",
                    "markets": "h2h,totals,spreads,double_chance",
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
                if match.get("status") not in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                    continue
                league_eligible += 1
                eligible += 1
                event = event_for_match(match, events, code)
                if event:
                    league_matched += 1
                    matched += 1
                    quote = convert_event(event)
                    if quote.get("best"):
                        match["odds"] = quote
                        updated += 1
                        league_updated += 1
                    elif match.get("odds"):
                        retained += 1
                        league_retained += 1
                elif match.get("odds"):
                    # Keep the last known valid quote when the provider temporarily
                    # omits the event or team-name matching is inconclusive.
                    retained += 1
                    league_retained += 1
        except Exception as exc:
            errors.append(f"{code}: {str(exc)[:180]}")
        coverage[code] = {
            "eligible": league_eligible,
            "matched": league_matched,
            "updated": league_updated,
            "retained": league_retained,
            "notMatched": max(0, league_eligible - league_matched),
        }
        time.sleep(1)

    data["oddsUpdatedAt"] = datetime.now(timezone.utc).isoformat()
    data["oddsCoverage"] = {
        "eligible": eligible,
        "matched": matched,
        "updated": updated,
        "retained": retained,
        "notMatched": max(0, eligible - matched),
        "byLeague": coverage,
    }
    data["oddsCreditsRemaining"] = int(quota_remaining) if str(quota_remaining or "").isdigit() else None
    data["oddsCreditsUsed"] = int(quota_used) if str(quota_used or "").isdigit() else None
    data["oddsStatus"] = "ready" if (updated or retained) else ("partial_error" if errors else "no_quotes")
    data["oddsNotice"] = (
        f"Новые коэффициенты: {updated}; сохранены прежние: {retained}; без совпадения: {max(0, eligible - matched)}."
        if (updated or retained) else "На момент последнего обновления подходящих коэффициентов не найдено."
    )
    if errors:
        data["oddsErrors"] = errors
    else:
        data.pop("oddsErrors", None)
    DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Odds updated for {updated} matches; remaining credits: {data['oddsCreditsRemaining']}")


if __name__ == "__main__":
    main()
