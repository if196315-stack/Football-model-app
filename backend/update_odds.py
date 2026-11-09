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
    if league is None:
        aliases = {key: alias for group in ALIASES.values() for key, alias in group.items()}
    else:
        aliases = ALIASES.get(league, {})
    return norm(aliases.get(value, value))


def team_side_from_label(value, home, away):
    """Identify a team named inside a bookmaker double-chance outcome."""
    label = norm(value)
    if label in ("home", "1"):
        return "home"
    if label in ("away", "2"):
        return "away"
    home_score = similarity(value, home)
    away_score = similarity(value, away)
    if home_score >= 0.86 and home_score > away_score:
        return "home"
    if away_score >= 0.86 and away_score > home_score:
        return "away"
    return None


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
    """Parse provider timestamps and normalize them to timezone-aware UTC."""
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except (TypeError, ValueError):
        return None


def is_same_fixture_date(match_value, event_time):
    """Date-only fixture feeds have no kickoff time; match by UTC calendar date."""
    raw = str(match_value or "").strip()
    if len(raw) == 10:
        try:
            return datetime.fromisoformat(raw).date() == event_time.date()
        except ValueError:
            return False
    target_time = parse_time(raw)
    return bool(target_time and abs((event_time - target_time).total_seconds()) <= 18 * 3600)


def read_market(bookmaker, key, home, away):
    market = next((x for x in bookmaker.get("markets", []) if x.get("key") == key), None)
    if not market:
        return None
    out = [] if key in ("spreads", "team_totals") else {}
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
            # The Odds API names outcomes with actual team names, e.g.
            # "Fulham or Draw", not only generic "Home or Draw" labels.
            parts = re.split(r"\s+or\s+", name.strip(), flags=re.IGNORECASE)
            if len(parts) == 2:
                draw_part = next((part for part in parts if norm(part) in ("draw", "tie")), None)
                team_parts = [part for part in parts if part is not draw_part]
                if draw_part is not None and len(team_parts) == 1:
                    side = team_side_from_label(team_parts[0], home, away)
                    label = "1x" if side == "home" else "x2" if side == "away" else None
                elif draw_part is None:
                    sides = [team_side_from_label(part, home, away) for part in parts]
                    label = "12" if set(sides) == {"home", "away"} else None
                else:
                    label = None
            else:
                compact = re.sub(r"[^a-z0-9]+", "", name.lower())
                label = compact if compact in ("1x", "x2", "12") else None
            if label:
                out[label] = float(price)
        elif key == "btts":
            label = name.strip().lower()
            if label in ("yes", "no"):
                out[label] = float(price)
        elif key == "team_totals":
            point = outcome.get("point")
            description = str(outcome.get("description") or "")
            if point is None:
                continue
            try:
                point = float(point)
            except (TypeError, ValueError):
                continue
            team = "home" if similarity(description, home) >= 0.86 else (
                "away" if similarity(description, away) >= 0.86 else None
            )
            side = name.strip().lower()
            if team and side in ("over", "under"):
                out.append({"team": team, "point": point, "side": side, "price": float(price)})
        elif key in ("spreads", "alternate_spreads"):
            point = outcome.get("point")
            if point is None:
                continue
            try:
                point = float(point)
            except (TypeError, ValueError):
                continue
            # Betting Desk publishes only level handicap (0) for either team.
            if abs(point) > 0.001:
                continue
            if norm(name) in (norm(home), norm(away)):
                out.append({"team": "home" if norm(name) == norm(home) else "away",
                            "point": point, "price": float(price)})
    return out or None

def event_for_match(match, events, league):
    raw_target_time = str(match.get("utcDate", "") or "")
    target_time = parse_time(raw_target_time)
    if not target_time and len(raw_target_time.strip()) != 10:
        return None
    ranked = []
    for event in events:
        event_time = parse_time(event.get("commence_time", ""))
        if not event_time or not is_same_fixture_date(raw_target_time, event_time):
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
    best = {"h2h": {}, "totals": {}, "doubleChance": {}, "btts": {}, "spreads": [], "teamTotals": []}
    for raw in event.get("bookmakers", []):
        markets = {
            "h2h": read_market(raw, "h2h", event.get("home_team", ""), event.get("away_team", "")) or {},
            "totals": read_market(raw, "totals", event.get("home_team", ""), event.get("away_team", "")) or {},
            "doubleChance": read_market(raw, "double_chance", event.get("home_team", ""), event.get("away_team", "")) or {},
            "btts": read_market(raw, "btts", event.get("home_team", ""), event.get("away_team", "")) or {},
            "spreads": (
                read_market(raw, "spreads", event.get("home_team", ""), event.get("away_team", "")) or []
            ) + (
                read_market(raw, "alternate_spreads", event.get("home_team", ""), event.get("away_team", "")) or []
            ),
            "teamTotals": read_market(raw, "team_totals", event.get("home_team", ""), event.get("away_team", "")) or [],
        }
        item = {
            "key": raw.get("key"), "title": raw.get("title"),
            "lastUpdate": raw.get("last_update"),
            **markets,
        }
        if any(markets.values()):
            books.append(item)
        for market_key in ("h2h", "totals", "doubleChance", "btts"):
            for outcome, price in markets[market_key].items():
                old = best[market_key].get(outcome)
                if old is None or price > old["price"]:
                    best[market_key][outcome] = {
                        "price": price, "bookmaker": raw.get("title", raw.get("key", ""))
                    }
        for list_market in ("spreads", "teamTotals"):
            for quote in markets[list_market]:
                candidate = {**quote, "bookmaker": raw.get("title", raw.get("key", ""))}
                identity = ("team", "point") if list_market == "spreads" else ("team", "point", "side")
                old = next((x for x in best[list_market]
                            if all(x.get(k) == candidate.get(k) for k in identity)), None)
                if old is None:
                    best[list_market].append(candidate)
                elif candidate["price"] > old["price"]:
                    old.update(candidate)
    compact_best = {
        market: {key: value["price"] for key, value in values.items()}
        for market, values in best.items() if market not in ("spreads", "teamTotals") and values
    }
    if best["spreads"]:
        compact_best["spreads"] = best["spreads"]
    if best["teamTotals"]:
        compact_best["teamTotals"] = best["teamTotals"]
    best_books = {
        market: {key: value["bookmaker"] for key, value in values.items()}
        for market, values in best.items() if market not in ("spreads", "teamTotals") and values
    }
    if best["spreads"]:
        best_books["spreads"] = [
            {"team": x["team"], "point": x["point"], "bookmaker": x["bookmaker"]}
            for x in best["spreads"]
        ]
    if best["teamTotals"]:
        best_books["teamTotals"] = [
            {"team": x["team"], "point": x["point"], "side": x["side"], "bookmaker": x["bookmaker"]}
            for x in best["teamTotals"]
        ]
    return {
        "eventId": event.get("id"),
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "bookmakers": books,
        "best": compact_best,
        "bestBookmakers": best_books,
    }


MODEL_MARKETS = ("double_chance", "spreads", "alternate_spreads", "team_totals")


def has_model_market(quote):
    best = (quote or {}).get("best") or {}
    dc = best.get("doubleChance") or {}
    if any(isinstance(price, (int, float)) and price > 1 for price in dc.values()):
        return True
    for row in best.get("spreads") or []:
        if isinstance(row.get("price"), (int, float)) and row["price"] > 1 and abs(float(row.get("point", 0))) < 0.001:
            return True
    for row in best.get("teamTotals") or []:
        if row.get("side") == "over" and isinstance(row.get("price"), (int, float)) and row["price"] > 1:
            return True
    return False


def fetch_event_market_quote(session, sport, event, api_key, regions="eu"):
    """Fetch model markets from the per-event endpoint; /odds only accepts featured markets."""
    event_id = event.get("id")
    if not event_id:
        return None, ["event id missing"]
    url = f"{BASE_URL}/{sport}/events/{event_id}/odds"
    params = {
        "apiKey": api_key, "regions": regions,
        "markets": ",".join(MODEL_MARKETS),
        "oddsFormat": "decimal", "dateFormat": "iso",
    }
    response = session.get(url, params=params, timeout=35)
    if response.status_code != 422:
        response.raise_for_status()
        return convert_event(response.json()), []

    # Isolate a market-level rejection rather than losing all requested markets.
    combined_errors = []
    quote = None
    for market in MODEL_MARKETS:
        single_params = dict(params)
        single_params["markets"] = market
        single = session.get(url, params=single_params, timeout=35)
        if single.status_code >= 400:
            combined_errors.append(f"{market}: HTTP {single.status_code} {single.text[:120]}")
            continue
        one_quote = convert_event(single.json())
        quote = merge_quote(quote, one_quote)
    return quote, combined_errors

def merge_quote(existing, incoming):
    """Add missing markets from The Odds API without replacing BSD consensus."""
    if not existing:
        return incoming
    if not incoming:
        return existing
    merged = dict(existing)
    merged["best"] = dict(existing.get("best") or {})
    merged["bestBookmakers"] = dict(existing.get("bestBookmakers") or {})
    for market, values in (incoming.get("best") or {}).items():
        if market in ("spreads", "teamTotals"):
            old_rows = list(merged["best"].get(market) or [])
            identity = ("team", "point") if market == "spreads" else ("team", "point", "side")
            for row in values or []:
                if not any(all(old.get(k) == row.get(k) for k in identity) for old in old_rows):
                    old_rows.append(row)
            if old_rows:
                merged["best"][market] = old_rows
        else:
            old_values = dict(merged["best"].get(market) or {})
            for key, value in (values or {}).items():
                old_values.setdefault(key, value)
            if old_values:
                merged["best"][market] = old_values
    for market, values in (incoming.get("bestBookmakers") or {}).items():
        if market in ("spreads", "teamTotals"):
            old_rows = list(merged["bestBookmakers"].get(market) or [])
            old_rows.extend(row for row in (values or []) if row not in old_rows)
            if old_rows:
                merged["bestBookmakers"][market] = old_rows
        else:
            old_values = dict(merged["bestBookmakers"].get(market) or {})
            for key, value in (values or {}).items():
                old_values.setdefault(key, value)
            if old_values:
                merged["bestBookmakers"][market] = old_values
    merged["bookmakers"] = list(existing.get("bookmakers") or []) + [
        book for book in (incoming.get("bookmakers") or [])
        if book not in (existing.get("bookmakers") or [])
    ]
    merged["updatedAt"] = incoming.get("updatedAt") or existing.get("updatedAt")
    merged["eventId"] = existing.get("eventId") or incoming.get("eventId")
    return merged


def main():
    if not DATA_FILE.exists():
        raise RuntimeError("docs/data.json does not exist")
    data = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    api_key = os.getenv("ODDS_API_KEY", "").strip()
    if not api_key:
        bsd_count = int(data.get("bsdOddsUpdated") or 0)
        data["oddsStatus"] = "ready" if bsd_count else "missing_api_key"
        data["oddsNotice"] = (
            f"BSD обновил базовые котировки для {bsd_count} матчей; The Odds API не настроен."
            if bsd_count else
            "Добавьте ODDS_API_KEY в GitHub Secrets, чтобы дополнять рынки котировками букмекеров."
        )
        DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"The Odds API skipped; BSD supplied odds for {bsd_count} matches.")
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
                    # The bulk odds endpoint accepts featured markets only.
                    "markets": "h2h",
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
                    model_quote, market_errors = fetch_event_market_quote(
                        requests, sport, event, api_key, regions="eu"
                    )
                    if model_quote:
                        quote = merge_quote(quote, model_quote)
                    for market_error in market_errors:
                        if len(errors) < 25:
                            errors.append(f"{code} {match.get('home')}–{match.get('away')}: {market_error}")
                    if quote.get("best"):
                        match["odds"] = merge_quote(match.get("odds"), quote)
                    if has_model_market(quote):
                        updated += 1
                        league_updated += 1
                    elif has_model_market(match.get("odds")):
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
        f"Новые котировки по рынкам модели: {updated}; сохранены прежние: {retained}; матчи без сопоставления: {max(0, eligible - matched)}."
        if (updated or retained) else "The Odds API не вернул котировок по рынкам модели для сопоставленных матчей."
    )
    if errors:
        data["oddsErrors"] = errors
    else:
        data.pop("oddsErrors", None)
    DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Odds updated for {updated} matches; remaining credits: {data['oddsCreditsRemaining']}")


if __name__ == "__main__":
    main()
