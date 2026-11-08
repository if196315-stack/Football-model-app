"""Manifest-driven Soccer365 collector.

The collector performs no discovery beyond links found on explicitly verified
competition pages. It does not infer URL patterns, follow redirects, bypass
access controls, or write to the historical store.
"""
from __future__ import annotations

from typing import Any, Callable, Iterable
from urllib.parse import urlparse

from soccer365_catalog import validate_competition_catalog
from soccer365_source import extract_match_links, parse_match_page
from soccer365_quality import validate_match_records


def _allowed_match_url(url: str) -> bool:
    try:
        parsed = urlparse(url)
        return (
            parsed.scheme == "https"
            and parsed.hostname in {"soccer365.net", "www.soccer365.net"}
            and parsed.username is None
            and parsed.password is None
            and parsed.port in (None, 443)
            and not parsed.query
            and not parsed.fragment
        )
    except ValueError:
        return False


def collect_catalog(
    client: Any,
    entries: Iterable[dict[str, Any]],
    *,
    link_extractor: Callable[[str, str], Iterable[str]] | None = None,
) -> dict[str, Any]:
    """Fetch verified competition pages and their linked match pages.

    The supplied client must expose get(url) -> HTML text. Fetch failures are
    recorded per page and make the run incomplete; successful partial records
    are returned for inspection but must not be treated as a complete season.
    """
    manifest = list(entries)
    extract_links = link_extractor or extract_match_links
    validation = validate_competition_catalog(manifest)
    if not validation["valid"]:
        return {
            "complete": False,
            "catalogValid": False,
            "catalogValidation": validation,
            "records": [],
            "qualityReport": validate_match_records([]),
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

        try:
            match_urls = sorted(set(extract_links(listing_html, competition_url)))
        except Exception as exc:
            errors.append({
                "url": competition_url,
                "stage": "competition_link_extraction",
                "errorType": type(exc).__name__,
                "message": str(exc),
            })
            reports.append(report)
            continue
        rejected_links = [url for url in match_urls if not isinstance(url, str) or not _allowed_match_url(url)]
        if rejected_links:
            errors.extend({
                "url": str(url),
                "stage": "match_link_validation",
                "errorType": "UntrustedMatchLink",
                "message": "Extracted match URL is not a canonical HTTPS Soccer365 URL",
            } for url in rejected_links)
        match_urls = [url for url in match_urls if isinstance(url, str) and _allowed_match_url(url)]
        report["discoveredMatchLinks"] = len(match_urls)
        if not match_urls:
            warnings.append({
                "competitionId": entry["competitionId"],
                "season": entry["season"],
                "code": "no_match_links_found",
            })

        for match_url in match_urls:
            if match_url in seen_match_urls:
                errors.append({
                    "url": match_url,
                    "stage": "catalog_identity",
                    "errorType": "DuplicateMatchLink",
                    "message": "Match URL appeared under more than one competition entry; identity requires review",
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

    quality_report = validate_match_records(records)
    return {
        "complete": not errors and quality_report["valid"] and not any(
            warning.get("code") == "no_match_links_found" for warning in warnings
        ),
        "catalogValid": True,
        "catalogValidation": validation,
        "records": records,
        "qualityReport": quality_report,
        "competitionReports": reports,
        "errors": errors,
        "warnings": warnings,
    }
