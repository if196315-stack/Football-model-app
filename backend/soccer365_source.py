"""Soccer365 HTML adapter.

This module deliberately does not fall back to Football-Data.org or Openplay.
It parses only fields explicitly present on Soccer365 pages; missing values stay None.
The production pipeline is not switched to this adapter until source access, terms,
coverage, and parser fixtures have been validated.
"""
from __future__ import annotations

import html
import json
import re
import time
from datetime import datetime, timezone
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlparse

import requests

BASE_URL = "https://soccer365.net"
MATCH_PATH = re.compile(r"/(?:live/(?:match|game)-[^/?]+|games/\d+)/?", re.I)
XG_PAIR = re.compile(
    r"([0-9]+(?:[.,][0-9]+)?)\s*(?:Ожидаемые\s+голы\s*\(xG\)|Expected\s+goals\s*\(xG\))"
    r"\s*([0-9]+(?:[.,][0-9]+)?)",
    re.I,
)
DATE_DMY = re.compile(r"(?<!\d)(\d{1,2})\.(\d{1,2})\.(20\d{2})(?:\s+(\d{1,2}):(\d{2}))?")
SCORE = re.compile(r"(?<!\d)(\d{1,2})\s*[:–-]\s*(\d{1,2})(?!\d)")


class _PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.text_parts: list[str] = []
        self.links: list[str] = []
        self.title_parts: list[str] = []
        self._in_title = False
        self._in_script = False
        self._script_type = ""
        self.jsonld: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "a" and values.get("href"):
            self.links.append(str(values["href"]))
        if tag == "title":
            self._in_title = True
        if tag == "script":
            self._in_script = True
            self._script_type = str(values.get("type") or "").lower()

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False
        if tag == "script":
            self._in_script = False
            self._script_type = ""

    def handle_data(self, data: str) -> None:
        value = data.strip()
        if not value:
            return
        if self._in_title:
            self.title_parts.append(value)
        if self._in_script and self._script_type == "application/ld+json":
            self.jsonld.append(data)
        elif not self._in_script:
            self.text_parts.append(value)


def _number(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        result = float(value.replace(",", "."))
        return result if 0 <= result <= 20 else None
    except (TypeError, ValueError):
        return None


def _parse_datetime(value: str | None) -> str | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        # A naive timestamp has no declared timezone; never silently label it UTC.
        if parsed.tzinfo is None:
            return None
        return parsed.astimezone(timezone.utc).isoformat()
    except ValueError:
        return None


def _jsonld_objects(raw_scripts: list[str]) -> list[dict[str, Any]]:
    objects: list[dict[str, Any]] = []
    for raw in raw_scripts:
        try:
            payload = json.loads(raw)
        except (TypeError, ValueError):
            continue
        candidates = payload if isinstance(payload, list) else [payload]
        for item in candidates:
            if isinstance(item, dict):
                graph = item.get("@graph")
                if isinstance(graph, list):
                    objects.extend(x for x in graph if isinstance(x, dict))
                else:
                    objects.append(item)
    return objects


def _teams_from_title(title: str) -> tuple[str | None, str | None]:
    head = re.split(r"\s*:\s*", title, maxsplit=1)[0]
    head = re.sub(r"\s*(?:смотреть онлайн|watch online).*$", "", head, flags=re.I)
    parts = re.split(r"\s+(?:vs?\.?|[-–—])\s+", head, maxsplit=1, flags=re.I)
    if len(parts) != 2:
        return None, None
    home, away = (html.unescape(x).strip(" \t-–—") for x in parts)
    return (home or None, away or None)


def extract_match_links(page_html: str, page_url: str = BASE_URL) -> list[str]:
    parser = _PageParser()
    parser.feed(page_html)
    found = set()
    for href in parser.links:
        absolute = urljoin(page_url, html.unescape(href).split("?", 1)[0])
        parsed = urlparse(absolute)
        if parsed.hostname and parsed.hostname.lower().rstrip(".") in {
            "soccer365.net", "www.soccer365.net"
        } and MATCH_PATH.search(parsed.path):
            found.add(absolute.rstrip("/") + "/")
    return sorted(found)


def parse_match_page(
    page_html: str,
    url: str,
    *,
    country: str | None = None,
    competition_id: str | int | None = None,
    competition_name: str | None = None,
    competition_type: str | None = None,
) -> dict[str, Any]:
    """Normalize a match page; absent or ambiguous source values are left null."""
    parser = _PageParser()
    parser.feed(page_html)
    title = " ".join(parser.title_parts).strip()
    text = " ".join(parser.text_parts)
    objects = _jsonld_objects(parser.jsonld)

    home, away = _teams_from_title(title)
    kickoff = None
    local_kickoff = None
    home_score = away_score = None
    for item in objects:
        item_type = item.get("@type")
        if item_type in ("SportsEvent", "Event") or (
            isinstance(item_type, list) and any(x in ("SportsEvent", "Event") for x in item_type)
        ):
            kickoff = _parse_datetime(item.get("startDate"))
            if not home and isinstance(item.get("homeTeam"), dict):
                home = item["homeTeam"].get("name")
            if not away and isinstance(item.get("awayTeam"), dict):
                away = item["awayTeam"].get("name")
            score = item.get("homeTeamScore")
            if score is not None:
                try:
                    home_score = int(score)
                except (TypeError, ValueError):
                    pass
            score = item.get("awayTeamScore")
            if score is not None:
                try:
                    away_score = int(score)
                except (TypeError, ValueError):
                    pass
            break

    if kickoff is None:
        date_match = DATE_DMY.search(text)
        if date_match:
            day, month, year, hour, minute = date_match.groups()
            # Keep local wall time unless the page exposes an explicit timezone.
            local_kickoff = f"{year}-{int(month):02d}-{int(day):02d}"
            if hour is not None:
                local_kickoff += f"T{int(hour):02d}:{int(minute or 0):02d}:00"

    # The first xG pair on Soccer365's page is the full-match value; later
    # pairs represent half-specific values. Never sum those periods.
    xg_match = XG_PAIR.search(text)
    home_xg = _number(xg_match.group(1)) if xg_match else None
    away_xg = _number(xg_match.group(2)) if xg_match else None

    status = None
    lowered = text.lower()
    if any(word in lowered for word in ("завершен", "матч окончен", "finished", "full time")):
        status = "FINISHED"
    elif any(word in lowered for word in ("перенесен", "отложен", "postponed")):
        status = "POSTPONED"
    elif any(word in lowered for word in ("отменен", "cancelled", "canceled")):
        status = "CANCELLED"
    # Unknown status stays null; minute markers and generic navigation text
    # are not reliable evidence that a match is currently live.

    return {
        "id": url.rstrip("/").rsplit("/", 1)[-1],
        "source": "Soccer365",
        "sourceUrl": url,
        "country": country,
        "competitionId": str(competition_id) if competition_id is not None else None,
        "competition": competition_name,
        "competitionType": competition_type,
        "utcDate": kickoff,
        "localKickoff": local_kickoff,
        "status": status,
        "home": home,
        "away": away,
        "homeScore": home_score,
        "awayScore": away_score,
        "statistics": {
            "homeXg": home_xg,
            "awayXg": away_xg,
            "xgAvailable": home_xg is not None and away_xg is not None,
        },
    }


class Soccer365Client:
    """Small polite HTTP client. It does not bypass bot checks or access controls."""

    def __init__(self, session: requests.Session | None = None, min_delay: float = 2.0):
        self.session = session or requests.Session()
        self.session.headers.update({
            "User-Agent": "MatchIQ/1.0 (football data source integration)",
            "Accept-Language": "en,ru;q=0.8",
        })
        self.min_delay = max(1.0, float(min_delay))
        self._last_request = 0.0

    @staticmethod
    def _validate_url(url: str) -> None:
        parsed = urlparse(url)
        if (
            parsed.scheme != "https"
            or parsed.hostname not in {"soccer365.net", "www.soccer365.net"}
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port not in (None, 443)
        ):
            raise ValueError("Only HTTPS Soccer365.net URLs are allowed")

    def get(self, url: str) -> str:
        self._validate_url(url)
        delay = self.min_delay - (time.monotonic() - self._last_request)
        if delay > 0:
            time.sleep(delay)
        last_error: Exception | None = None
        for attempt in range(5):
            try:
                response = self.session.get(url, timeout=(15, 45), allow_redirects=False)
                self._last_request = time.monotonic()
                # Never follow redirects automatically: a trusted page must not
                # redirect the client to an unapproved host or downgrade HTTPS.
                if response.is_redirect or response.is_permanent_redirect:
                    raise ValueError("Soccer365 redirect refused; validate the destination explicitly")
                if response.status_code in (401, 403, 404, 451):
                    response.raise_for_status()
                if response.status_code in (429, 500, 502, 503, 504):
                    if attempt == 4:
                        response.raise_for_status()
                    retry_after = response.headers.get("Retry-After")
                    try:
                        wait = float(retry_after) if retry_after else min(15 * (2 ** attempt), 180)
                    except ValueError:
                        wait = min(15 * (2 ** attempt), 180)
                    time.sleep(max(1, wait))
                    continue
                response.raise_for_status()
                return response.text
            except requests.RequestException as exc:
                last_error = exc
                if attempt == 4:
                    raise
                time.sleep(min(10 * (2 ** attempt), 120))
        raise RuntimeError(f"Soccer365 request failed: {last_error}")
