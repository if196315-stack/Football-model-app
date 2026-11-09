#!/usr/bin/env python3
"""Seven-competition chronological xG backtest for every supported goal market.

Offline only. Same-calendar-day fixtures are scored before any same-day result/xG
is admitted to history. Baselines are expanding, Laplace-smoothed league outcome
frequencies. This evaluates probability quality, not betting ROI (historical odds
are not part of the xG archive).
"""
from __future__ import annotations
import argparse, csv, json, math
from collections import defaultdict
from pathlib import Path
from backtest_pitchapi import WINDOW, HALF_LIFE, SHRINKAGE, poisson, team_strength

MATCH_LINES = (1.5, 2.5, 3.5)
TEAM_LINES = (0.5, 1.5, 2.5, 3.5)

def domestic_rows(path):
    rows=[]
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            rows.append({"id":str(r["match_id"]),"date":str(r["date"])[:10],
              "league":r["league_code"],"season":r.get("season",""),
              "home":r["home_team"],"away":r["away_team"],
              "hg":int(r["home_goals"]),"ag":int(r["away_goals"]),
              "hx":float(r["home_xg"]),"ax":float(r["away_xg"])})
    return rows

def uefa_rows(path):
    payload=json.loads(Path(path).read_text(encoding="utf-8"))
    out=[]
    for r in payload.get("matches",[]):
        if r.get("pairedXg") is not True: continue
        if any(r.get(k) is None for k in ("homeScore","awayScore","homeXg","awayXg","date","leagueCode","home","away")): continue
        out.append({"id":str(r["id"]),"date":str(r["date"])[:10],
          "league":str(r["leagueCode"]),"season":str(r.get("season") or ""),
          "home":str(r["home"]),"away":str(r["away"]),
          "hg":int(r["homeScore"]),"ag":int(r["awayScore"]),
          "hx":float(r["homeXg"]),"ax":float(r["awayXg"])})
    return out

def market_outcomes(hg,ag):
    total=hg+ag
    d={"1X2":str(1 if hg>ag else 2 if hg<ag else 0),
       "DC_1X":int(hg>=ag),"DC_X2":int(ag>=hg),
       "BTTS":int(hg>0 and ag>0),
       "DNB_HOME":str("W" if hg>ag else "L" if hg<ag else "P"),
       "DNB_AWAY":str("W" if ag>hg else "L" if ag<hg else "P")}
    for line in MATCH_LINES: d[f"TOTAL_OVER_{line}"]=int(total>line)
    for side,goals in (("HOME",hg),("AWAY",ag)):
        for line in TEAM_LINES: d[f"{side}_OVER_{line}"]=int(goals>line)
    return d

def market_probs(lh,la):
    hp,ap=poisson(lh),poisson(la)
    joint={(h,a):ph*pa for h,ph in enumerate(hp) for a,pa in enumerate(ap)}
    p1=sum(p for (h,a),p in joint.items() if h>a)
    px=sum(p for (h,a),p in joint.items() if h==a)
    p2=max(0.0,1-p1-px)
    out={"1X2":{"1":p1,"X":px,"2":p2},
         "DC_1X":p1+px,"DC_X2":p2+px,
         "BTTS":(1-hp[0])*(1-ap[0]),
         "DNB_HOME":{"W":p1,"P":px,"L":p2},
         "DNB_AWAY":{"W":p2,"P":px,"L":p1}}
    for line in MATCH_LINES:
        out[f"TOTAL_OVER_{line}"]=sum(p for (h,a),p in joint.items() if h+a>line)
    for side,pmf in (("HOME",hp),("AWAY",ap)):
        for line in TEAM_LINES:
            out[f"{side}_OVER_{line}"]=sum(p for g,p in enumerate(pmf) if g>line)
    return out

def score(rows, market, source):
    classes = ("1","X","2") if market=="1X2" else (("W","P","L") if market.startswith("DNB") else ("0","1"))
    n=len(rows)
    if not n:return None
    brier=ll=correct=0.0
    bins={c:[] for c in classes}
    for r in rows:
        p=r[source]; actual=str(r["actual"])
        if market not in ("1X2",) and not market.startswith("DNB"): p={"0":1-float(p),"1":float(p)}
        brier+=sum((float(p[c])-(1.0 if c==actual else 0.0))**2 for c in classes)
        ll-=math.log(max(1e-15,float(p[actual])))
        correct+=int(max(classes,key=lambda c:p[c])==actual)
        for c in classes: bins[c].append((float(p[c]),int(actual==c)))
    ece={}
    for c,vals in bins.items():
        ece[c]=sum(len(b)*abs(sum(x for x,y in b)/len(b)-sum(y for x,y in b)/len(b)) for b in
          [[v for v in vals if i/10<=v[0]<(i+1)/10 or (i==9 and v[0]==1)] for i in range(10)] if b)/n
    return {"n":n,"brier":brier/n,"log_loss":ll/n,"accuracy":correct/n,"ece":ece}

def run(rows, holdout):
    rows=sorted(rows,key=lambda r:(r["date"],r["league"],r["id"]))
    histories=defaultdict(list); league_xg=defaultdict(list); counts=defaultdict(lambda:defaultdict(int))
    bydate=defaultdict(list)
    for r in rows: bydate[r["date"]].append(r)
    predictions=[]
    markets=["1X2","DC_1X","DC_X2","BTTS",*(f"TOTAL_OVER_{x}" for x in MATCH_LINES),
      *(f"{s}_OVER_{x}" for s in ("HOME","AWAY") for x in TEAM_LINES),"DNB_HOME","DNB_AWAY"]
    for date in sorted(bydate):
        todays=bydate[date]
        base={}
        for league in {m["league"] for m in todays}:
            for side in ("home","away"):
                vals=[v[side+"_xg"] for v in league_xg[league] if v["side"]==side]
                if vals:base[(league,side)]=sum(vals)/len(vals)
        batch=[]
        for m in todays:
            l=m["league"]; hs=team_strength(histories[(l,m["home"])],l,base); aws=team_strength(histories[(l,m["away"])],l,base)
            hb,ab=base.get((l,"home")),base.get((l,"away"))
            if not hs or not aws or not hb or not ab: continue
            lh=max(.05,min(4.5,hb*hs["attack"]*aws["defence"]))
            la=max(.05,min(4.5,ab*aws["attack"]*hs["defence"]))
            mp=market_probs(lh,la); actual=market_outcomes(m["hg"],m["ag"])
            for market in markets:
                key=actual[market]
                prior=counts[l][market]
                classes=("1","X","2") if market=="1X2" else (("W","P","L") if market.startswith("DNB") else ("0","1"))
                basep={c:(prior[c]+1)/(sum(prior.values())+len(classes)) for c in classes}
                batch.append({"league":l,"season":m["season"],"date":date,"market":market,"actual":key,
                              "model":mp[market],"baseline":basep})
        predictions.extend(batch)
        # Update only after every fixture for this date has been predicted.
        for m in todays:
            l=m["league"]; a=market_outcomes(m["hg"],m["ag"])
            for market in markets: counts[l][market][str(a[market])]+=1
            histories[(l,m["home"])].append({"side":"home","xg":m["hx"],"xga":m["ax"]})
            histories[(l,m["away"])].append({"side":"away","xg":m["ax"],"xga":m["hx"]})
            league_xg[l].append({"side":"home","home_xg":m["hx"],"away_xg":m["ax"]})
            league_xg[l].append({"side":"away","home_xg":m["hx"],"away_xg":m["ax"]})
    result={"method":"calendar_date_walk_forward_independent_poisson","holdout_from":holdout,
      "input_matches":len(rows),"predicted_match_market_rows":len(predictions),
      "competitions":sorted({r["league"] for r in rows}),"markets":{}}
    for market in markets:
        allm=[p for p in predictions if p["market"]==market]
        train=[p for p in allm if p["date"]<holdout]; test=[p for p in allm if p["date"]>=holdout]
        def grouped(items):
            out={}
            for league in sorted({x["league"] for x in items}):
                subset=[x for x in items if x["league"]==league]
                out[league]={"model":score(subset,market,"model"),"baseline":score(subset,market,"baseline")}
            return out
        result["markets"][market]={"all":{"model":score(allm,market,"model"),"baseline":score(allm,market,"baseline")},
          "holdout":{"model":score(test,market,"model"),"baseline":score(test,market,"baseline")},
          "holdout_by_competition":grouped(test),"train_n":len(train)}
    return result

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--domestic",required=True);p.add_argument("--uefa",required=True)
    p.add_argument("--output",required=True);p.add_argument("--holdout-from",default="2026-07-01")
    a=p.parse_args()
    rows=domestic_rows(a.domestic)+uefa_rows(a.uefa)
    ids=[r["id"] for r in rows]
    if len(ids)!=len(set(ids)): raise SystemExit("Duplicate match IDs across domestic and UEFA archives")
    report=run(rows,a.holdout_from)
    Path(a.output).parent.mkdir(parents=True,exist_ok=True)
    Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"input_matches":report["input_matches"],"competitions":report["competitions"],
      "market_holdout_n":{k:v["holdout"]["model"]["n"] if v["holdout"]["model"] else 0 for k,v in report["markets"].items()}},ensure_ascii=False))
if __name__=="__main__":main()
