#!/usr/bin/env python3
"""Import the completed UEFA xG diagnostic and fixture artifact into a compact history cache."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def build_history(xg_report: dict, fixture_cache: dict) -> dict:
    xg_by_id = {
        str(row.get("id")): row
        for row in (xg_report.get("results") or [])
        if row.get("requestStatus") == "ok"
    }
    matches = []
    missing_paired_xg = 0
    missing_scores = 0
    for fixture in (fixture_cache.get("matches") or {}).values():
        if fixture.get("status") != "finished":
            continue
        match_id = str(fixture.get("id") or "")
        xg = xg_by_id.get(match_id)
        if not xg or not xg.get("pairedXg") or xg.get("homeXg") is None or xg.get("awayXg") is None:
            missing_paired_xg += 1
            continue
        home_score, away_score = fixture.get("homeScore"), fixture.get("awayScore")
        if not isinstance(home_score, int) or not isinstance(away_score, int):
            missing_scores += 1
            continue
        matches.append({
            "id": match_id,
            "leagueCode": fixture.get("leagueCode"),
            "season": fixture.get("season"),
            "date": str(fixture.get("date") or "")[:10],
            "home": fixture.get("home"),
            "away": fixture.get("away"),
            "homeTeamId": fixture.get("homeTeamId"),
            "awayTeamId": fixture.get("awayTeamId"),
            "homeScore": home_score,
            "awayScore": away_score,
            "homeXg": float(xg["homeXg"]),
            "awayXg": float(xg["awayXg"]),
            "pairedXg": True,
        })
    matches.sort(key=lambda row: (row["date"], row["leagueCode"], row["id"]))
    return {
        "schema_version": 1,
        "provider": "PitchAPI",
        "model_source": "shot-level expected_goals",
        "xg_report_generated_at": xg_report.get("generatedAt"),
        "fixture_cache_updated_at": fixture_cache.get("updatedAt"),
        "fixture_cache_matches": len(fixture_cache.get("matches") or {}),
        "finished_matches_with_paired_xg_and_scores": len(matches),
        "finished_matches_missing_paired_xg": missing_paired_xg,
        "finished_matches_missing_scores": missing_scores,
        "matches": matches,
    }


def write_atomic(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xg-report", required=True)
    parser.add_argument("--fixture-cache", required=True)
    parser.add_argument("--output", default="artifacts/uefa-xg-history.json")
    args = parser.parse_args()
    report = json.loads(Path(args.xg_report).read_text(encoding="utf-8"))
    fixtures = json.loads(Path(args.fixture_cache).read_text(encoding="utf-8"))
    history = build_history(report, fixtures)
    write_atomic(Path(args.output), history)
    print(json.dumps({key: value for key, value in history.items() if key != "matches"}, ensure_ascii=False))
    if not history["matches"]:
        raise RuntimeError("No paired xG fixtures with final scores were imported")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
