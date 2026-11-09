"""API-Sport.ru odds ingestion using BetBoom exclusively. Fixture/model source remains PitchAPI."""
import json, os, re, unicodedata
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/"docs"/"data.json"
URL="https://api.api-sport.ru/v2/football/matches"
BOOKS=("betboom",)

def norm(v):
    s=unicodedata.normalize("NFKD",str(v or "").lower())
    s="".join(c for c in s if not unicodedata.combining(c))
    s=" ".join(re.sub(r"[^a-z0-9]+"," ",s).split())
    aliases={"man city":"manchester city","man utd":"manchester united",
             "man united":"manchester united","spurs":"tottenham hotspur"}
    return aliases.get(s,s)

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

def team_names(team):
    if not isinstance(team,dict):return []
    values=[team.get("name"),team.get("fullName")]
    translations=team.get("translations") or {}
    if isinstance(translations,dict):values.extend(translations.values())
    return [str(x) for x in values if x]

def team_similarity(target_name,team):
    names=team_names(team)
    return max((sim(target_name,name) for name in names),default=0.0)

def match_fixture(target,candidates):
    raw_target=target.get("utcDate") or target.get("dateEvent")
    t=kickoff(target)
    if not t:return None
    date_only=isinstance(raw_target,str) and len(raw_target.strip())==10 and "T" not in raw_target
    ranked=[]
    for m in candidates:
        mt=kickoff(m)
        if not mt:continue
        if date_only:
            # PitchAPI may provide only YYYY-MM-DD. Compare calendar dates,
            # not an artificial midnight timestamp against the real kickoff.
            if mt.date()!=t.date():continue
        elif abs((mt-t).total_seconds())>6*3600:continue
        h=team_similarity(target.get("home"),m.get("homeTeam"))
        a=team_similarity(target.get("away"),m.get("awayTeam"))
        # Provider and model may use short/long club names (e.g. Man City).
        if h>=.68 and a>=.68 and h+a>=1.48:ranked.append((h+a,m))
    ranked.sort(key=lambda x:x[0],reverse=True)
    if not ranked or (len(ranked)>1 and ranked[0][0]-ranked[1][0]<.08):return None
    return ranked[0][1]

def market_label(market):
    name = market.get("name") or {}
    return (name.get("en") or name.get("ru") or "") if isinstance(name, dict) else str(name)


def is_supported_full_time_market(slug, market):
    slug = slug.lower()
    name = market_label(market).lower()
    excluded = (
        "first_half", "firsthalf", "1st_half", "first half", "1st half",
        "second_half", "secondhalf", "2nd_half", "second half", "2nd half",
        "half_time", "half time", "halftime", "period",
        "corner", "corners", "card", "cards", "shot", "shots",
        "offside", "offsides", "player",
    )
    if any(token in slug or token in name for token in excluded):
        return False
    return True


def market_group(slug, market):
    name = market_label(market).lower()
    if name:
        normalized = re.sub(r"[^a-zа-я0-9]+", " ", name)
        normalized = re.sub(r"\b(over|under|больше|меньше)\b", " ", normalized)
        normalized = re.sub(r"\b\d+(?:[.,]\d+)?\b", " ", normalized)
        normalized = re.sub(r"\s+", " ", normalized).strip()
        if normalized:
            return normalized
    normalized_slug = re.sub(r"(^|[_-])(over|under)(?=[_-]|$)", "_", slug.lower())
    normalized_slug = re.sub(r"[_-]\d+[_-]\d+$", "", normalized_slug)
    return normalized_slug.strip("_-")


def market_kind(slug,market):
    slug=slug.lower()
    name=(market.get("name") or {})
    name=(name.get("en") or name.get("ru") or "").lower() if isinstance(name,dict) else str(name).lower()
    if "double_chance" in slug or "double chance" in name:return "dc"
    if ("both_teams" in slug or "btts" in slug or "both teams to score" in name
            or "обе команды забьют" in name or "обе забьют" in name):return "btts"
    if ("total_team" in slug or "team_total" in slug or "team total" in name
            or "тотал команды" in name or "индивидуальный тотал" in name):return "tt"
    if ("total" in slug or "тотал" in name) and not ("team" in slug or "команд" in name):return "total"
    if "handicap" in slug or "гандикап" in name or "фора" in name:return "spread"
    return None

def lines(stake):
    for x in stake.get("lines") or []:
        if not x.get("suspended") and isinstance(x.get("factor"),(int,float)) and x["factor"]>1:yield x
    if isinstance(stake.get("factor"),(int,float)) and stake["factor"]>1:
        yield {"factor":stake["factor"],"argument":None}

def parse_odds(m):
    best={"doubleChance":{},"spreads":[],"teamTotals":[],"totals":[],"btts":{}}
    books_best={"doubleChance":{},"spreads":[],"teamTotals":[],"totals":[],"btts":{}}
    book_rows=[]
    # BetBoom is the single reference bookmaker; never mix other prices.
    betboom=(m.get("oddsBk") or {}).get("betboom")
    if isinstance(betboom,dict):
        bk="betboom"; board=betboom
        row={"key":bk,"title":"BETBOOM","doubleChance":{},"spreads":[],"teamTotals":[],"totals":[],"btts":{}}
        for slug,market in (board.get("markets") or {}).items():
            if (not isinstance(market,dict) or market.get("status")!="open"
                    or market.get("stage")!="prematch"):continue
            if not is_supported_full_time_market(slug, market):continue
            kind=market_kind(slug,market)
            if not kind:continue
            market_name = market_label(market)
            market_key = market_group(slug, market)
            for outcome,stake in (market.get("stakes") or {}).items():
                if not isinstance(stake,dict) or stake.get("suspended"):continue
                for line in lines(stake):
                    price=float(line["factor"]); arg=line.get("argument"); key=outcome.lower()
                    if kind=="dc":
                        key={"home_or_draw":"1x","draw_or_away":"x2","home_or_away":"12"}.get(key,key)
                        if key not in ("1x","x2","12"):continue
                        best["doubleChance"][key]=max(price,best["doubleChance"].get(key,0))
                        books_best["doubleChance"][key]=bk.upper(); row["doubleChance"][key]=price
                    elif kind=="btts":
                        side = ("yes" if key in ("yes", "true", "1") or key.endswith("_yes") else
                                "no" if key in ("no", "false", "2") or key.endswith("_no") else None)
                        if not side:continue
                        if price>best["btts"].get(side,0):
                            best["btts"][side]=price; books_best["btts"][side]=bk.upper()
                        row["btts"][side]=price
                    elif kind=="spread":
                        side="home" if key in ("handicap_1","w1","home","1") or slug.endswith("_1") else ("away" if key in ("handicap_2","w2","away","2") or slug.endswith("_2") else None)
                        if side is None or arg is None or abs(float(arg))>.001:continue
                        q={"team":side,"point":0.0,"price":price,
                           "market_slug":slug,"market_group":market_key,"market_name":market_name}
                        old=next((x for x in best["spreads"] if x["team"]==side),None)
                        if old is None or price>old["price"]:
                            if old:best["spreads"].remove(old)
                            best["spreads"].append(q)
                            books_best["spreads"]=[x for x in books_best["spreads"] if x["team"]!=side]+[{"team":side,"point":0.0,"bookmaker":bk.upper()}]
                        row["spreads"].append(q)
                    elif kind in ("tt","total"):
                        # API-Sport may encode the selection as "over", "over_1_5",
                        # or "Over 1.5". Normalize the selection without relying on
                        # the exact line suffix; the line itself comes from argument.
                        side_key = ("over" if key in ("over", "больше") or key.startswith(("over", "больше")) else
                                    "under" if key in ("under", "меньше") or key.startswith(("under", "меньше")) else None)
                        if side_key is None or arg is None:continue
                        try:
                            point = float(arg)
                        except (TypeError, ValueError):
                            continue
                        # Exact lines supported by the model; discard quarter-lines and unrelated markets.
                        allowed_points = {0.5, 1.5, 2.5, 3.5} if kind == "tt" else {1.5, 2.5, 3.5}
                        if not any(abs(point - allowed) < 0.001 for allowed in allowed_points):
                            continue
                        if kind=="tt":
                            market_name = market.get("name") or {}
                            market_name = (market_name.get("en") or market_name.get("ru") or "") if isinstance(market_name, dict) else str(market_name)
                            name_key = re.sub(r"[^a-zа-я0-9]+", " ", market_name.lower()).strip()
                            slug_tokens = set(re.split(r"[^a-z0-9]+", slug.lower()))
                            team = ("home" if "home" in slug_tokens or
                                    ("1" in slug_tokens and "team" in slug_tokens) or
                                    slug.endswith(("_1", "1")) or "home" in name_key
                                    or "хозяев" in name_key or "team 1" in name_key
                                    or "команды 1" in name_key or "команда 1" in name_key else
                                    "away" if "away" in slug_tokens or
                                    ("2" in slug_tokens and "team" in slug_tokens) or
                                    slug.endswith(("_2", "2")) or "away" in name_key
                                    or "гостей" in name_key or "team 2" in name_key
                                    or "команды 2" in name_key or "команда 2" in name_key else None)
                            if team is None:continue
                            q={"team":team,"point":point,"side":side_key,"price":price,
                               "market_slug":slug,"market_group":market_key,"market_name":market_name}
                            target=best["teamTotals"]; row_target=row["teamTotals"]; books_target=books_best["teamTotals"]
                            identity=lambda x:x.get("team")==team and x.get("point")==q["point"] and x.get("side")==side_key and x.get("market_group")==market_key
                        else:
                            q={"point":point,"side":side_key,"price":price,
                               "market_slug":slug,"market_group":market_key,"market_name":market_name}
                            target=best["totals"]; row_target=row["totals"]; books_target=books_best["totals"]
                            identity=lambda x:x.get("point")==q["point"] and x.get("side")==side_key and x.get("market_group")==market_key
                        old=next((x for x in target if identity(x)),None)
                        if old is None or price>old["price"]:
                            if old:target.remove(old)
                            target.append(q)
                            books_target[:]=[x for x in books_target if not identity(x)]+[{k:q[k] for k in q if k!="price"}|{"bookmaker":bk.upper()}]
                        row_target.append(q)
        if any(row[k] for k in ("doubleChance","spreads","teamTotals","totals","btts")):
            book_rows.append(row)
    best={k:v for k,v in best.items() if v}
    books_best={k:v for k,v in books_best.items() if v}
    return {"eventId":str(m.get("id") or ""),"bookmaker":"BETBOOM","updatedAt":datetime.now(timezone.utc).isoformat(),"bookmakers":book_rows,"best":best,"bestBookmakers":books_best}

def main():
    key=os.getenv("API_SPORT_KEY","").strip()
    if not key:raise RuntimeError("API_SPORT_KEY missing from GitHub Secrets")
    data=json.loads(DATA.read_text(encoding="utf-8"))
    today=datetime.now(timezone.utc).date()
    params={"date_from":today.isoformat(),"date_to":(today+timedelta(days=14)).isoformat(),"exclude_amateur":"true","view":"odds","with_bk_odds":"true","odds_stage":"all","bookmaker_ids":",".join(BOOKS),"page_size":500,"sort":"asc"}
    session=requests.Session();session.headers.update({"Authorization":key})
    # API pages are capped at 500 fixtures; walk every page in the 14-day window.
    params["page"]=1
    resp=session.get(URL,params=params,timeout=60);resp.raise_for_status()
    payload=resp.json()
    if isinstance(payload,list):
        fixtures=payload
    else:
        fixtures=list(payload.get("matches") or [])
        total_pages=min(int(payload.get("totalPages") or 1),100)
        for page in range(2,total_pages+1):
            page_params={**params,"page":page}
            page_resp=session.get(URL,params=page_params,timeout=60)
            page_resp.raise_for_status()
            page_payload=page_resp.json()
            fixtures.extend(page_payload if isinstance(page_payload,list) else page_payload.get("matches") or [])
    eligible=matched=updated=retained=0
    for comp in data.get("competitions",[]):
        for match in comp.get("matches",[]):
            if match.get("status") not in ("SCHEDULED","TIMED","IN_PLAY","PAUSED"):continue
            eligible+=1
            # Clear legacy-provider prices. Never label stale prices as BetBoom.
            match.pop("odds",None)
            source=match_fixture(match,fixtures)
            if not source:continue
            matched+=1;quote=parse_odds(source)
            if quote["best"]:
                match["odds"]=quote
                updated+=1
    data["oddsProvider"]="API-Sport / BetBoom"
    data["oddsBookmaker"]="betboom"
    data["oddsUpdatedAt"]=datetime.now(timezone.utc).isoformat()
    data["oddsCoverage"]={"eligible":eligible,"matched":matched,"updated":updated,"noBetBoomQuotes":matched-updated,"notMatched":eligible-matched}
    data["oddsStatus"]="ready" if updated else "no_quotes"
    data["oddsNotice"]=f"BetBoom: получены котировки для {updated} матчей." if updated else "Для ближайших матчей не найдены подходящие котировки BetBoom."
    DATA.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"provider":"API-Sport / BetBoom","fixtures":len(fixtures),"eligible":eligible,"matched":matched,"updated":updated,"noBetBoomQuotes":matched-updated,"notMatched":eligible-matched}))

if __name__=="__main__":main()
