import json
import re
import time
from datetime import datetime, date
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

BASE = "https://soccer365.ru"
START = date(2026, 5, 25)
END = date(2026, 11, 8)
LEAGUES = {
    "eng": 12,
    "esp": 16,
    "ita": 15,
    "ger": 17,
    "fra": 18,
}
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
            if r.status_code == 429 or r.status_code >= 500:
                r.raise_for_status()
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
    # Match pages expose the kickoff date in the page title/header.
    for pat in (r"(\d{1,2})\.(\d{1,2})\.(20\d{2})", r"(\d{1,2})/(\d{1,2})/(20\d{2})"):
        m = re.search(pat, text)
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
    d = match_date(title + " " + text[:2500])
    # xG values appear on the page as: number, "Ожидаемые голы (xG)", number.
    vals = re.findall(r"(?<![\w])([0-9]+(?:[.,][0-9]+)?)\s*Ожидаемые\s+голы\s*\(xG\)\s*([0-9]+(?:[.,][0-9]+)?)", text, re.I)
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
        "source": "Soccer365.ru public match pages and competition result archives",
        "period": {"from": str(START), "to": str(END)},
        "generatedAt": datetime.utcnow().isoformat(timespec="seconds") + "Z",
        "leagues": {}, "matches": [], "errors": [],
    }
    urls = {}
    for code, comp_id in LEAGUES.items():
        for season in ("current",):
            page_url = f"{BASE}/competitions/{comp_id}/results/"
            try:
                html = get(page_url)
                soup = BeautifulSoup(html, "html.parser")
                found = set()
                href_samples = []
                raw_match_samples = re.findall(r".{0,100}match.{0,160}", html, re.I)[:20]
                for a in soup.select("a[href]"):
                    href = a.get("href", "")
                    if "match" in href.lower():
                        if len(href_samples) < 30:
                            href_samples.append(href)
                        if re.search(r"/(?:live/)?match[-_/]", href, re.I):
                            found.add(urljoin(BASE, href.split("?")[0]))
                urls[code] = urls.get(code, set()) | found
                report["leagues"].setdefault(code, {})[season] = {
                    "archiveUrl": page_url, "matchLinks": len(found), "pageTitle": soup.title.get_text(" ", strip=True) if soup.title else "", "htmlBytes": len(str(soup)), "hrefSamples": href_samples, "rawMatchSamples": raw_match_samples
                }
            except Exception as e:
                report["errors"].append({"league": code, "url": page_url, "error": str(e)[:250]})

    for code, found in urls.items():
        rows = []
        for url in sorted(found):
            try:
                row = parse_match(url, code)
                if row["date"]:
                    d = date.fromisoformat(row["date"])
                    if not START <= d <= END:
                        continue
                else:
                    # Keep undated pages in diagnostics, never count as verified coverage.
                    row["dateStatus"] = "unparsed"
                rows.append(row)
            except Exception as e:
                report["errors"].append({"league": code, "url": url, "error": str(e)[:250]})
            time.sleep(0.35)
        report["matches"].extend(rows)
        report["leagues"].setdefault(code, {})["audit"] = {
            "candidatePages": len(found),
            "inPeriodPages": len(rows),
            "datedPages": sum(bool(x["date"]) for x in rows),
            "bothXg": sum(x["xgBoth"] for x in rows),
            "noBothXg": sum(not x["xgBoth"] for x in rows),
            "xgCoverageAmongDated": round(
                sum(x["xgBoth"] for x in rows if x["date"]) /
                max(1, sum(bool(x["date"]) for x in rows)), 4
            ),
            "sampleWithoutXg": [x for x in rows if not x["xgBoth"]][:8],
        }
    Path("soccer365-xg-audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({
        "period": report["period"], "leagues": report["leagues"],
        "errors": report["errors"][:20], "totalPages": len(report["matches"]),
        "totalBothXg": sum(x["xgBoth"] for x in report["matches"]),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
