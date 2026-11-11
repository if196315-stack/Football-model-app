#!/usr/bin/env python3
"""Read-only audit of domestic AI signal eligibility using Android rules."""
import argparse, json, math, re
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path

LEAGUES = {"ENG":"Англия · Премьер-лига","ESP":"Испания · Ла Лига","ITA":"Италия · Серия A","GER":"Германия · Бундеслига","FRA":"Франция · Лига 1"}
MIN_ODDS, MIN_PROB, MIN_EV, MIN_IMPLIED = 1.50, 0.60, 0.05, 0.05

def num(v, default=float("nan")):
    try:
        x=float(v)
        return x if math.isfinite(x) else default
    except (TypeError,ValueError): return default

def s(v): return "" if v is None else str(v).strip()

def active_line(m):
    o=m.get("odds")
    if not isinstance(o,dict): return False
    source=s(o.get("source"))
    if s(o.get("bookmaker")).upper()!="BETBOOM" and not ("api-sport" in source.lower() and "betboom" in source.lower()): return False
    b=o.get("best")
    if not isinstance(b,dict): return False
    valid=lambda x: math.isfinite(num(x)) and num(x)>1.0
    dc=b.get("doubleChance") or {}
    bt=b.get("btts") or {}
    if any(valid(dc.get(k)) for k in ("1x","x2")) or any(valid(bt.get(k)) for k in ("yes","no")): return True
    for field in ("totals","teamTotals"):
        rows=b.get(field)
        if isinstance(rows,list) and any(isinstance(r,dict) and s(r.get("side")).lower()=="over" and valid(r.get("price")) for r in rows): return True
    return False

def odds_for(sig,m):
    side=s(sig.get("side")); b=((m.get("odds") or {}).get("best") or {})
    if not isinstance(b,dict): return None
    def ok(v):
        x=num(v)
        return x if math.isfinite(x) and x>=MIN_ODDS else None
    if side.upper() in ("1X","X2"): return ok((b.get("doubleChance") or {}).get("1x" if side.upper()=="1X" else "x2"))
    if side.startswith("ИТБ "):
        mt=re.search(r"ИТБ\s+([0-9]+(?:\.[0-9]+)?)",side,re.I)
        if not mt: return None
        point=num(mt.group(1)); team=s(side.split("·",1)[1]) if "·" in side else ""
        home,away=s(m.get("home")),s(m.get("away"))
        target="home" if team.lower()==home.lower() else "away" if team.lower()==away.lower() else ""
        for r in b.get("teamTotals",[]) if isinstance(b.get("teamTotals"),list) else []:
            if not isinstance(r,dict): continue
            rt=s(r.get("team"))
            rs="home" if rt.lower() in ("home","хозяева",home.lower()) or rt=="1" else "away" if rt.lower() in ("away","гости",away.lower()) or rt=="2" else ""
            line=num(r.get("point")); price=ok(r.get("price"))
            if target and rs==target and s(r.get("side")).lower()=="over" and math.isfinite(line) and abs(line-point)<.001 and price is not None: return price
        return None
    if side.startswith("ТБ "):
        point=num(side[3:])
        for r in b.get("totals",[]) if isinstance(b.get("totals"),list) else []:
            if not isinstance(r,dict): continue
            line=num(r.get("point")); price=ok(r.get("price"))
            if s(r.get("side"))=="over" and math.isfinite(point) and math.isfinite(line) and abs(line-point)<.001 and price is not None: return price
        return None
    if side in ("ОЗ ДА","ОЗ НЕТ"): return ok((b.get("btts") or {}).get("yes" if side=="ОЗ ДА" else "no"))
    return None

def signals_for(p):
    a=[x for x in p.get("signals",[]) if isinstance(x,dict)] if isinstance(p.get("signals"),list) else []
    strongest=p.get("strongestSignal")
    if isinstance(strongest,dict):
        side=s(strongest.get("side"))
        if side and not any(s(x.get("side")).lower()==side.lower() for x in a): a.append(strongest)
    return a

def check(sig,m):
    side=s(sig.get("side"))
    if sig.get("noPositiveSignal") is True: return None,"no_positive_signal"
    edge=num(sig.get("selectionEdgePp"),num(sig.get("edgePp")))
    if not math.isfinite(edge) or edge<=0: return None,"non_positive_selection_edge"
    if side.upper()=="ОЗ НЕТ": return None,"excluded_btts_no"
    odds=odds_for(sig,m)
    if odds is None: return None,"no_matching_betboom_price"
    p=num(sig.get("modelProbability"))
    if not math.isfinite(p): return None,"missing_probability"
    ev=p*odds-1
    if p<MIN_PROB: return None,"probability_below_60pct"
    if ev<MIN_EV: return None,"ev_below_5pct"
    if sig.get("paperOnly") is True:
        if num(sig.get("edgePp"),0)<5: return None,"paper_edge_below_5pp"
    else:
        implied=p-1/odds+num(sig.get("formAdjustmentPp"),0)/100
        if implied<MIN_IMPLIED: return None,"implied_edge_below_5pp"
    return {"side":side,"odds":round(odds,3),"probability":round(p,4),"selection_edge_pp":round(edge,3),"ev_pct":round(ev*100,2),"paper_only":sig.get("paperOnly") is True},None

def match_day(m):
    raw=s(m.get("utcDate"))[:10] or s(m.get("dateEvent"))[:10]
    try: return date.fromisoformat(raw)
    except ValueError: return None

def build(data):
    today=datetime.now(timezone.utc).date()
    out={"generated_at_utc":datetime.now(timezone.utc).isoformat(),"as_of_utc_date":today.isoformat(),"thresholds":{"min_odds":MIN_ODDS,"min_probability":MIN_PROB,"min_ev_pct":MIN_EV*100,"min_selection_edge_pp":"> 0","min_implied_edge_pct_non_paper":MIN_IMPLIED*100,"min_raw_edge_pp_paper_only":5},"leagues":{}}
    comps=data.get("competitions",[])
    for code,name in LEAGUES.items():
        comp=next((x for x in comps if isinstance(x,dict) and s(x.get("code")).upper()==code),{})
        counts=Counter(); qualifying=[]; with_candidates=[]
        for m in comp.get("matches",[]) if isinstance(comp,dict) else []:
            if not isinstance(m,dict) or s(m.get("status")) not in ("SCHEDULED","TIMED"): continue
            day=match_day(m)
            if day is None or day<today: continue
            counts["scheduled_future"]+=1
            p=m.get("prediction")
            if not isinstance(p,dict): counts["without_prediction"]+=1; continue
            counts["with_prediction"]+=1
            if not active_line(m): counts["without_active_betboom_line"]+=1; continue
            counts["with_active_betboom_line"]+=1
            sigs=signals_for(p)
            if not sigs: counts["without_candidate_signal"]+=1; continue
            counts["with_candidate_signal"]+=1
            passed=[]
            for sig in sigs:
                result,reason=check(sig,m)
                if result: passed.append(result); counts["qualifying_signals"]+=1
                else: counts["rejected_signals"]+=1; counts["rejected:"+reason]+=1
            row={"date":day.isoformat(),"home":s(m.get("home")),"away":s(m.get("away")),"candidate_signals":len(sigs),"qualifying_signals":passed}
            with_candidates.append(row)
            if passed: counts["qualifying_matches"]+=1; qualifying.append(row)
        out["leagues"][code]={"name":name,"counts":dict(sorted(counts.items())),"qualifying_matches":qualifying,"matches_with_candidates":with_candidates}
    total=Counter()
    for v in out["leagues"].values(): total.update(v["counts"])
    out["totals"]=dict(sorted(total.items()))
    return out

def markdown(r):
    L=["# Отчёт: сильные AI-сигналы в пяти национальных лигах","",f"- Дата среза UTC: **{r['as_of_utc_date']}**",f"- Время формирования: {r['generated_at_utc']}","- Источник: текущий docs/data.json из main; только предстоящие SCHEDULED/TIMED матчи.","- Матч считается прошедшим отбор, если хотя бы один сигнал проходит все Android-фильтры.","","## Порог AI","","- Активная линия BetBoom (API-Sport) и точное совпадение рынка/исхода; коэффициент ≥ 1.50.","- Вероятность ≥ 60%; EV ≥ 5%; selection edge > 0 п.п.","- Для обычного сигнала implied edge ≥ 5 п.п.; для paperOnly raw edge ≥ 5 п.п.","- ОЗ НЕТ исключён; чужие/синтетические цены не используются.","","## Итог по лигам","","| Лига | Будущих матчей | С прогнозом | С BetBoom | С сигналами-кандидатами | Прошли AI | Сигналов прошло |","|---|---:|---:|---:|---:|---:|---:|"]
    for code,v in r["leagues"].items():
        c=v["counts"]; L.append(f"| {code} | {c.get('scheduled_future',0)} | {c.get('with_prediction',0)} | {c.get('with_active_betboom_line',0)} | {c.get('with_candidate_signal',0)} | **{c.get('qualifying_matches',0)}** | {c.get('qualifying_signals',0)} |")
    t=r["totals"]; L.append(f"| **Всего** | **{t.get('scheduled_future',0)}** | **{t.get('with_prediction',0)}** | **{t.get('with_active_betboom_line',0)}** | **{t.get('with_candidate_signal',0)}** | **{t.get('qualifying_matches',0)}** | **{t.get('qualifying_signals',0)}** |")
    L += ["","## Матчи, прошедшие отбор",""]
    any_match=False
    for code,v in r["leagues"].items():
        if not v["qualifying_matches"]: continue
        any_match=True; L.append("### "+code)
        for m in v["qualifying_matches"]:
            sig=", ".join(f"{x['side']} @ {x['odds']:.2f} (P={x['probability']:.1%}, EV={x['ev_pct']:.1f}%)" for x in m["qualifying_signals"])
            L.append(f"- {m['date']} — {m['home']} — {m['away']}: {sig}")
        L.append("")
    if not any_match: L += ["В этом срезе матчей, прошедших все фильтры, нет.",""]
    L += ["## Причины отсева",""]
    for code,v in r["leagues"].items():
        reasons={k.split(":",1)[1]:n for k,n in v["counts"].items() if k.startswith("rejected:")}
        if reasons: L.append("- **"+code+":** "+"; ".join(f"{k.replace('_',' ')} — {n}" for k,n in sorted(reasons.items())))
    L += ["","## Оговорки","","- Это моментальный срез предстоящих матчей, а не прогноз на весь тур/сезон.","- Количество матчей отличается от количества сигналов: в одном матче могут пройти несколько рынков.","- Подсчёт повторяет условия collectAiCandidates Android; файл отчёта не изменяет feed и не размещает ставки.",""]
    return "\n".join(L)

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--input",default="docs/data.json")
    ap.add_argument("--json-out",default="ai-domestic-audit.json")
    ap.add_argument("--markdown-out",default="ai-domestic-audit.md")
    a=ap.parse_args()
    report=build(json.loads(Path(a.input).read_text(encoding="utf-8")))
    Path(a.json_out).write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    Path(a.markdown_out).write_text(markdown(report),encoding="utf-8")
    print(markdown(report))

if __name__=="__main__": main()
