"""Provider-neutral model core shared by the active PitchAPI data pipeline.

This module contains only team-name normalization, xG model calculations,
historical baselines, and prediction archiving. Provider ingestion is handled
by backend/update_data_pitchapi.py.
"""
import json
import math
import re
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "data.json"
COMPS = {"PL": ("Англия · Премьер-лига", "eng"), "PD": ("Испания · Ла Лига", "esp"), "SA": ("Италия · Серия A", "ita"), "BL1": ("Германия · Бундеслига", "ger"), "FL1": ("Франция · Лига 1", "fra")}
MAP = {v: k for k, (_, v) in COMPS.items()}
WINDOW, HALF_LIFE, SHRINKAGE = 30, 12.0, 3.0

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
    return {"1":p1,"x":px,"2":p2,"1x":p1+px,"x2":px+p2,
      "over15":ov15,"over25":ov25,"over35":ov35,"under25":1-ov25,"bttsYes":btts,"bttsNo":1-btts,
      "homeOver05":ho05,"homeOver15":ho15,"homeOver25":ho25,"awayOver05":ao05,"awayOver15":ao15,"awayOver25":ao25,
      "ahMinus05Home":p1,"ahPlus05Home":p1+px,"expectedHomeGoals":lh,"expectedAwayGoals":la}

def historical_baselines(matches, comp, target_date):
    g=matches[(matches.comp==comp)&(matches.date<pd.Timestamp(target_date))]
    n=len(g)
    if n < 100:return None
    hs=g.home_score.astype(float); aw=g.away_score.astype(float)
    home_w=int((hs>aw).sum()); away_w=int((aw>hs).sum())
    def rate(count,total): return (count+1.0)/(total+2.0)
    return {"1x":rate(int((hs>=aw).sum()),n),"x2":rate(int((aw>=hs).sum()),n),
      "homeOver15":rate(int((hs>=2).sum()),n),"awayOver15":rate(int((aw>=2).sum()),n),"matches":n}

def strongest_signal(pred, baseline, home, away):
    if not baseline:return None
    candidates=[("1X","Хозяева не проиграют","1x","1X","Не 1X"),
      ("X2","Гости не проиграют","x2","X2","Не X2"),
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
    if not hn or not an:return None,"Не удалось надёжно сопоставить команды с исторической базой xG"
    hs=st.get((c,hn)); ads=st.get((c,an)); hb=base.get((c,"home")); ab=base.get((c,"away"))
    if not hs or not ads or not hb or not ab:return None,"Недостаточно исторических xG для одной из команд"
    lh=max(.05,min(4.5,hb*hs["attack"]*ads["defence"])); la=max(.05,min(4.5,ab*ads["attack"]*hs["defence"]))
    pred=markets(lh,la)
    pred["strongestSignal"]=strongest_signal(pred,historical_baselines(matches,c,target_date),home,away)
    return pred,None

def update_prediction_archive(payload):
    """Freeze forecasts before kickoff and attach final scores without rewriting forecasts."""
    now = datetime.now(timezone.utc)
    archive = {"version": 1, "matches": {}}
    if OUT.exists():
        try:
            previous = json.loads(OUT.read_text(encoding="utf-8"))
            old = previous.get("predictionArchive", {})
            if isinstance(old.get("matches"), dict):
                archive["matches"] = old["matches"]
        except Exception:
            pass

    for competition in payload.get("competitions", []):
        for match in competition.get("matches", []):
            match_id = match.get("id")
            if match_id is None:
                continue
            key_id = str(match_id)
            status = match.get("status")
            existing = archive["matches"].get(key_id)

            if existing:
                # Only attach the result. Never replace the captured forecast.
                if status == "FINISHED" and match.get("homeScore") is not None and match.get("awayScore") is not None:
                    existing["status"] = "FINISHED"
                    existing["homeScore"] = match["homeScore"]
                    existing["awayScore"] = match["awayScore"]
                    existing["resultUpdatedAt"] = now.isoformat()
                continue

            # Capture only a forecast that exists before scheduled kickoff.
            if status not in ("SCHEDULED", "TIMED") or not match.get("prediction"):
                continue
            try:
                kickoff = datetime.fromisoformat(str(match.get("utcDate", "")).replace("Z", "+00:00"))
                if kickoff.tzinfo is None:
                    kickoff = kickoff.replace(tzinfo=timezone.utc)
            except Exception:
                continue
            if kickoff <= now:
                continue

            archive["matches"][key_id] = {
                "id": match_id,
                "league": competition.get("name", ""),
                "home": match.get("home", ""),
                "away": match.get("away", ""),
                "utcDate": match.get("utcDate", ""),
                "predictionCapturedAt": now.isoformat(),
                "status": "SCHEDULED",
                "prediction": match["prediction"],
                "homeScore": None,
                "awayScore": None
            }

    payload["predictionArchive"] = archive
    payload["evaluationMatches"] = [
        item for item in archive["matches"].values()
        if item.get("status") == "FINISHED"
        and item.get("homeScore") is not None
        and item.get("awayScore") is not None
        and isinstance(item.get("prediction"), dict)
    ]
