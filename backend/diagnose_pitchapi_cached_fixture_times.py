"""Read-only extraction of PitchAPI date/timeUtc from the restored Actions cache.

No API requests are made. The target list is shared with the existing 39-fixture
API-Sport timestamp audit.
"""
import json
from pathlib import Path

from diagnose_api_sport_fixture_timestamps import TARGETS
from pitchapi_data_source import CACHE_PATH

def main():
    if not CACHE_PATH.exists():
        print("PITCHAPI_CACHE_STATUS=missing")
        print("No PitchAPI cache was restored; no API request was made.")
        return
    cache=json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    matches=cache.get("matches",{})
    output=[]
    for comp,pitch_date,home,away,tournament_id,home_id,away_id in TARGETS:
        candidates=[
            row for row in matches.values()
            if row.get("leagueCode")=={"SA":"ITA","BL1":"GER"}[comp]
            and row.get("home")==home and row.get("away")==away
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
        "targets":len(TARGETS),
        "targetsWithOneCacheRow":sum(x["candidateCount"]==1 for x in output),
        "targetsWithNoCacheRow":sum(x["candidateCount"]==0 for x in output),
        "targetsWithMultipleCacheRows":sum(x["candidateCount"]>1 for x in output),
        "cacheUpdatedAt":cache.get("updatedAt"),
        "cacheLastRefresh":cache.get("lastRefresh"),
        "cacheProvider":cache.get("provider"),
        "apiRequests":0,
        "readOnly":True
    }
    print("PITCHAPI_RAW_TIME_ROWS="+json.dumps(output,ensure_ascii=False))
    print("PITCHAPI_RAW_TIME_SUMMARY="+json.dumps(summary,ensure_ascii=False))

if __name__=="__main__":
    main()
