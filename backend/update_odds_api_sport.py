"""API-Sport.ru odds ingestion. Fixture/model source remains PitchAPI."""
import json, os, re, unicodedata
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/"docs"/"data.json"
URL="https://api.api-sport.ru/v2/football/matches"
BOOKS=("melbet","pari","marathon","betboom","pinnacle")

def norm(v):
    s=unicodedata.normalize("NFKD",str(v or "").lower())
    s="".join(c for c in s if not unicodedata.combining(c))
    return " ".join(re.sub(r"[^a-z0-9]+"," ",s).split())

def sim(a,b):
    a,b=norm(a),norm(b)
    if not a or not b:return 0
    if a==b:return 1
    seq=SequenceMatcher(None,a,b).ratio()
    aa,bb=set(a.split()),set(b.split())
    return .72*seq+.28*(2*len(aa&bb)/max(1,len(aa)+len(bb)))

def kickoff(m):
    stamp=m.get("startTimestamp")
    if isinstance(stamp,(int,float)): return datetime.fromtimestamp(stamp/1000,tz=timezone.utc)
    raw=m.get("utcDate") or m.get("dateEvent")
    try:
        d=datetime.fromisoformat(str(raw).replace("Z","+00:00"))
        return d.replace(tzinfo=timezone.utc) if d.tzinfo is None else d.astimezone(timezone.utc)
    except (ValueError,TypeError): return None

def match_fixture(target,candidates):
    t=kickoff(target)
    if not t:return None
    ranked=[]
    for m in candidates:
        mt=kickoff(m)
        if not mt or abs((mt-t).total_seconds())>18*3600:continue
        h=sim(target.get("home"),(m.get("homeTeam") or {}).get("name"))
        a=sim(target.get("away"),(m.get("awayTeam") or {}).get("name"))
        if h>=.84 and a>=.84:ranked.append((h+a,m))
    ranked.sort(key=lambda x:x[0],reverse=True)
    if not ranked or (len(ranked)>1 and ranked[0][0]-ranked[1][0]<.1):return None
    return ranked[0][1]

def market_kind(slug,market):
    slug=slug.lower()
    name=(market.get("name") or {})
    name=(name.get("en") or name.get("ru") or "").lower() if isinstance(name,dict) else str(name).lower()
    if "double_chance" in slug or "double chance" in name:return "dc"
    if "handicap" in slug or "гандикап" in name or "фора" in name:return "spread"
    if "total_team" in slug or "team_total" in slug or "тотал команды" in name:return "tt"
    return None

def lines(stake):
    for x in stake.get("lines") or []:
        if isinstance(x.get("factor"),(int,float)) and x["factor"]>1:yield x
    if isinstance(stake.get("factor"),(int,float)) and stake["factor"]>1:
        yield {"factor":stake["factor"],"argument":None}

def parse_odds(m):
    best={"doubleChance":{},"spreads":[],"teamTotals":[]}
    books_best={"doubleChance":{},"spreads":[],"teamTotals":[]}
    book_rows=[]
    for bk,board in (m.get("oddsBk") or {}).items():
        if not isinstance(board,dict):continue
        row={"key":bk,"title":bk.upper(),"doubleChance":{},"spreads":[],"teamTotals":[]}
        for slug,market in (board.get("markets") or {}).items():
            if not isinstance(market,dict) or market.get("status")!="open":continue
            kind=market_kind(slug,market)
            if not kind:continue
            for outcome,stake in (market.get("stakes") or {}).items():
                if not isinstance(stake,dict) or stake.get("suspended"):continue
                for line in lines(stake):
                    price=float(line["factor"]); arg=line.get("argument"); key=outcome.lower()
                    if kind=="dc":
                        key={"home_or_draw":"1x","draw_or_away":"x2","home_or_away":"12"}.get(key,key)
                        if key not in ("1x","x2","12"):continue
                        if price>best["doubleChance"].get(key,0):
                            best["doubleChance"][key]=price; books_best["doubleChance"][key]=bk.upper()
                        row["doubleChance"][key]=price
                    elif kind=="spread":
                        side="home" if key in ("handicap_1","w1","home","1") or slug.endswith("_1") else ("away" if key in ("handicap_2","w2","away","2") or slug.endswith("_2") else None)
                        if side is None or arg is None or abs(float(arg))>.001:continue
                        q={"team":side,"point":0.0,"price":price}
                        old=next((x for x in best["spreads"] if x["team"]==side),None)
                        if old is None or price>old["price"]:
                            if old:best["spreads"].remove(old)
                            best["spreads"].append(q)
                            books_best["spreads"]=[x for x in books_best["spreads"] if x["team"]!=side]+[{"team":side,"point":0.0,"bookmaker":bk.upper()}]
                        row["spreads"].append(q)
                    else:
                        side="home" if slug.endswith("_1") else ("away" if slug.endswith("_2") else None)
                        if side is None or arg is None or key not in ("over","under"):continue
                        q={"team":side,"point":float(arg),"side":key,"price":price}
                        old=next((x for x in best["teamTotals"] if x["team"]==side and x["point"]==q["point"] and x["side"]==key),None)
                        if old is None or price>old["price"]:
                            if old:best["teamTotals"].remove(old)
                            best["teamTotals"].append(q)
                            books_best["teamTotals"]=[x for x in books_best["teamTotals"] if not (x["team"]==side and x["point"]==q["point"] and x["side"]==key)]+[{**{k:q[k] for k in ("team","point","side")},"bookmaker":bk.upper()}]
                        row["teamTotals"].append(q)
        if any(row[k] for k in ("doubleChance","spreads","teamTotals")):book_rows.append(row)
    best={k:v for k,v in best.items() if v}
    books_best={k:v for k,v in books_best.items() if v}
    return {"eventId":str(m.get("id") or ""),"updatedAt":datetime.now(timezone.utc).isoformat(),"bookmakers":book_rows,"best":best,"bestBookmakers":books_best}

def main():
    key=os.getenv("API_SPORT_KEY","").strip()
    if not key:raise RuntimeError("API_SPORT_KEY missing from GitHub Secrets")
    data=json.loads(DATA.read_text(encoding="utf-8"))
    today=datetime.now(timezone.utc).date()
    params={"date_from":today.isoformat(),"date_to":(today+timedelta(days=14)).isoformat(),"exclude_amateur":"true","view":"odds","with_bk_odds":"true","odds_stage":"all","bookmaker_ids":",".join(BOOKS),"page_size":500,"sort":"asc"}
    session=requests.Session();session.headers.update({"Authorization":key})
    resp=session.get(URL,params=params,timeout=60);resp.raise_for_status()
    payload=resp.json();fixtures=payload if isinstance(payload,list) else payload.get("matches") or []
    eligible=matched=updated=retained=0
    for comp in data.get("competitions",[]):
        for match in comp.get("matches",[]):
            if match.get("status") not in ("SCHEDULED","TIMED","IN_PLAY","PAUSED"):continue
            eligible+=1;source=match_fixture(match,fixtures)
            if not source:
                if match.get("odds"):retained+=1
                continue
            matched+=1;quote=parse_odds(source)
            if quote["best"]:match["odds"]=quote;updated+=1
            elif match.get("odds"):retained+=1
    data["oddsProvider"]="API-Sport"
    data["oddsUpdatedAt"]=datetime.now(timezone.utc).isoformat()
    data["oddsCoverage"]={"eligible":eligible,"matched":matched,"updated":updated,"retained":retained,"notMatched":eligible-matched}
    data["oddsStatus"]="ready" if updated or retained else "no_quotes"
    data["oddsNotice"]=f"API-Sport: обновлены коэффициенты {updated} матчей; сохранены прежние {retained}." if updated or retained else "API-Sport не вернул подходящие котировки."
    DATA.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"provider":"API-Sport","fixtures":len(fixtures),"eligible":eligible,"matched":matched,"updated":updated,"retained":retained}))

if __name__=="__main__":main()
