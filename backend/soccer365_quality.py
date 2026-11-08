"""Deterministic integrity checks for normalized Soccer365 match records.

Reports issues without mutating records, inventing identifiers, or filling missing statistics.
"""
from __future__ import annotations

import math
import re
import unicodedata
from collections import defaultdict
from datetime import datetime
from typing import Any, Iterable


VALID_STATUSES = {"SCHEDULED", "TIMED", "IN_PLAY", "PAUSED", "FINISHED", "POSTPONED", "CANCELLED"}
DUPLICATE_KICKOFF_TOLERANCE_SECONDS = 5 * 60


def _text(value: Any) -> str:
    return " ".join(str(value or "").split()).casefold()


def _team_key(row: dict[str, Any], side: str) -> str:
    stable_id = row.get(f"{side}TeamId")
    value = str(stable_id).strip() if stable_id is not None else str(row.get(side) or "")
    value = unicodedata.normalize("NFKC", value).casefold()
    return re.sub(r"[^\w]", "", value, flags=re.UNICODE)


def _issue(code: str, severity: str, match_id: str | None, message: str) -> dict[str, Any]:
    return {"code": code, "severity": severity, "matchId": match_id, "message": message}


def _utc_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        return None
    return parsed


def validate_match_records(records: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Return deterministic integrity findings; ambiguous fixture collisions are warnings."""
    rows = list(records)
    issues: list[dict[str, Any]] = []
    seen_ids: dict[str, int] = {}
    candidates: list[tuple[int, str, str, str, str, datetime]] = []

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

        home, away = _team_key(row, "home"), _team_key(row, "away")
        if not home or not away:
            issues.append(_issue("missing_team", "error", match_id, "Home or away team identity is missing"))
        elif home == away:
            issues.append(_issue("same_home_away", "error", match_id, "Home and away resolve to the same team"))

        competition_id = _text(row.get("competitionId"))
        competition_type = _text(row.get("competitionType"))
        season = _text(row.get("season"))
        if not competition_id:
            issues.append(_issue("missing_competition_id", "warning", match_id,
                                 "Stable competition ID is missing; competition name is not a safe substitute"))
        if not competition_type:
            issues.append(_issue("missing_competition_type", "warning", match_id,
                                 "League/cup/friendly classification is missing"))
        if not season:
            issues.append(_issue("missing_season", "warning", match_id, "Competition season is missing"))

        kickoff = _utc_datetime(row.get("utcDate"))
        if kickoff is None:
            code = "missing_kickoff" if not row.get("utcDate") and not row.get("localKickoff") else "invalid_utc_kickoff"
            issues.append(_issue(code, "warning", match_id,
                                 "A timezone-aware UTC kickoff is required for model-ready records"))

        status = row.get("status")
        if status not in VALID_STATUSES:
            issues.append(_issue("unknown_status", "warning", match_id,
                                 "Match status is missing or outside the recognized status set"))

        home_score, away_score = row.get("homeScore"), row.get("awayScore")
        for field, score in (("homeScore", home_score), ("awayScore", away_score)):
            if score is not None and (isinstance(score, bool) or not isinstance(score, int) or score < 0):
                issues.append(_issue("invalid_score", "error", match_id,
                                     f"{field} must be a non-negative integer or null"))

        if status == "FINISHED" and (home_score is None or away_score is None):
            issues.append(_issue("finished_without_score", "warning", match_id,
                                 "Finished match has no complete final score"))
        if status in {"SCHEDULED", "TIMED", "POSTPONED", "CANCELLED"} and (
            home_score is not None or away_score is not None
        ):
            issues.append(_issue("score_on_nonfinished_match", "warning", match_id,
                                 "A non-finished match contains a score; verify whether it is a live or final score"))

        stats = row.get("statistics") or {}
        hxg, axg = stats.get("homeXg"), stats.get("awayXg")
        for field, value in (("homeXg", hxg), ("awayXg", axg)):
            if value is not None and (
                isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value) or not 0 <= value <= 20
            ):
                issues.append(_issue("invalid_xg", "error", match_id,
                                     f"{field} must be finite and between 0 and 20, or null"))
        if (hxg is None) != (axg is None):
            issues.append(_issue("partial_xg_pair", "warning", match_id,
                                 "Only one team's xG is present; retain source values and exclude from paired-xG analysis"))
        if bool(stats.get("xgAvailable")) != (hxg is not None and axg is not None):
            issues.append(_issue("xg_flag_mismatch", "warning", match_id,
                                 "xgAvailable does not agree with the nullable xG pair"))

        if home and away and competition_id and competition_type and kickoff:
            candidates.append((index, home, away, competition_id, competition_type, kickoff))

    # Compare only same ordered teams + competition ID/type. A five-minute window
    # catches minor timestamp formatting/drift without merging reverse fixtures.
    buckets: dict[tuple[str, str, str, str], list[tuple[int, datetime]]] = defaultdict(list)
    for index, home, away, comp_id, comp_type, kickoff in candidates:
        buckets[(home, away, comp_id, comp_type)].append((index, kickoff))
    for grouped in buckets.values():
        grouped.sort(key=lambda item: item[1])
        for pos, (index, kickoff) in enumerate(grouped):
            for prior_index, prior_kickoff in reversed(grouped[:pos]):
                delta = (kickoff - prior_kickoff).total_seconds()
                if delta > DUPLICATE_KICKOFF_TOLERANCE_SECONDS:
                    break
                row = rows[index]
                match_id = str(row.get("id")) if row.get("id") is not None else None
                issues.append(_issue("possible_duplicate_fixture", "warning", match_id,
                                     f"Same team identities, competition ID/type and kickoff within five minutes of record index {prior_index}"))

    errors = sum(item["severity"] == "error" for item in issues)
    warnings = sum(item["severity"] == "warning" for item in issues)
    return {"recordCount": len(rows), "errorCount": errors, "warningCount": warnings,
            "valid": errors == 0, "issues": issues}
