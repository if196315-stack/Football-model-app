"""Read-only A-League Men upcoming MelBet quote coverage audit.
Updated trigger marker: run standalone coverage audit.
Uses PitchAPI fixtures + API-Sport odds feed; writes only a standalone artifact.
No app feeds, Android files, betting actions or non-MelBet prices are touched.
"""
import json, os, sys, time
from datetime import datetime, timedelta, timezone
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from update_odds_api_sport import URL, match_fixture, parse_odds

PITCH="https://api.pitchapi.dev/v1"
LEAGUE_ID="l_4MPd7x"
SEASON="2026/2027"

def pitch_get(path,key,params=None):
    r=requests.get(PITCH+path,params=params or {},headers={"X-API-KEY":key},timeout=45)
    r.raise_for_status()
    body=r.json()
    if not isinstance(body,dict) or "data" not in body or "error" in body:
        raise RuntimeError("Unexpected PitchAPI response envelope")
    return body["data"]

def fetch_odds(api_key,start,end):
    params={"date_from":start.isoformat(),"date_to":end.isoformat(),
      "exclude_amateur":"true","view":"odds","with_bk_odds":"true",
      "odds_stage":"all","bookmaker_ids":"melbet","page_size":500,"sort":"asc","page":1}
    all_rows=[]
    cursor=start
    while cursor<=end:
        stop=min(cursor+timedelta(days=30),end)
        q={**params,"date_from":cursor.isoformat(),"date_to":stop.isoformat()}
        r=requests.get(URL,params=q,headers={"Authorization":api_key},timeout=60)
        r.raise_for_status(); payload=r.json()
        all_rows.extend(payload if isinstance(payload,list) else payload.get("matches") or [])
        pages=min(int(payload.get("totalPages") or 1),100) if isinstance(payload,dict) else 1
        for page in range(2,pages+1):
            rr=requests.get(URL,params={**q,"page":page},headers={"Authorization":api_key},timeout=60)
            rr.raise_for_status(); pp=rr.json()
            all_rows.extend(pp if isinstance(pp,list) else pp.get("matches") or [])
        cursor=stop+timedelta(days=1)
    unique={}
    for row in all_rows:
        if row.get("id") is not None: unique[str(row["id"])]=row
    return list(unique.values())

def main():
    pk=os.environ.get("PITCHAPI_API_KEY","").strip()
    ak=os.environ.get("API_SPORT_KEY","").strip()
    if not pk or not ak: raise SystemExit("Required research secrets are not configured")
    now=datetime.now(timezone.utc)
    data=pitch_get(f"/leagues/{LEAGUE_ID}/matches",pk,{"season":SEASON,"status":"all"})
    fixtures=data.get("matches",[]) if isinstance(data,dict) else []
    upcoming=[]
    for m in fixtures:
        if not isinstance(m,dict) or not m.get("id"): continue
        status=str(m.get("status") or "").casefold()
        if status in {"finished","played","complete","completed","cancelled","postponed","awarded"}: continue
        h=m.get("home_team") or {}; a=m.get("away_team") or {}
        raw=m.get("date") or m.get("kickoff") or m.get("utc_date")
        if not raw: continue
        try:
            dt=datetime.fromisoformat(str(raw).replace("Z","+00:00"))
            if dt.tzinfo is None: dt=dt.replace(tzinfo=timezone.utc)
            else: dt=dt.astimezone(timezone.utc)
        except ValueError: continue
        if dt < now-timedelta(hours=2): continue
        upcoming.append({"id":str(m["id"]),"date":dt.isoformat(),
          "target":{"utcDate":str(raw).strip() if len(str(raw).strip())==10 else dt.isoformat(),"home":h.get("name"),"away":a.get("name")}})
    odds=fetch_odds(ak,now.date(),(now+timedelta(days=180)).date())
    rows=[]; matched=0; boards=0; active=0
    for f in upcoming:
        found=match_fixture(f["target"],odds)
        if not found:
            rows.append({"pitchapi_match_id":f["id"],"date":f["date"],
              "home":f["target"]["home"],"away":f["target"]["away"],
              "provider_fixture_matched":False})
            continue
        matched+=1
        parsed=parse_odds(found)
        board=(found.get("oddsBk") or {}).get("melbet")
        if isinstance(board,dict): boards+=1
        if parsed.get("prematchActive"): active+=1
        best=parsed.get("best") or {}
        tt=[x for x in best.get("teamTotals",[]) if abs(float(x.get("point",99))-1.0)<.001 and x.get("side")=="over"]
        spreads=[x for x in best.get("spreads",[]) if float(x.get("point",99)) in (0.0,1.0)]
        totals=[x for x in best.get("totals",[]) if abs(float(x.get("point",99))-2.5)<.001 and x.get("side")=="over"]
        btts=best.get("btts") or {}
        rows.append({"pitchapi_match_id":f["id"],"date":f["date"],
          "home":f["target"]["home"],"away":f["target"]["away"],
          "provider_fixture_matched":True,"provider_fixture_id":str(found.get("id")),
          "melbet_board_present":isinstance(board,dict),
          "prematch_active":parsed.get("prematchActive"),
          "freshness":parsed.get("freshnessStatus"),
          "board_updated_at":parsed.get("bookmakerUpdatedAt"),
          "team_total_over_1_0":tt,"asian_handicap_0_or_plus1":spreads,
          "match_over_2_5":totals,"btts_yes":btts.get("yes")})
    report={"generated_at":now.isoformat(),"competition":"A-League Men",
      "season":SEASON,"odds_provider":"API-Sport","bookmaker_only":"MelBet",
      "scope":"upcoming fixtures; read-only; no betting or app feed writes",
      "pitchapi_upcoming_fixtures":len(upcoming),"api_sport_fixtures_in_180d":len(odds),
      "matched_fixtures":matched,"fixtures_with_melbet_board":boards,
      "fixtures_with_active_prematch_quotes":active,"fixtures":rows}
    Path("australia-melbet-coverage.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="fixtures"},ensure_ascii=False,indent=2))
    for market, predicate in [
      ("ITB home/away over 1.0",lambda r: any(x.get("team")=="home" for x in r.get("team_total_over_1_0",[])) and any(x.get("team")=="away" for x in r.get("team_total_over_1_0",[]))),
      ("AH 0",lambda r:any(x.get("point")==0.0 for x in r.get("asian_handicap_0_or_plus1",[]))),
      ("AH +1",lambda r:any(x.get("point")==1.0 for x in r.get("asian_handicap_0_or_plus1",[]))),
      ("Over 2.5",lambda r:bool(r.get("match_over_2_5"))),
      ("BTTS Yes",lambda r:bool(r.get("btts_yes")))]:
      print(f"{market}: {sum(1 for r in rows if predicate(r))}/{len(upcoming)}")
if __name__=="__main__": main()
