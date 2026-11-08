import json, math, os, re
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import time
from difflib import SequenceMatcher
from pathlib import Path
import pandas as pd
import requests

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/"docs"/"data.json"
BASE="https://github.com/nlbair/openplay/releases/download/data-v0.1.0"
COMPS={"PL":("Англия · Премьер-лига","eng"),"PD":("Испания · Ла Лига","esp"),"SA":("Италия · Серия A","ita"),"BL1":("Германия · Бундеслига","ger"),"FL1":("Франция · Лига 1","fra")}
MAP={v:k for k,(_,v) in COMPS.items()}
WINDOW,HALF_LIFE,SHRINKAGE=30,12.0,3.0

def parquet(name):
    p=ROOT/"backend"/"cache"/name
    p.parent.mkdir(parents=True,exist_ok=True)
    if not p.exists():
        r=requests.get(f"{BASE}/{name}",timeout=120); r.raise_for_status(); p.write_bytes(r.content)
    return pd.read_parquet(p)

def key(s):
    """Normalize club names across provider spelling, accents and common suffixes."""
    import unicodedata
    value=unicodedata.normalize("NFKD",str(s or "").lower())
    value="".join(ch for ch in value if not unicodedata.combining(ch))
    value=value.replace("&"," and ").replace("@"," at ")
    value=re.sub(r"[^a-z0-9]+"," ",value)
    ignored={"fc","afc","cf","sc","ac","club","football","the","de"," del","cd","ud","sd","fk","sk","sv","vfl","tsv","1"}
    return " ".join(x for x in value.split() if x not in ignored)

# Common provider/Openplay aliases. Keys and values are normalized by key().
ALIASES={
    "eng":{
        "man utd":"manchester united","man united":"manchester united",
        "man city":"manchester city","spurs":"tottenham hotspur",
        "wolves":"wolverhampton wanderers","brighton":"brighton hove albion",
        "west ham":"west ham united","newcastle":"newcastle united",
        "nottingham forest":"nottm forest",
    },
    "esp":{
        "atletico madrid":"atletico de madrid","athletic bilbao":"athletic club",
        "real betis":"real betis balompie","celta vigo":"rc celta de vigo",
        "sociedad":"real sociedad","espanyol":"rcd espanyol",
    },
    "ita":{
        "inter":"internazionale","inter milan":"internazionale",
        "milan":"ac milan","roma":"as roma","lazio":"ss lazio",
        "juventus":"juventus",
    },
    "ger":{
        "bayern":"bayern munich","bayern munchen":"bayern munich",
        "dortmund":"borussia dortmund","gladbach":"borussia monchengladbach",
        "leverkusen":"bayer leverkusen","koln":"fc koln",
    },
    "fra":{
        "psg":"paris saint germain","paris sg":"paris saint germain",
        "olympique lyon":"lyon","olympique marseille":"marseille",
        "st etienne":"saint etienne",
    },
}

def _alias_key(value, league):
    normalized=key(value)
    aliases=ALIASES.get(league,{})
    # Resolve aliases in both directions, supporting either provider as source.
    normalized=key(aliases.get(normalized,normalized))
    for alias,canonical in aliases.items():
        if normalized==key(canonical):
            return key(canonical)
    return normalized

def _similarity(a,b):
    if not a or not b:return 0.0
    seq=SequenceMatcher(None,a,b).ratio()
    at=set(a.split()); bt=set(b.split())
    token=(2*len(at&bt)/max(1,len(at)+len(bt)))
    # A partial match helps when one source includes a city or legal suffix.
    shorter,longer=(a,b) if len(a)<=len(b) else (b,a)
    partial=SequenceMatcher(None,shorter,longer).ratio() if len(shorter)>=5 else 0.0
    return max(seq,0.72*seq+0.28*token,0.90*partial)

def match_name(name,names,league=None):
    target=_alias_key(name,league)
    normalized=[(n,_alias_key(n,league)) for n in names]
    exact=[n for n,k in normalized if k==target]
    if len(exact)==1:return exact[0]
    ranked=sorted(((_similarity(target,k),n) for n,k in normalized),reverse=True)
    if not ranked:return None
    best_score,best_name=ranked[0]
    second_score=ranked[1][0] if len(ranked)>1 else 0.0
    # Conservative fuzzy acceptance: high confidence and a clear gap over runner-up.
    if best_score>=0.88 and best_score-second_score>=0.06:
        return best_name
    return None

def history():
    m=parquet("matches.parquet"); s=parquet("team_match_stats.parquet")
    m=m[m.comp.isin(MAP)].copy(); s=s[s.comp.isin(MAP)].copy()
    m["date"]=pd.to_datetime(m.date); s["date"]=pd.to_datetime(s.date)
    h=m[["match_id","comp","home_team_id","home_team"]].rename(columns={"home_team_id":"team_id","home_team":"team"})
    a=m[["match_id","comp","away_team_id","away_team"]].rename(columns={"away_team_id":"team_id","away_team":"team"})
    s=s.merge(pd.concat([h,a]).drop_duplicates(["match_id","team_id"]),on=["match_id","comp","team_id"],how="left")
    s=s.dropna(subset=["date","team","xg","xg_against"]).sort_values("date")
    base={}
    for c in MAP:
        for side in ("home","away"):
            v=s.loc[(s.comp==c)&(s.side==side),"xg"]
            base[c,side]=float(v.mean()) if len(v) else None
    return s,base,m

def strengths(s,base):
    out={}
    for (c,t),g in s.groupby(["comp","team"]):
        g=g.tail(WINDOW); aa=dd=sw=0.0
        for i,(_,r) in enumerate(g.iterrows()):
            w=.5**((len(g)-1-i)/HALF_LIFE); b=base.get((c,str(r.side))); ob=base.get((c,"away" if r.side=="home" else "home"))
            if not b or not ob:continue
            aa+=w*float(r.xg)/b; dd+=w*float(r.xg_against)/ob; sw+=w
        if sw:out[c,t]={"attack":(aa+SHRINKAGE)/(sw+SHRINKAGE),"defence":(dd+SHRINKAGE)/(sw+SHRINKAGE),"matches":len(g)}
    return out

def pois(lam):
    p=[math.exp(-lam)]
    for i in range(1,13):p.append(p[-1]*lam/i)
    p[-1]+=max(0,1-sum(p)); return p

def markets(lh,la):
    h,a=pois(lh),pois(la); p1=px=p2=ov15=ov25=ov35=btts=ho05=ho15=ho25=ao05=ao15=ao25=0.0
    for x,hp in enumerate(h):
        for y,ap in enumerate(a):
            p=hp*ap
            if x>y:p1+=p
            elif x==y:px+=p
            else:p2+=p
            if x+y>=2:ov15+=p
            if x+y>=3:ov25+=p
            if x+y>=4:ov35+=p
            if x and y:btts+=p
            if x>=1:ho05+=p
            if x>=2:ho15+=p
            if x>=3:ho25+=p
            if y>=1:ao05+=p
            if y>=2:ao15+=p
            if y>=3:ao25+=p
    return {"1":p1,"x":px,"2":p2,"1x":p1+px,"x2":px+p2,"dnbHome":p1/max(1e-12,p1+p2),"dnbAway":p2/max(1e-12,p1+p2),
      "over15":ov15,"over25":ov25,"over35":ov35,"under25":1-ov25,"bttsYes":btts,"bttsNo":1-btts,
      "homeOver05":ho05,"homeOver15":ho15,"homeOver25":ho25,"awayOver05":ao05,"awayOver15":ao15,"awayOver25":ao25,
      "ah0Home":p1/max(1e-12,p1+p2),"ahMinus05Home":p1,"ahPlus05Home":p1+px,"expectedHomeGoals":lh,"expectedAwayGoals":la}

def historical_baselines(matches, comp, target_date):
    g=matches[(matches.comp==comp)&(matches.date<pd.Timestamp(target_date))]
    n=len(g)
    if n < 100:return None
    hs=g.home_score.astype(float); aw=g.away_score.astype(float)
    home_w=int((hs>aw).sum()); away_w=int((aw>hs).sum())
    def rate(count,total): return (count+1.0)/(total+2.0)
    return {"1x":rate(int((hs>=aw).sum()),n),"x2":rate(int((aw>=hs).sum()),n),
      "dnbHome":rate(home_w,home_w+away_w) if home_w+away_w else 0.5,
      "dnbAway":rate(away_w,home_w+away_w) if home_w+away_w else 0.5,
      "homeOver15":rate(int((hs>=2).sum()),n),"awayOver15":rate(int((aw>=2).sum()),n),"matches":n}

def strongest_signal(pred, baseline, home, away):
    if not baseline:return None
    candidates=[("1X","Хозяева не проиграют","1x","1X","Не 1X"),
      ("X2","Гости не проиграют","x2","X2","Не X2"),
      ("Фора 0 хозяев","Фора 0 хозяев","dnbHome",f"Фора 0 · {home}",f"Фора 0 · {away}"),
      ("Фора 0 гостей","Фора 0 гостей","dnbAway",f"Фора 0 · {away}",f"Фора 0 · {home}"),
      ("ИТБ 1.5 хозяев","Хозяева забьют 2+","homeOver15",f"ИТБ 1.5 · {home}",f"ИТМ 1.5 · {home}"),
      ("ИТБ 1.5 гостей","Гости забьют 2+","awayOver15",f"ИТБ 1.5 · {away}",f"ИТМ 1.5 · {away}")]
    best=None
    for key,title,field,yes,no in candidates:
      p=float(pred[field]); b=float(baseline[field])
      if p>=0.5: side,mp,bp=yes,p,b
      else: side,mp,bp=no,1-p,1-b
      edge=mp-bp
      item={"market":title,"side":side,"modelProbability":mp,"baselineProbability":bp,
        "edgePp":edge*100.0,"baselineMatches":baseline["matches"]}
      if best is None or edge>best["edgePp"]/100.0:best=item
    if best is not None and best["edgePp"]<=0:best["noPositiveSignal"]=True
    return best

def predict(c,home,away,st,base,names,matches,target_date):
    hn=match_name(home,names.get(c,[]),c); an=match_name(away,names.get(c,[]),c)
    if not hn or not an:return None,"Не удалось надёжно сопоставить команды с Openplay"
    hs=st.get((c,hn)); ads=st.get((c,an)); hb=base.get((c,"home")); ab=base.get((c,"away"))
    if not hs or not ads or not hb or not ab:return None,"Недостаточно исторических xG для одной из команд"
    lh=max(.05,min(4.5,hb*hs["attack"]*ads["defence"])); la=max(.05,min(4.5,ab*ads["attack"]*hs["defence"]))
    pred=markets(lh,la)
    pred["strongestSignal"]=strongest_signal(pred,historical_baselines(matches,c,target_date),home,away)
    return pred,None

def preserve_existing_odds(payload):
    """Keep bookmaker quotes when the six-hour fixture/model refresh rewrites data.json."""
    if not OUT.exists():
        return
    try:
        previous = json.loads(OUT.read_text(encoding="utf-8"))
    except Exception:
        return
    old_odds = {}
    for competition in previous.get("competitions", []):
        for match in competition.get("matches", []):
            quote = match.get("odds")
            match_id = match.get("id")
            if quote and match_id is not None:
                old_odds[str(match_id)] = quote
    kept = 0
    for competition in payload.get("competitions", []):
        for match in competition.get("matches", []):
            quote = old_odds.get(str(match.get("id")))
            if quote and match.get("status") in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                match["odds"] = quote
                kept += 1
    for key in ("oddsUpdatedAt", "oddsCreditsRemaining", "oddsCreditsUsed", "oddsStatus", "oddsNotice", "oddsErrors", "oddsCoverage"):
        if key in previous:
            payload[key] = previous[key]
    if kept:
        payload["oddsNotice"] = f"Сохранены котировки для {kept} матчей; обновление коэффициентов выполняется отдельным заданием."


def main():
    p={"updatedAt":datetime.now(timezone.utc).isoformat(),"source":"Football-Data.org (fixtures/results); openplay (Noah Bair), CC BY 4.0 (xG/history)",
      "modelVersion":"Openplay v1.3 · frozen research baseline","modelConfig":{"window":WINDOW,"halfLife":HALF_LIFE,"shrinkage":SHRINKAGE},
      "openplayDataThrough":None,"status":"initializing","notice":"Загружаю исторические xG и расписание.",
      "dataWarning":"При отсутствии xG за текущий сезон прогноз использует последнюю доступную историю Openplay; дата базы указана отдельно.","competitions":[]}
    try:
        s,b,matches=history(); st=strengths(s,b); p["openplayDataThrough"]=s.date.max().date().isoformat()
        names={c:sorted(s.loc[s.comp==c,"team"].dropna().unique().tolist()) for c in MAP}
        token=os.getenv("FOOTBALL_DATA_TOKEN","").strip()
        if not token:
            p["status"]="missing_api_token"; p["notice"]="Историческая модель загружена, но отсутствует FOOTBALL_DATA_TOKEN для получения расписания."
        else:
            for code,(label,c) in COMPS.items():
                item={"code":code,"league":c,"name":label,"matches":[],"error":None}
                try:
                    # Football-Data.org applies a shared request-rate limit. Retry 429
                    # responses and respect Retry-After instead of silently losing leagues.
                    url=f"https://api.football-data.org/v4/competitions/{code}/matches"
                    params={"status":"SCHEDULED,TIMED,POSTPONED,IN_PLAY,PAUSED,FINISHED"}
                    r=None
                    for attempt in range(4):
                        r=requests.get(url,headers={"X-Auth-Token":token},params=params,timeout=35)
                        if r.status_code == 429:
                            if attempt == 3:
                                break
                            retry=r.headers.get("Retry-After","").strip()
                            wait=None
                            if retry:
                                try: wait=float(retry)
                                except ValueError:
                                    try: wait=max(1,(parsedate_to_datetime(retry)-datetime.now(timezone.utc)).total_seconds())
                                    except Exception: wait=None
                            if wait is None: wait=65*(attempt+1)
                            time.sleep(min(max(wait,5),180))
                            continue
                        if r.status_code in (500,502,503,504) and attempt < 3:
                            time.sleep(10*(attempt+1)); continue
                        break
                    if r is None or not r.ok:
                        detail=(r.text[:180] if r is not None else "no response")
                        raise RuntimeError(f"Football-Data.org HTTP {r.status_code if r is not None else 'error'} for {code}: {detail}")
                    # Stay below the provider's per-minute request limit.
                    time.sleep(7)
                    for m in r.json().get("matches",[]):
                        home=(m.get("homeTeam") or {}).get("name") or "TBD"; away=(m.get("awayTeam") or {}).get("name") or "TBD"
                        pred,warn=predict(c,home,away,st,b,names,matches,m.get("utcDate","")[:10])
                        score=(m.get("score") or {}).get("fullTime") or {}
                        item["matches"].append({"id":m.get("id"),"utcDate":m.get("utcDate"),"status":m.get("status"),"home":home,"away":away,
                          "homeScore":score.get("home"),"awayScore":score.get("away"),"prediction":pred,"predictionWarning":warn})
                except Exception as e:item["error"]=str(e)[:240]
                item["matches"].sort(key=lambda x:x.get("utcDate") or "")
                p["competitions"].append(item)
            upcoming=[m for c in p["competitions"] for m in c["matches"] if m.get("status") in ("SCHEDULED","TIMED") and m.get("prediction")]
            p["status"]="ready" if upcoming else "no_predictions"
            p["notice"]="Прогнозы рассчитаны по последней доступной истории Openplay. Свежесть xG указана отдельно."
    except Exception as e:
        p["status"]="model_data_error"; p["notice"]="Ошибка загрузки/обработки Openplay: "+str(e)[:300]
    preserve_existing_odds(p)
    OUT.parent.mkdir(parents=True,exist_ok=True); OUT.write_text(json.dumps(p,ensure_ascii=False,indent=2),encoding="utf-8")

if __name__=="__main__":main()
