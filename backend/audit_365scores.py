import json
import re
import time
from difflib import SequenceMatcher
import os
from datetime import datetime
from datetime import date, timedelta
from pathlib import Path

import requests

START = date(2026, 5, 25)
END = date(2026, 11, 8)
BASE = "https://webws.365scores.com/web"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
    "Origin": "https://www.365scores.com",
    "Referer": "https://www.365scores.com/",
    "Accept": "application/json, text/plain, */*",
}
LEAGUES = {
    "eng": ("premier league", "england premier league"),
    "esp": ("la liga", "laliga", "primera division"),
    "ita": ("serie a", "italy serie a"),
    "ger": ("bundesliga", "germany bundesliga"),
    "fra": ("ligue 1", "france ligue 1"),
}
SESSION = requests.Session()
SESSION.headers.update(HEADERS)


def get_json(path, params):
    url = f"{BASE}/{path.strip('/')}/"
    last = None
    for attempt in range(5):
        try:
            response = SESSION.get(url, params=params, timeout=(15, 45))
            if response.status_code == 429 or response.status_code >= 500:
                response.raise_for_status()
            response.raise_for_status()
            return response.json()
        except Exception as exc:
            last = exc
            if attempt == 4:
                raise RuntimeError(f"{path}: {last}") from last
            time.sleep(2 ** attempt)
    raise RuntimeError(str(last))


def norm(value):
    return re.sub(r"[^a-z0-9]+", " ", str(value or "").lower()).strip()


def league_code(game, competitions=None, countries=None):
    comp = game.get("competition") or game.get("tournament") or {}
    if not isinstance(comp, dict):
        comp = {}
    comp_id = game.get("competitionId")
    if competitions and comp_id is not None:
        comp = competitions.get(comp_id, comp)
    name = norm(comp.get("name") or game.get("competitionName") or game.get("competitionDisplayName") or "")
    country_obj = comp.get("country") or {}
    if isinstance(country_obj, dict):
        country = norm(country_obj.get("name"))
    else:
        country = norm(country_obj)
    if not country and countries:
        country = norm(countries.get(comp.get("countryId"), ""))

    # Require BOTH the canonical competition and its country. Substring-only
    # matching incorrectly classified unrelated leagues (e.g. Egypt as ENG).
    rules = {
        "eng": ({"premier league", "english premier league"}, {"england", "united kingdom"}),
        "esp": ({"la liga", "laliga", "primera division"}, {"spain", "espana"}),
        "ita": ({"serie a", "italy serie a"}, {"italy", "italia"}),
        "ger": ({"bundesliga", "germany bundesliga"}, {"germany", "deutschland"}),
        "fra": ({"ligue 1", "france ligue 1"}, {"france"}),
    }
    for code, (names, countries_allowed) in rules.items():
        if name in names and country in countries_allowed:
            return code
    return None


def competitor_id(game, side):
    obj = game.get(f"{side}Competitor") or game.get(side) or {}
    return obj.get("id") if isinstance(obj, dict) else None


def competitor_name(game, side):
    obj = game.get(f"{side}Competitor") or game.get(side) or {}
    return (obj.get("name") or obj.get("shortName")) if isinstance(obj, dict) else str(obj or "")


def parse_xg(stats, home_id, away_id):
    found = {}
    for item in stats or []:
        name = norm(item.get("name") or item.get("shortName"))
        if name not in ("expected goals", "xg"):
            continue
        cid = item.get("competitorId")
        try:
            value = float(str(item.get("value", "")).replace(",", "."))
        except (TypeError, ValueError):
            continue
        if cid == home_id:
            found["home"] = value
        elif cid == away_id:
            found["away"] = value
    return found


def extract_games(payload):
    """Find game collections across API response variants."""
    candidates = []
    def walk(obj, path="root", depth=0):
        if depth > 5:
            return
        if isinstance(obj, dict):
            for key, value in obj.items():
                if isinstance(value, list) and norm(key) in ("games", "events", "fixtures", "matches", "results"):
                    candidates.append((path + "." + key, value))
                if isinstance(value, (dict, list)):
                    walk(value, path + "." + str(key), depth + 1)
        elif isinstance(obj, list):
            for i, value in enumerate(obj[:3]):
                if isinstance(value, (dict, list)):
                    walk(value, path + "[" + str(i) + "]", depth + 1)
    walk(payload)
    if not candidates:
        return [], []
    candidates.sort(key=lambda item: len(item[1]), reverse=True)
    return candidates[0][1], [{"path": p, "count": len(items)} for p, items in candidates]


def team_key(value):
    value = norm(value)
    aliases = {
        "man utd": "manchester united", "man united": "manchester united",
        "man city": "manchester city", "spurs": "tottenham hotspur",
        "inter milan": "inter", "internazionale": "inter",
        "ac milan": "milan", "atletico madrid": "atletico",
        "atletico de madrid": "atletico", "athletic bilbao": "athletic club",
        "paris saint germain": "psg", "paris sg": "psg",
        "borussia monchengladbach": "monchengladbach",
        "bayern munchen": "bayern munich", "koln": "cologne",
        "1 fc koln": "cologne", "fc koln": "cologne",
    }
    value = aliases.get(value, value)
    return value


def compare_football_data(rows):
    token = os.getenv("FOOTBALL_DATA_TOKEN", "").strip()
    if not token:
        return {"available": False, "error": "FOOTBALL_DATA_TOKEN is not configured"}
    fd_codes = {"eng": "PL", "esp": "PD", "ita": "SA", "ger": "BL1", "fra": "FL1"}
    result = {"available": True, "source": "Football-Data.org API v4", "leagues": {}, "missingMatches": []}
    for league, comp in fd_codes.items():
        try:
            response = requests.get(
                f"https://api.football-data.org/v4/competitions/{comp}/matches",
                headers={"X-Auth-Token": token},
                params={"dateFrom": str(START), "dateTo": str(END)},
                timeout=(15, 45),
            )
            if response.status_code == 429:
                time.sleep(float(response.headers.get("Retry-After", "65")))
                response = requests.get(
                    f"https://api.football-data.org/v4/competitions/{comp}/matches",
                    headers={"X-Auth-Token": token},
                    params={"dateFrom": str(START), "dateTo": str(END)},
                    timeout=(15, 45),
                )
            response.raise_for_status()
            fixtures = response.json().get("matches", [])
            candidates = [r for r in rows if r["league"] == league]
            used = set()
            matched = 0
            missing = []
            for fixture in fixtures:
                home = (fixture.get("homeTeam") or {}).get("name") or ""
                away = (fixture.get("awayTeam") or {}).get("name") or ""
                kickoff = fixture.get("utcDate") or ""
                try:
                    fd_day = datetime.fromisoformat(kickoff.replace("Z", "+00:00")).date()
                except Exception:
                    fd_day = None
                best = None
                for idx, candidate in enumerate(candidates):
                    if idx in used:
                        continue
                    try:
                        d365 = datetime.fromisoformat(str(candidate.get("date")).replace("Z", "+00:00")).date()
                    except Exception:
                        d365 = None
                    if fd_day and d365 and abs((fd_day - d365).days) > 1:
                        continue
                    hs = SequenceMatcher(None, team_key(home), team_key(candidate.get("home"))).ratio()
                    aws = SequenceMatcher(None, team_key(away), team_key(candidate.get("away"))).ratio()
                    score = (hs + aws) / 2
                    if hs >= 0.78 and aws >= 0.78 and (best is None or score > best[0]):
                        best = (score, idx, candidate)
                if best:
                    _, idx, candidate = best
                    used.add(idx)
                    matched += 1
                    candidate["footballDataMatch"] = True
                    candidate["footballDataId"] = fixture.get("id")
                else:
                    item = {
                        "league": league, "date": kickoff, "home": home, "away": away,
                        "status": fixture.get("status"),
                    }
                    missing.append(item)
                    result["missingMatches"].append(item)
            result["leagues"][league] = {
                "footballDataFixtures": len(fixtures),
                "matched365Scores": matched,
                "missingIn365Scores": len(missing),
                "missingSample": missing[:30],
            }
        except Exception as exc:
            result["leagues"][league] = {"error": str(exc)[:300]}
        time.sleep(7)
    return result


def main():
    matches = {}
    errors = []
    payload_diagnostics = []
    cursor = START
    while cursor <= END:
        chunk_end = min(cursor + timedelta(days=6), END)
        params = {
            "sports": 1,
            "startDate": cursor.strftime("%d/%m/%Y"),
            "endDate": chunk_end.strftime("%d/%m/%Y"),
            "showOdds": "false",
            "onlyMajorGames": "false",
            "withTop": "false",
            "appTypeId": 5,
            "langId": 1,
            "timezoneName": "Europe/Berlin",
            "userCountryId": 3,
        }
        try:
            payload = get_json("games/allscores", params)
            games, collections = extract_games(payload)
            if len(payload_diagnostics) < 3:
                payload_diagnostics.append({
                    "from": str(cursor), "to": str(chunk_end),
                    "topLevelKeys": list(payload.keys())[:40] if isinstance(payload, dict) else [],
                    "collections": collections,
                    "competitionSample": (payload.get("competitions") or [])[:8] if isinstance(payload, dict) else [],
                    "gameSample": (games[:3] if games else []),
                    "preview": json.dumps(payload, ensure_ascii=False)[:1200],
                })
            competitions = {c.get("id"): c for c in payload.get("competitions", []) if isinstance(c, dict)}
            countries = {c.get("id"): c.get("name", "") for c in payload.get("countries", []) if isinstance(c, dict)}
            for game in games:
                code = league_code(game, competitions, countries)
                gid = game.get("id") or game.get("gameId")
                if code and gid is not None:
                    matches[str(gid)] = (code, game)
        except Exception as exc:
            errors.append({"stage": "fixtures", "from": str(cursor), "to": str(chunk_end), "error": str(exc)[:300]})
        cursor = chunk_end + timedelta(days=1)
        time.sleep(0.25)

    summary = {code: {"matches": 0, "both_xg": 0, "one_xg": 0, "no_xg": 0, "stats_errors": 0} for code in LEAGUES}
    rows = []
    for gid, (code, game) in matches.items():
        summary[code]["matches"] += 1
        home_id, away_id = competitor_id(game, "home"), competitor_id(game, "away")
        row = {
            "id": gid, "league": code,
            "date": game.get("startTime") or game.get("startDate") or game.get("date"),
            "home": competitor_name(game, "home"),
            "away": competitor_name(game, "away"),
        }
        try:
            payload = get_json("game/stats", {"games": gid, "appTypeId": 5, "langId": 1, "timezoneName": "Europe/Berlin", "userCountryId": 3})
            xg = parse_xg(payload.get("statistics") or [], home_id, away_id)
            row["homeXg"] = xg.get("home")
            row["awayXg"] = xg.get("away")
            if "home" in xg and "away" in xg:
                summary[code]["both_xg"] += 1
                row["xgCoverage"] = "both"
            elif xg:
                summary[code]["one_xg"] += 1
                row["xgCoverage"] = "one"
            else:
                summary[code]["no_xg"] += 1
                row["xgCoverage"] = "none"
        except Exception as exc:
            summary[code]["stats_errors"] += 1
            row["xgCoverage"] = "error"
            row["error"] = str(exc)[:200]
        rows.append(row)
        time.sleep(0.25)

    report = {
        "source": "365Scores public web JSON endpoints; unofficial and subject to change",
        "period": {"from": str(START), "to": str(END)},
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "fixtureCount": len(matches),
        "summary": summary,
        "fixtureErrors": errors,
        "payloadDiagnostics": payload_diagnostics,
        "matches": rows,
    }
    Path("365scores-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "period": report["period"], "fixtureCount": len(matches),
        "summary": summary, "footballDataCrossCheck": cross_check, "fixtureErrors": errors,
        "payloadDiagnostics": payload_diagnostics,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
