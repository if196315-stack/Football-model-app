import json
import re
import time
from datetime import datetime, date, timezone
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

BASE = "https://soccer365.ru"
START = date(2026, 5, 25)
END = date(2026, 11, 8)
LEAGUES = {"eng": 12, "esp": 16, "ita": 15, "ger": 17, "fra": 18}
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
    "Accept-Language": "ru,en;q=0.9",
}
S = requests.Session()
S.headers.update(HEADERS)


def get(url):
    last = None
    for attempt in range(4):
        try:
            r = S.get(url, timeout=(15, 45))
            r.raise_for_status()
            return r.text
        except Exception as e:
            last = e
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
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


def parse_match(url, league):
    html = get(url)
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    text = soup.get_text(" ", strip=True)
    d = match_date(html[:100000] + " " + title + " " + text[:5000])
    vals = re.findall(
        r"(?<![\w])([0-9]+(?:[.,][0-9]+)?)\s*Ожидаемые\s+голы\s*\(xG\)\s*([0-9]+(?:[.,][0-9]+)?)",
        text, re.I
    )
    home_xg = away_xg = None
    if vals:
        home_xg, away_xg = [float(x.replace(",", ".")) for x in vals[0]]
    return {
        "league": league, "url": url, "title": title,
        "date": d.isoformat() if d else None,
        "homeXg": home_xg, "awayXg": away_xg,
        "xgBoth": home_xg is not None and away_xg is not None,
    }


def main():
    report = {
        "source": "Soccer365.ru public competition archives and match pages",
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
            time.sleep(0.2)

        report["matches"].extend(rows)
        report["leagues"][code] = {
            "archives": archive_reports,
            "uniqueCandidatePages": len(found),
            "inPeriodPages": len(rows),
            "bothXg": sum(x["xgBoth"] for x in rows),
            "noBothXg": sum(not x["xgBoth"] for x in rows),
            "xgCoverageAmongInPeriod": round(sum(x["xgBoth"] for x in rows) / max(1, len(rows)), 4),
            "sampleWithoutXg": [x for x in rows if not x["xgBoth"]][:8],
        }

    Path("soccer365-xg-audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({
        "period": report["period"], "leagues": report["leagues"],
        "errors": report["errors"][:30], "totalPages": len(report["matches"]),
        "totalBothXg": sum(x["xgBoth"] for x in report["matches"]),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
