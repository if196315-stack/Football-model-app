"""Read-only extraction of PitchAPI date/timeUtc from the restored Actions cache.

No API requests or third-party Python packages are required.
"""
import ast
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CACHE_PATH=ROOT/"backend"/"cache"/"pitchapi_history.json"

def load_targets():
    source=ROOT/"backend"/"diagnose_api_sport_fixture_timestamps.py"
    tree=ast.parse(source.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node,ast.Assign) and any(
            isinstance(target,ast.Name) and target.id=="TARGETS" for target in node.targets
        ):
            return ast.literal_eval(node.value)
    raise RuntimeError("TARGETS not found in timestamp audit source")

def main():
    if not CACHE_PATH.exists():
        print("PITCHAPI_CACHE_STATUS=missing")
        print("No PitchAPI cache was restored; no API request was made.")
        return
    cache=json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    matches=cache.get("matches",{})
    output=[]
    for comp,pitch_date,home,away,tournament_id,home_id,away_id in load_targets():
        candidates=[
            row for row in matches.values()
            if row.get("leagueCode")=={"SA":"ITA","BL1":"GER"}[comp]
            and row.get("home")==home and row.get("away")==away
            and row.get("season")=="2026/2027"
            and row.get("status")=="not_started"
        ]
        output.append({
            "competition":comp,"expectedDate":pitch_date,"home":home,"away":away,
            "candidateCount":len(candidates),
            "pitchApiRows":[{
                "id":row.get("id"),"date":row.get("date"),"timeUtc":row.get("timeUtc"),
                "status":row.get("status"),"season":row.get("season"),
                "leagueId":row.get("leagueId"),"leagueName":row.get("leagueName")
            } for row in candidates]
        })
    summary={
        "targets":len(output),
        "targetsWithOneCacheRow":sum(x["candidateCount"]==1 for x in output),
        "targetsWithNoCacheRow":sum(x["candidateCount"]==0 for x in output),
        "targetsWithMultipleCacheRows":sum(x["candidateCount"]>1 for x in output),
        "cacheUpdatedAt":cache.get("updatedAt"),
        "cacheLastRefresh":cache.get("lastRefresh"),
        "cacheProvider":cache.get("provider"),
        "apiRequests":0,"readOnly":True
    }
    print("PITCHAPI_RAW_TIME_ROWS="+json.dumps(output,ensure_ascii=False))
    print("PITCHAPI_RAW_TIME_SUMMARY="+json.dumps(summary,ensure_ascii=False))

if __name__=="__main__":
    main()
