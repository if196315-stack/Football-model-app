"""Read-only, bounded xG coverage probe for UEFA fixture cache."""
from __future__ import annotations
import json, os, random, time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "backend/cache/pitchapi_uefa_fixtures.json"
REPORT = ROOT / "pitchapi-uefa-xg-diagnostic.json"
BASE = "https://api.pitchapi.dev/v1"

def shot_rows(data):
    if not isinstance(data, dict): return []
    if isinstance(data.get("shots"), list):
        return [x for x in data["shots"] if isinstance(x, dict)]
    periods = data.get("periods")
    if not isinstance(periods, list): return []
    return [s for p in periods if isinstance(p, dict)
            for s in (p.get("shots") or []) if isinstance(s, dict)]

def summarize_shots(data, home_id, away_id):
    totals = {home_id: 0.0, away_id: 0.0}
    counts = {home_id: 0, away_id: 0}
    valid = 0
    for shot in shot_rows(data):
        team = shot.get("team_id")
        value = shot.get("expected_goals")
        if team not in totals or isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1:
            continue
        totals[team] += float(value)
        counts[team] += 1
        valid += 1
    return {
        "validShots": valid,
        "homeShotCount": counts[home_id], "awayShotCount": counts[away_id],
        "homeXg": round(totals[home_id], 4) if counts[home_id] else None,
        "awayXg": round(totals[away_id], 4) if counts[away_id] else None,
        "pairedXg": bool(counts[home_id] and counts[away_id]),
    }

def select_sample(matches, per_group=2, limit=24):
    groups = defaultdict(list)
    for row in matches:
        if row.get("status") == "finished" and row.get("homeTeamId") is not None and row.get("awayTeamId") is not None:
            groups[(row.get("leagueCode"), row.get("season"))].append(row)
    chosen = []
    for key in sorted(groups):
        rows = sorted(groups[key], key=lambda r: (str(r.get("date") or ""), r["id"]))
        if len(rows) <= per_group: chosen.extend(rows)
        else:
            for i in range(per_group):
                chosen.append(rows[round(i * (len(rows)-1) / max(1, per_group-1))])
    return chosen[:limit]

def run(api_key, session=requests, cache_path=CACHE, report_path=REPORT, limit=24, pause=0.15):
    if not api_key: raise ValueError("PITCHAPI_API_KEY is not configured")
    cache = json.loads(Path(cache_path).read_text(encoding="utf-8"))
    rows = list(cache.get("matches", {}).values())
    sample = select_sample(rows, limit=limit)
    results, errors = [], []
    for row in sample:
        item = {k: row.get(k) for k in ("id","leagueCode","season","date","home","away","homeTeamId","awayTeamId")}
        try:
            response = session.get(f"{BASE}/matches/{row['id']}/shots",
                headers={"X-API-KEY": api_key}, timeout=30)
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "data" not in body or "error" in body:
                raise ValueError("Unexpected PitchAPI shots response")
            item.update(summarize_shots(body["data"], row["homeTeamId"], row["awayTeamId"]))
            item["requestStatus"] = "ok"
        except Exception as exc:
            item.update({"requestStatus":"error", "errorType":type(exc).__name__, "error":str(exc)[:180]})
            errors.append(item["id"])
        results.append(item)
        if pause: time.sleep(pause)
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(), "provider":"PitchAPI",
        "scope":"Read-only sampled UEFA shot/xG endpoint diagnostic; no production cache or model writes",
        "fixtureCacheMatches":len(rows), "sampleLimit":limit, "sampledMatches":len(results),
        "successfulRequests":sum(x["requestStatus"]=="ok" for x in results),
        "requestErrors":len(errors),
        "matchesWithValidXg":sum(x.get("validShots",0)>0 for x in results),
        "matchesWithPairedXg":sum(bool(x.get("pairedXg")) for x in results),
        "matchesWithNoValidXg":sum(x["requestStatus"]=="ok" and x.get("validShots",0)==0 for x in results),
        "errors":errors, "results":results,
    }
    Path(report_path).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="results"},ensure_ascii=False,indent=2))
    if errors: raise RuntimeError(f"{len(errors)} UEFA shot requests failed; report saved")
    return report

if __name__ == "__main__":
    run(os.environ.get("PITCHAPI_API_KEY","").strip(),
        limit=int(os.environ.get("PITCHAPI_UEFA_XG_SAMPLE_LIMIT","24")))
