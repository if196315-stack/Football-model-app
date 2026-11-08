#!/usr/bin/env python3
"""Build a deterministic, offline PitchAPI xG backtest CSV from the persisted cache."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path

from pitchapi_data_source import load_cache, model_history_rows

EXCLUDED_SEASONS = {
    ("ESP", "2023/24"), ("ESP", "2023/2024"),
    ("GER", "2024/25"), ("GER", "2024/2025"),
    ("ITA", "2022/23"), ("ITA", "2022/2023"),
}
EXCLUDED_MATCH_IDS = {"m_3ir3kE"}
COLUMNS = [
    "match_id", "date", "league_code", "season", "home_team", "away_team",
    "home_goals", "away_goals", "home_xg", "away_xg",
]


def export(input_cache: Path, output_csv: Path, manifest_path: Path) -> dict:
    cache = load_cache(input_cache)
    candidates = model_history_rows(cache)
    rows = []
    for match in candidates:
        if match.get("id") in EXCLUDED_MATCH_IDS:
            continue
        if (match.get("leagueCode"), match.get("season")) in EXCLUDED_SEASONS:
            continue
        if match.get("hasPlayoff") is True:
            continue
        if any(match.get(field) is None for field in (
            "homeScore", "awayScore", "homeXg", "awayXg",
            "home", "away", "date", "leagueCode", "season", "id",
        )):
            continue
        rows.append({
            "match_id": str(match["id"]),
            "date": str(match["date"])[:10],
            "league_code": str(match["leagueCode"]),
            "season": str(match["season"]),
            "home_team": str(match["home"]),
            "away_team": str(match["away"]),
            "home_goals": int(match["homeScore"]),
            "away_goals": int(match["awayScore"]),
            "home_xg": float(match["homeXg"]),
            "away_xg": float(match["awayXg"]),
        })
    rows.sort(key=lambda row: (row["date"], row["league_code"], row["match_id"]))
    if not rows:
        raise SystemExit("No eligible paired-xG matches found in PitchAPI cache")
    output_csv.parent.mkdir(parents=True, exist_ok=True)
    with output_csv.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    digest = hashlib.sha256(output_csv.read_bytes()).hexdigest()
    manifest = {
        "provider": "PitchAPI",
        "input_cache": str(input_cache),
        "output_csv": str(output_csv),
        "sha256": digest,
        "rows": len(rows),
        "unique_match_ids": len({row["match_id"] for row in rows}),
        "paired_xg_rows": len(rows),
        "excluded_seasons": [list(item) for item in sorted(EXCLUDED_SEASONS)],
        "excluded_match_ids": sorted(EXCLUDED_MATCH_IDS),
        "xg_policy": "Paired non-null shot-level PitchAPI xG only; missing values are never imputed.",
    }
    if manifest["unique_match_ids"] != len(rows):
        raise ValueError("Duplicate match IDs in exported backtest cohort")
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default="backend/cache/pitchapi_history.json")
    parser.add_argument("--output", default="artifacts/pitchapi-clean-candidate.csv")
    parser.add_argument("--manifest", default="artifacts/pitchapi-clean-candidate-manifest.json")
    args = parser.parse_args()
    export(Path(args.cache), Path(args.output), Path(args.manifest))


if __name__ == "__main__":
    main()
