"""Append-only JSONL store for normalized Soccer365 records.

This is an isolated historical-store prototype. It never overwrites a match ID:
changed source payloads are reported as conflicts for review, not silently merged.
"""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any, Iterable

from soccer365_quality import validate_match_records


class StoreFormatError(ValueError):
    """Raised when an existing JSONL store cannot be trusted."""


def _canonical(record: dict[str, Any]) -> str:
    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    """Read an append-only store; malformed lines fail closed with a line number."""
    source = Path(path)
    if not source.exists():
        return []
    records: list[dict[str, Any]] = []
    with source.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise StoreFormatError(f"Invalid JSON on line {line_number}") from exc
            if not isinstance(value, dict):
                raise StoreFormatError(f"Expected an object on line {line_number}")
            records.append(value)
    ids = [str(row.get("id") or "").strip() for row in records]
    if any(not value for value in ids) or len(ids) != len(set(ids)):
        raise StoreFormatError("Existing store has missing or duplicate match IDs")
    return records


def plan_append(
    existing: Iterable[dict[str, Any]],
    incoming: Iterable[dict[str, Any]],
) -> dict[str, Any]:
    """Plan additions without mutating inputs or replacing any historical record.

    Warnings (e.g. missing xG, season or timezone) do not erase source evidence.
    Records with integrity errors are rejected. Same-ID identical records are
    idempotent; same-ID changed records are conflicts and require manual review.
    """
    old_rows = list(existing)
    new_rows = list(incoming)
    old_by_id: dict[str, dict[str, Any]] = {}
    for row in old_rows:
        match_id = str(row.get("id") or "").strip()
        if not match_id or match_id in old_by_id:
            raise StoreFormatError("Existing records must have unique non-empty IDs")
        old_by_id[match_id] = row

    additions: list[dict[str, Any]] = []
    unchanged: list[str] = []
    conflicts: list[str] = []
    rejected: list[dict[str, Any]] = []
    pending: dict[str, dict[str, Any]] = {}

    for row in new_rows:
        if not isinstance(row, dict):
            rejected.append({"id": None, "reason": "record_not_object"})
            continue
        match_id = str(row.get("id") or "").strip()
        if not match_id:
            rejected.append({"id": None, "reason": "missing_match_id"})
            continue
        validation = validate_match_records([row])
        if validation["errorCount"]:
            rejected.append({
                "id": match_id,
                "reason": "integrity_error",
                "issues": [item for item in validation["issues"] if item["severity"] == "error"],
            })
            continue

        prior = old_by_id.get(match_id, pending.get(match_id))
        if prior is not None:
            if _canonical(prior) == _canonical(row):
                unchanged.append(match_id)
            else:
                conflicts.append(match_id)
            continue
        pending[match_id] = row
        additions.append(row)

    return {
        "records": old_rows + additions,
        "appended": [str(row["id"]) for row in additions],
        "unchanged": unchanged,
        "conflicts": conflicts,
        "rejected": rejected,
    }


def append_jsonl(path: str | Path, incoming: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Atomically append new IDs while preserving all existing JSONL records."""
    target = Path(path)
    existing = read_jsonl(target)
    plan = plan_append(existing, incoming)
    if plan["appended"]:
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp",
                                                dir=str(target.parent))
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                for row in plan["records"]:
                    stream.write(_canonical(row) + "\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_name, target)
        except Exception:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise
    return {key: value for key, value in plan.items() if key != "records"}
