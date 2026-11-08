"""Manifest-driven Soccer365 collector.

The collector performs no discovery beyond links found on explicitly verified
competition pages. It does not infer URL patterns, follow redirects, bypass
access controls, or write to the historical store.
"""
from __future__ import annotations

from typing import Any, Iterable

from soccer365_catalog import validate_competition_catalog
from soccer365_source import extract_match_links, parse_match_page


def collect_catalog(client: Any, entries: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Fetch verified competition pages and their linked match pages.

    The supplied client must expose get(url) -> HTML text. Fetch failures are
    recorded per page and make the run incomplete; successful partial records
    are returned for inspection but must not be treated as a complete season.
    """
    manifest = list(entries)
    validation = validate_competition_catalog(manifest)
    if not validation["valid"]:
        return {
            "complete": False,
            "catalogValid": False,
            "catalogValidation": validation,
            "records": [],
            "competitionReports": [],
            "errors": [],
            "warnings": [],
        }

    records: list[dict[str, Any]] = []
    reports: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    seen_match_urls: set[str] = set()

    for entry in manifest:
        competition_url = entry["url"].strip()
        report = {
            "competitionId": entry["competitionId"],
            "competitionName": entry["competitionName"],
            "competitionType": entry["competitionType"],
            "season": entry["season"],
            "competitionUrl": competition_url,
            "discoveredMatchLinks": 0,
            "parsedMatches": 0,
            "failedMatchPages": 0,
        }
        try:
            listing_html = client.get(competition_url)
        except Exception as exc:
            errors.append({
                "url": competition_url,
                "stage": "competition_page",
                "errorType": type(exc).__name__,
                "message": str(exc),
            })
            reports.append(report)
            continue

        match_urls = extract_match_links(listing_html, competition_url)
        report["discoveredMatchLinks"] = len(match_urls)
        if not match_urls:
            warnings.append({
                "competitionId": entry["competitionId"],
                "season": entry["season"],
                "code": "no_match_links_found",
            })

        for match_url in match_urls:
            if match_url in seen_match_urls:
                warnings.append({
                    "url": match_url,
                    "code": "duplicate_match_link_across_catalog",
                })
                continue
            seen_match_urls.add(match_url)
            try:
                match_html = client.get(match_url)
                record = parse_match_page(
                    match_html,
                    match_url,
                    country=entry["country"],
                    competition_id=entry["competitionId"],
                    competition_name=entry["competitionName"],
                    competition_type=entry["competitionType"],
                    season=entry["season"],
                )
                records.append(record)
                report["parsedMatches"] += 1
            except Exception as exc:
                report["failedMatchPages"] += 1
                errors.append({
                    "url": match_url,
                    "stage": "match_page",
                    "errorType": type(exc).__name__,
                    "message": str(exc),
                })

        reports.append(report)

    return {
        "complete": not errors,
        "catalogValid": True,
        "catalogValidation": validation,
        "records": records,
        "competitionReports": reports,
        "errors": errors,
        "warnings": warnings,
    }
