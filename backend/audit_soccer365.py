import json
import os
import re
import time
from datetime import datetime, date, timezone
from difflib import SequenceMatcher
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

BASE = "https://soccer365.net"
START = date(2026, 5, 25)
END = date(2026, 11, 8)
LEAGUES = {"eng": 12, "esp": 16, "ita": 15, "ger": 17, "fra": 18}
CODES = {"eng": "PL", "esp": "PD", "ita": "SA", "ger": "BL1", "fra": "FL1"}
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
    "Accept-Language": "ru,en;q=0.9",
}
S = requests.Session()
S.headers.update(HEADERS)


def get(url, headers=None):
    last = None
    for attempt in range(5):
        try:
            r = S.get(url, headers=headers, timeout=(20, 60))
            if r.status_code in (429, 500, 502, 503, 504):
                if attempt == 4:
                    r.raise_for_status()
                retry_after = r.headers.get("Retry-After")
                try:
                    wait = float(retry_after) if retry_after else min(30 * (2 ** attempt), 240)
                except ValueError:
                    wait = min(30 * (2 ** attempt), 240)
                time.sleep(wait)
                continue
            r.raise_for_status()
            return r.text
        except requests.RequestException as e:
            last = e
            if attempt == 4:
                raise
            time.sleep(min(30 * (2 ** attempt), 240))
    raise last


def match_date(text):
    iso = re.search(r'"startDate"\s*:\s*"([^"]+)"', text)
    if iso:
        try:
            return datetime.fromisoformat(iso.group(1).replace("Z", "+00:00")).date()
        except ValueError:
            pass
    m = re.search(r"(\d{1,2})\.(\d{1,2})\.(20\d{2})", text)
    if m:
        try:
            return date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        except ValueError:
            pass
    return None


def norm(name):
    value = str(name or "").lower().replace("ё", "е")
    value = re.sub(r"\b(fc|afc|cf|sc|ac|club|football|the|fk|sk|sv|vfl|tsv)\b", " ", value)
    return re.sub(r"[^a-zа-я0-9]+", "", value)


def similarity(a, b):
    return SequenceMatcher(None, norm(a), norm(b)).ratio()


def title_teams(title):
    head = title.split(":", 1)[0]
    head = re.sub(r"\s*[-–—]\s*смотреть онлайн.*$", "", head, flags=re.I)
    parts = re.split(r"\s+(?:vs\.?|[-–—])\s+", head, maxsplit=1, flags=re.I)
    return (parts[0].strip(), parts[1].strip()) if len(parts) == 2 else (None, None)


def parse_match(url, league):
    html = get(url)
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    text = soup.get_text(" ", strip=True)
    d = match_date(html[:100000] + " " + title + " " + text[:5000])

    # Prefer the competition-season link whose season contains the match date.
    # Global navigation contains several leagues and must never determine a match's competition.
    comp_candidates = []
    for a in soup.select('a[href*="/competitions/"]'):
        href = a.get("href", "")
        m = re.search(r"/competitions/(\d+)/(20\d{2})-(20\d{2})/?", href)
        if m:
            comp_candidates.append((int(m.group(1)), int(m.group(2)), int(m.group(3))))
    actual_comp_id = None
    if d:
        for cid, season_start, season_end in comp_candidates:
            if season_start <= d.year <= season_end:
                actual_comp_id = cid
                break
    if actual_comp_id is None and len({x[0] for x in comp_candidates}) == 1:
        actual_comp_id = comp_candidates[0][0]

    vals = re.findall(
        r"(?<![\w])([0-9]+(?:[.,][0-9]+)?)\s*Ожидаемые\s+голы\s*\(xG\)\s*([0-9]+(?:[.,][0-9]+)?)",
        text, re.I
    )
    home_xg = away_xg = None
    if vals:
        home_xg, away_xg = [float(x.replace(",", ".")) for x in vals[0]]
    home, away = title_teams(title)
    # English Soccer365 pages expose the two match-header club links before
    # lineup/related-match links; use them when available.
    club_names = [
        a.get_text(" ", strip=True)
        for a in soup.select('a[href*="/clubs/"]')
        if a.get_text(" ", strip=True)
    ]
    if len(club_names) >= 2:
        home, away = club_names[0], club_names[1]
    return {
        "archiveLeague": league, "competitionId": actual_comp_id,
        "url": url, "title": title, "home": home, "away": away,
        "date": d.isoformat() if d else None,
        "homeXg": home_xg, "awayXg": away_xg,
        "xgBoth": home_xg is not None and away_xg is not None,
    }


def fetch_official_fixtures():
    token = os.getenv("FOOTBALL_DATA_TOKEN", "").strip()
    if not token:
        return None, "FOOTBALL_DATA_TOKEN is not configured for the audit workflow"
    fixtures = []
    errors = []
    for league, code in CODES.items():
        url = f"https://api.football-data.org/v4/competitions/{code}/matches"
        params = {"dateFrom": str(START), "dateTo": str(END)}
        try:
            response = None
            for attempt in range(4):
                response = S.get(url, headers={"X-Auth-Token": token}, params=params, timeout=35)
                if response.status_code == 429 and attempt < 3:
                    wait = response.headers.get("Retry-After", "65")
                    try:
                        time.sleep(min(max(float(wait), 5), 180))
                    except ValueError:
                        time.sleep(65)
                    continue
                if response.status_code in (500, 502, 503, 504) and attempt < 3:
                    time.sleep(10 * (attempt + 1))
                    continue
                response.raise_for_status()
                break
            payload = response.json()
            for item in payload.get("matches", []):
                fixtures.append({
                    "league": league,
                    "date": (item.get("utcDate") or "")[:10],
                    "home": (item.get("homeTeam") or {}).get("name", ""),
                    "away": (item.get("awayTeam") or {}).get("name", ""),
                    "id": item.get("id"),
                })
        except Exception as e:
            errors.append({"league": league, "error": str(e)[:250]})
        time.sleep(10)
    return fixtures, errors


def compare_official(row, fixtures):
    if not fixtures or not row.get("date") or not row.get("home") or not row.get("away"):
        return {"officialMatch": False, "officialMatchId": None, "teamSimilarity": 0.0}
    candidates = [f for f in fixtures if f["league"] == row["archiveLeague"] and f["date"] == row["date"]]
    best = None
    for f in candidates:
        direct = min(similarity(row["home"], f["home"]), similarity(row["away"], f["away"]))
        reverse = min(similarity(row["home"], f["away"]), similarity(row["away"], f["home"]))
        score = max(direct, reverse)
        if best is None or score > best[0]:
            best = (score, f)
    if best and best[0] >= 0.84:
        return {"officialMatch": True, "officialMatchId": best[1]["id"], "teamSimilarity": round(best[0], 3)}
    return {"officialMatch": False, "officialMatchId": None, "teamSimilarity": round(best[0], 3) if best else 0.0}


def main():
    report = {
        "source": "Soccer365 public competition archives and match pages; Football-Data.org fixture cross-check",
        "period": {"from": str(START), "to": str(END)},
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "leagues": {}, "matches": [], "errors": [],
    }
    for code, comp_id in LEAGUES.items():
        found = set()
        archive_reports = []
        archive_urls = [
            f"{BASE}/competitions/{comp_id}/2025-2026/results/",
            f"{BASE}/competitions/{comp_id}/results/",
        ]
        for page_url in archive_urls:
            try:
                html = get(page_url)
                soup = BeautifulSoup(html, "html.parser")
                page_found = set()
                for a in soup.select("a[href]"):
                    href = a.get("href", "")
                    if re.search(r"/games/\d+/?$", href) or re.search(r"/live/match-[^/?]+", href):
                        page_found.add(urljoin(BASE, href.split("?")[0]))
                found.update(page_found)
                archive_reports.append({
                    "archiveUrl": page_url,
                    "pageTitle": soup.title.get_text(" ", strip=True) if soup.title else "",
                    "htmlBytes": len(html),
                    "matchLinks": len(page_found),
                })
            except Exception as e:
                report["errors"].append({"league": code, "url": page_url, "error": str(e)[:250]})

        rows = []
        for url in sorted(found):
            try:
                row = parse_match(url, code)
                if row["date"]:
                    d = date.fromisoformat(row["date"])
                    if START <= d <= END:
                        rows.append(row)
                else:
                    report["errors"].append({"league": code, "url": url, "error": "match date not parsed"})
            except Exception as e:
                report["errors"].append({"league": code, "url": url, "error": str(e)[:250]})
            time.sleep(2.0)

        report["matches"].extend(rows)
        report["leagues"][code] = {
            "expectedCompetitionId": comp_id,
            "archives": archive_reports,
            "uniqueCandidatePages": len(found),
            "inPeriodPages": len(rows),
            "correctCompetition": sum(x["competitionId"] == comp_id for x in rows),
            "wrongOrUnknownCompetition": sum(x["competitionId"] != comp_id for x in rows),
            "bothXg": sum(x["xgBoth"] for x in rows),
            "noBothXg": sum(not x["xgBoth"] for x in rows),
            "sampleWrongCompetition": [x for x in rows if x["competitionId"] != comp_id][:8],
        }

    official, official_errors = fetch_official_fixtures()
    report["officialFixtureCount"] = len(official) if official is not None else None
    report["officialFixtureErrors"] = official_errors
    if official is not None:
        for row in report["matches"]:
            row.update(compare_official(row, official))
        for code in LEAGUES:
            rows = [x for x in report["matches"] if x["archiveLeague"] == code]
            report["leagues"][code].update({
                "officialMatchesMatched": sum(x["officialMatch"] for x in rows),
                "notMatchedToOfficial": sum(not x["officialMatch"] for x in rows),
                "xgOnOfficialMatches": sum(x["officialMatch"] and x["xgBoth"] and x["competitionId"] == LEAGUES[code] for x in rows),
                "sampleNotMatched": [x for x in rows if not x["officialMatch"]][:8],
            })

    Path("soccer365-xg-audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({
        "period": report["period"], "leagues": report["leagues"],
        "officialFixtureCount": report["officialFixtureCount"],
        "officialFixtureErrors": report["officialFixtureErrors"],
        "errors": report["errors"][:30], "totalPages": len(report["matches"]),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
