"""Read-only CLI for Soccer365 historical coverage reports.

Usage:
  python backend/report_soccer365_coverage.py --catalog catalog.json --records archive.jsonl

The command never fetches pages and never modifies either input file.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from soccer365_coverage import summarize_historical_coverage
from soccer365_store import read_jsonl


def build_report(catalog_path: str | Path, records_path: str | Path) -> dict:
    with Path(catalog_path).open("r", encoding="utf-8") as stream:
        catalog = json.load(stream)
    if not isinstance(catalog, list):
        raise ValueError("Catalog JSON must contain a list of competition entries")
    records = read_jsonl(records_path)
    return summarize_historical_coverage(catalog, records)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, help="Explicit verified Soccer365 catalog JSON")
    parser.add_argument("--records", required=True, help="Append-only Soccer365 JSONL archive")
    args = parser.parse_args(argv)
    try:
        report = build_report(args.catalog, args.records)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report.get("valid") else 1


if __name__ == "__main__":
    raise SystemExit(main())
