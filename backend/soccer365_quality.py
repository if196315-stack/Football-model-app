"""Deterministic integrity checks for normalized Soccer365 match records.

This module reports data-quality problems without modifying records or filling gaps.
It is intentionally independent of the production update pipeline.
"""
from __future__ import annotations

import re
import unicodedata
from collections import defaultdict
from typing import Any, Iterable


def _text(value: Any) -> str:
    return " ".join(str(value or "").split()).casefold()


def _team_key(value: Any) -> str:
    value = unicodedata.normalize("NFKC", _text(value))
    return re.sub(r"[^\\w]", "", value, flags=re.UNICODE)


def _issue(code: str, severity: str, match_id: str | None, message: str) -> dict[str, Any]:
    return {"code": code, "severity": severity, "matchId": match_id, "message": message}


def validate_match_records(records: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Return deterministic integrity findings; never mutate or repair input records."""
    rows = list(records)
    issues: list[dict[str, Any]] = []
    seen_ids: dict[str, int] = {}
    fingerprints: dict[tuple[str, str, str, str, str], list[int]] = defaultdict(list)

    for index, row in enumerate(rows):
        raw_id = row.get("id")
        match_id = str(raw_id).strip() if raw_id is not None else None
        if not match_id:
            issues.append(_issue("missing_match_id", "error", None, "Stable source match ID is missing"))
        elif match_id in seen_ids:
            issues.append(_issue("duplicate_match_id", "error", match_id,
                                 f"Match ID also appears at record index {seen_ids[match_id]}"))
        else:
            seen_ids[match_id] = index

        home, away = _team_key(row.get("home")), _team_key(row.get("away"))
        if not home or not away:
            issues.append(_issue("missing_team", "error", match_id, "Home or away team is missing"))
        elif home == away:
            issues.append(_issue("same_home_away", "error", match_id, "Home and away resolve to the same team"))

        competition = _text(row.get("competitionId") or row.get("competition"))
        kickoff = _text(row.get("utcDate") or row.get("localKickoff"))
        if not competition:
            issues.append(_issue("missing_competition", "warning", match_id,
                                 "Competition identity is missing; do not pool this record by country alone"))
        if not row.get("utcDate") and not row.get("localKickoff"):
            issues.append(_issue("missing_kickoff", "warning", match_id, "No usable kickoff timestamp is present"))

        if home and away and competition and kickoff:
            fingerprints[(home, away, competition, kickoff, _text(row.get("competitionType")))].append(index)

        home_score, away_score = row.get("homeScore"), row.get("awayScore")
        for field, score in (("homeScore", home_score), ("awayScore", away_score)):
            if score is not None and (isinstance(score, bool) or not isinstance(score, int) or score < 0):
                issues.append(_issue("invalid_score", "error", match_id,
                                     f"{field} must be a non-negative integer or null"))

        if row.get("status") == "FINISHED" and (home_score is None or away_score is None):
            issues.append(_issue("finished_without_score", "warning", match_id,
                                 "Finished match has no complete final score"))

        stats = row.get("statistics") or {}
        hxg, axg = stats.get("homeXg"), stats.get("awayXg")
        for field, value in (("homeXg", hxg), ("awayXg", axg)):
            if value is not None and (
                isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 20
            ):
                issues.append(_issue("invalid_xg", "error", match_id,
                                     f"{field} must be between 0 and 20 or null"))
        if (hxg is None) != (axg is None):
            issues.append(_issue("partial_xg_pair", "warning", match_id,
                                 "Only one team's xG is present; retain both values as supplied and exclude from paired-xG analysis"))
        if bool(stats.get("xgAvailable")) != (hxg is not None and axg is not None):
            issues.append(_issue("xg_flag_mismatch", "warning", match_id,
                                 "xgAvailable does not agree with the nullable xG pair"))

    for fingerprint, indexes in fingerprints.items():
        if len(indexes) > 1:
            for index in indexes[1:]:
                row = rows[index]
                issues.append(_issue("possible_duplicate_fixture", "warning",
                                     str(row.get("id")) if row.get("id") is not None else None,
                                     f"Same teams, competition, type and kickoff as record index {indexes[0]}"))

    errors = sum(item["severity"] == "error" for item in issues)
    warnings = sum(item["severity"] == "warning" for item in issues)
    return {
        "recordCount": len(rows),
        "errorCount": errors,
        "warningCount": warnings,
        "valid": errors == 0,
        "issues": issues,
    }
