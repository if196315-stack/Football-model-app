"""Explicit Soccer365 competition catalogue validation.

No URL patterns, season labels, competition IDs, or competition types are guessed.
This module validates a human-verified manifest; it performs no network requests.
"""
from __future__ import annotations

from typing import Any, Iterable
from urllib.parse import urlparse


COMPETITION_TYPES = {
    "league",
    "domestic_cup",
    "international_cup",
    "friendly",
    "national_team",
}


def _nonempty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_competition_catalog(entries: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Validate explicit competition-season entries and report all manifest issues.

    Required fields: country, competitionId, competitionName, competitionType,
    season, and a directly verified Soccer365 competition URL.
    IDs are unique per competition+season+type; URLs must be HTTPS on the
    canonical Soccer365 host. Redirects are not followed or resolved here.
    """
    rows = list(entries)
    issues: list[dict[str, Any]] = []
    seen_keys: dict[tuple[str, str, str], int] = {}
    seen_urls: dict[str, int] = {}

    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            issues.append({"index": index, "code": "entry_not_object", "severity": "error"})
            continue

        for field in ("country", "competitionId", "competitionName", "season"):
            if not _nonempty(row.get(field)):
                issues.append({
                    "index": index,
                    "code": f"missing_{field}",
                    "severity": "error",
                })

        if "expectedFinishedMatches" in row:
            expected = row.get("expectedFinishedMatches")
            if isinstance(expected, bool) or not isinstance(expected, int) or expected < 0:
                issues.append({
                    "index": index,
                    "code": "invalid_expected_finished_matches",
                    "severity": "error",
                })

        competition_type = row.get("competitionType")
        if competition_type not in COMPETITION_TYPES:
            issues.append({
                "index": index,
                "code": "invalid_competition_type",
                "severity": "error",
                "allowed": sorted(COMPETITION_TYPES),
            })

        url = row.get("url")
        if not _nonempty(url):
            issues.append({"index": index, "code": "missing_url", "severity": "error"})
        else:
            parsed = urlparse(url.strip())
            try:
                port = parsed.port
            except ValueError:
                port = -1
            if (
                parsed.scheme != "https"
                or parsed.hostname not in {"soccer365.net", "www.soccer365.net"}
                or parsed.username is not None
                or parsed.password is not None
                or port not in (None, 443)
                or parsed.query
                or parsed.fragment
            ):
                issues.append({
                    "index": index,
                    "code": "untrusted_catalog_url",
                    "severity": "error",
                })
            else:
                canonical_url = f"https://{parsed.hostname.lower()}{parsed.path.rstrip('/')}/"
                if canonical_url in seen_urls:
                    issues.append({
                        "index": index,
                        "code": "duplicate_catalog_url",
                        "severity": "error",
                        "first_index": seen_urls[canonical_url],
                    })
                else:
                    seen_urls[canonical_url] = index

        if all(_nonempty(row.get(field)) for field in
               ("competitionId", "season", "competitionType")):
            key = (
                row["competitionId"].strip(),
                row["season"].strip(),
                row["competitionType"],
            )
            if key in seen_keys:
                issues.append({
                    "index": index,
                    "code": "duplicate_competition_season",
                    "severity": "error",
                    "first_index": seen_keys[key],
                })
            else:
                seen_keys[key] = index

    errors = sum(issue["severity"] == "error" for issue in issues)
    return {
        "entryCount": len(rows),
        "errorCount": errors,
        "valid": errors == 0,
        "issues": issues,
    }
