"""Historical coverage reporting against an explicit Soccer365 manifest."""
from __future__ import annotations

from collections import defaultdict
import math
from typing import Any, Iterable

from soccer365_catalog import validate_competition_catalog


def summarize_historical_coverage(
    entries: Iterable[dict[str, Any]],
    records: Iterable[dict[str, Any]],
    *,
    min_finished_matches: int = 100,
    min_paired_xg_matches: int = 30,
) -> dict[str, Any]:
    """Report country/competition/season coverage without guessing missing history.

    An entry may include expectedFinishedMatches only when that count was
    independently verified from the source. Without it, historical completeness
    is explicitly unknown, even when model sample thresholds are met.
    """
    manifest = list(entries)
    rows = list(records)
    validation = validate_competition_catalog(manifest)
    if min_finished_matches < 1 or min_paired_xg_matches < 1:
        raise ValueError("Coverage thresholds must be positive")
    if not validation["valid"]:
        return {
            "valid": False,
            "manifestValidation": validation,
            "source": "Soccer365",
            "competitions": [],
        }

    groups: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    for entry in manifest:
        key = (
            entry["country"].strip(),
            entry["competitionId"].strip(),
            entry["competitionType"],
            entry["season"].strip(),
        )
        groups[key] = {
            "country": key[0],
            "competitionId": key[1],
            "competitionName": entry["competitionName"],
            "competitionType": key[2],
            "season": key[3],
            "expectedFinishedMatches": entry.get("expectedFinishedMatches"),
            "totalRecords": 0,
            "finishedMatches": 0,
            "finishedWithScore": 0,
            "finishedWithPairedXg": 0,
            "finishedMissingXg": 0,
            "missingUtcKickoff": 0,
            "missingTeamIds": 0,
            "duplicateIds": 0,
            "missingMatchIds": 0,
            "_ids": set(),
            "_teams": defaultdict(lambda: {"pairedXgMatches": 0, "finishedMatches": 0}),
        }

    unmatched_records = 0
    for row in rows:
        key = (
            str(row.get("country") or "").strip(),
            str(row.get("competitionId") or "").strip(),
            row.get("competitionType"),
            str(row.get("season") or "").strip(),
        )
        group = groups.get(key)
        if group is None:
            unmatched_records += 1
            continue
        raw_id = row.get("id")
        if raw_id is None or not str(raw_id).strip():
            group["missingMatchIds"] += 1
        if raw_id is not None and str(raw_id).strip():
            match_id = str(raw_id).strip()
            if match_id in group["_ids"]:
                group["duplicateIds"] += 1
                continue
            group["_ids"].add(match_id)
        group["totalRecords"] += 1
        if not row.get("utcDate"):
            group["missingUtcKickoff"] += 1
        if not row.get("homeTeamId") or not row.get("awayTeamId"):
            group["missingTeamIds"] += 1
        if row.get("status") != "FINISHED":
            continue
        group["finishedMatches"] += 1
        hs, aws = row.get("homeScore"), row.get("awayScore")
        has_score = (
            isinstance(hs, int) and not isinstance(hs, bool) and hs >= 0
            and isinstance(aws, int) and not isinstance(aws, bool) and aws >= 0
        )
        if has_score:
            group["finishedWithScore"] += 1
        stats = row.get("statistics") or {}
        hxg, axg = stats.get("homeXg"), stats.get("awayXg")
        has_xg = all(
            isinstance(v, (int, float)) and not isinstance(v, bool)
            and math.isfinite(v) and 0 <= v <= 20
            for v in (hxg, axg)
        )
        if has_xg:
            group["finishedWithPairedXg"] += 1
        else:
            group["finishedMissingXg"] += 1
        for side in ("home", "away"):
            team_id = row.get(f"{side}TeamId")
            team_name = row.get(side)
            team_key = str(team_id).strip() if team_id else str(team_name or "").strip().casefold()
            if team_key:
                group["_teams"][team_key]["finishedMatches"] += 1
                if has_xg:
                    group["_teams"][team_key]["pairedXgMatches"] += 1

    output = []
    for key in sorted(groups):
        group = groups[key]
        expected = group["expectedFinishedMatches"]
        known_expected = isinstance(expected, int) and not isinstance(expected, bool) and expected >= 0
        source_complete = (
            known_expected
            and group["finishedMatches"] == expected
            and group["duplicateIds"] == 0
            and group["missingMatchIds"] == 0
        )
        teams = [
            {
                "team": team_key,
                "finishedMatches": value["finishedMatches"],
                "pairedXgMatches": value["pairedXgMatches"],
                "xgSampleReady": value["pairedXgMatches"] >= min_paired_xg_matches,
            }
            for team_key, value in sorted(group["_teams"].items())
        ]
        output.append({
            **{k: v for k, v in group.items() if not k.startswith("_")},
            "expectedCountVerified": known_expected,
            "sourceHistoryComplete": source_complete if known_expected else None,
            "finishedCountDelta": group["finishedMatches"] - expected if known_expected else None,
            # Coverage against the independently verified season total.
            "finishedResultCoverage": (
                group["finishedWithScore"] / expected if known_expected and expected else None
            ),
            # Data quality among records already marked FINISHED.
            "finishedScoreRate": (
                group["finishedWithScore"] / group["finishedMatches"]
                if group["finishedMatches"] else None
            ),
            "baselineReady": group["finishedWithScore"] >= min_finished_matches,
            "teams": teams,
        })

    return {
        "valid": True,
        "source": "Soccer365",
        "thresholds": {
            "finishedMatchesWithScore": min_finished_matches,
            "pairedXgMatchesPerTeam": min_paired_xg_matches,
        },
        "unmatchedRecordCount": unmatched_records,
        "competitions": output,
    }
