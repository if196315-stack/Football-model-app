import json
import re
import time
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


def league_code(game):
    comp = game.get("competition") or game.get("tournament") or {}
    name = norm(comp.get("name") or game.get("competitionName") or "")
    country = norm((comp.get("country") or {}).get("name") if isinstance(comp.get("country"), dict) else comp.get("country"))
    hay = name + " " + country
    for code, aliases in LEAGUES.items():
        if any(norm(alias) in hay for alias in aliases):
            return code
    # Some payloads only use the canonical league name.
    if "premier league" in name and ("england" in hay or "english" in hay):
        return "eng"
    if "la liga" in name or "laliga" in name:
        return "esp"
    if "serie a" in name and ("italy" in hay or "italia" in hay):
        return "ita"
    if "bundesliga" in name and ("germany" in hay or "deutschland" in hay):
        return "ger"
    if "ligue 1" in name and ("france" in hay or "frankreich" in hay):
        return "fra"
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
                    "preview": json.dumps(payload, ensure_ascii=False)[:2500],
                })
            for game in games:
                code = league_code(game)
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
        "summary": summary, "fixtureErrors": errors,
        "payloadDiagnostics": payload_diagnostics,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
