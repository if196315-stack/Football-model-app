"""Read-only MelBet coverage audit for eight competitions and 14 markets."""
import json, os, re, sys
from datetime import datetime, timezone
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from update_odds_api_sport import URL, _response_matches, iter_date_windows, kickoff, match_fixture, market_kind, market_label, is_supported_full_time_market, lines

BOOKMAKER = "melbet"
FEEDS = (ROOT / "docs/data.json", ROOT / "docs/uefa-data.json")
MARKETS = [
 ("dc_1x","Двойной шанс 1X"),("dc_x2","Двойной шанс X2"),("btts_yes","Обе забьют — Да"),
 ("total_over_1_5","Тотал матча Больше 1.5"),("total_over_2_5","Тотал матча Больше 2.5"),("total_over_3_5","Тотал матча Больше 3.5"),
 ("home_over_0_5","ИТБ хозяев 0.5"),("home_over_1_5","ИТБ хозяев 1.5"),("home_over_2_5","ИТБ хозяев 2.5"),("home_over_3_5","ИТБ хозяев 3.5"),
 ("away_over_0_5","ИТБ гостей 0.5"),("away_over_1_5","ИТБ гостей 1.5"),("away_over_2_5","ИТБ гостей 2.5"),("away_over_3_5","ИТБ гостей 3.5"),
]
KEYS = [key for key, _ in MARKETS]

def empty():
 return {key: None for key in KEYS}

def read_targets():
 comps=[]
 for path in FEEDS:
  payload=json.loads(path.read_text(encoding="utf-8"))
  comps.extend(payload.get("competitions") or [])
 today=datetime.now(timezone.utc).date()
 targets=[]
 for comp in comps:
  for match in comp.get("matches") or []:
   stamp=kickoff(match)
   if match.get("status") in ("SCHEDULED","TIMED","IN_PLAY","PAUSED") and stamp and stamp.date()>=today:
    targets.append((str(comp.get("code") or comp.get("name") or "unknown"),str(comp.get("name") or comp.get("code") or "unknown"),match))
 return targets

def normalize_market_set(source, bookmaker="melbet"):
 found=empty()
 board=((source.get("oddsBk") or {}).get(bookmaker) or {})
 for slug, market in (board.get("markets") or {}).items():
  if not isinstance(market,dict) or market.get("status")!="open" or market.get("stage")!="prematch":
   continue
  if not is_supported_full_time_market(slug,market):
   continue
  kind=market_kind(slug,market)
  name=market_label(market).lower()
  tokens=set(re.split(r"[^a-z0-9]+",str(slug).lower()))
  for outcome,stake in (market.get("stakes") or {}).items():
   if not isinstance(stake,dict) or stake.get("suspended"):
    continue
   out=str(outcome).lower()
   for line in lines(stake):
    try: price=float(line.get("factor"))
    except (TypeError,ValueError): continue
    key=None
    if kind=="dc":
     side={"home_or_draw":"1x","draw_or_away":"x2","1x":"1x","x2":"x2"}.get(out)
     if side: key="dc_"+side
    elif kind=="btts":
     if out in ("yes","true","1") or out.endswith("_yes"): key="btts_yes"
    elif kind=="total" and (out in ("over","больше") or out.startswith(("over","больше"))):
     try: point=round(float(line.get("argument")),2)
     except (TypeError,ValueError): continue
     key={1.5:"total_over_1_5",2.5:"total_over_2_5",3.5:"total_over_3_5"}.get(point)
    elif kind=="tt" and (out in ("over","больше") or out.startswith(("over","больше"))):
     try: point=round(float(line.get("argument")),2)
     except (TypeError,ValueError): continue
     team=None
     if "home" in tokens or "team_1" in str(slug).lower() or "home" in name or "хозяев" in name or "team 1" in name or "команды 1" in name or "команда 1" in name: team="home"
     elif "away" in tokens or "team_2" in str(slug).lower() or "away" in name or "гостей" in name or "team 2" in name or "команды 2" in name or "команда 2" in name: team="away"
     if team and point in (0.5,1.5,2.5,3.5): key=f"{team}_over_{str(point).replace('.','_')}"
    if key: found[key]=max(found[key] or 0,price)
 return found

def model_market_set(match):
 best=((match.get("odds") or {}).get("best") or {})
 found=empty()
 dc=best.get("doubleChance") or {}
 for side in ("1x","x2"):
  if isinstance(dc.get(side),(int,float)): found["dc_"+side]=dc[side]
 btts=best.get("btts") or {}
 if isinstance(btts.get("yes"),(int,float)): found["btts_yes"]=btts["yes"]
 for row in best.get("totals") or []:
  if row.get("side")=="over":
   key={1.5:"total_over_1_5",2.5:"total_over_2_5",3.5:"total_over_3_5"}.get(round(float(row.get("point",-1)),2))
   if key: found[key]=row.get("price")
 for row in best.get("teamTotals") or []:
  if row.get("side")=="over" and row.get("team") in ("home","away"):
   try: point=round(float(row.get("point")),2)
   except (TypeError,ValueError): continue
   if point in (0.5,1.5,2.5,3.5): found[f"{row['team']}_over_{str(point).replace('.','_')}"]=row.get("price")
 return found

def fetch_fixtures(session,targets,today):
 end=max((kickoff(m).date() for _,_,m in targets),default=today)
 base={"exclude_amateur":"true","view":"odds","with_bk_odds":"true","odds_stage":"all","bookmaker_ids":BOOKMAKER,"page_size":500,"sort":"asc","tz":"Etc/UTC"}
 fixtures=[]
 for start,finish in iter_date_windows(today,end,max_days=31):
  params={**base,"date_from":start.isoformat(),"date_to":finish.isoformat(),"page":1}
  response=session.get(URL,params=params,timeout=60)
  rows,pages=_response_matches(response); fixtures.extend(rows)
  for page in range(2,pages+1):
   response=session.get(URL,params={**params,"page":page},timeout=60)
   rows,_=_response_matches(response); fixtures.extend(rows)
 return fixtures

def main():
 key=os.getenv("API_SPORT_KEY","").strip()
 if not key: raise RuntimeError("API_SPORT_KEY missing from GitHub Secrets")
 targets=read_targets()
 session=requests.Session(); session.headers.update({"Authorization":key})
 fixtures=fetch_fixtures(session,targets,datetime.now(timezone.utc).date())
 by_comp={}
 overall={"eligible":len(targets),"matched":0,"with_any_market":0,"marketCoverage":{k:{"label":v,"fixtures":0} for k,v in MARKETS}}
 matched_rows=[]
 for code,name,match in targets:
  stats=by_comp.setdefault(code,{"competition":name,"eligible":0,"matched":0,"with_any_market":0,"marketCoverage":{k:{"label":v,"fixtures":0} for k,v in MARKETS}})
  stats["eligible"]+=1
  source=match_fixture(match,fixtures)
  if source is None: continue
  overall["matched"]+=1; stats["matched"]+=1
  prices=normalize_market_set(source)
  present=[k for k,v in prices.items() if v is not None]
  if present: overall["with_any_market"]+=1; stats["with_any_market"]+=1
  for k in present: overall["marketCoverage"][k]["fixtures"]+=1; stats["marketCoverage"][k]["fixtures"]+=1
  matched_rows.append({"competition":code,"date":kickoff(source).isoformat() if kickoff(source) else None,"home":(source.get("homeTeam") or {}).get("name"),"away":(source.get("awayTeam") or {}).get("name"),"providerFixtureId":source.get("id"),"markets":{k:prices[k] for k in present}})
 baseline={"eligible":len(targets),"with_any_market":0,"marketCoverage":{k:{"label":v,"fixtures":0} for k,v in MARKETS}}
 for _,_,match in targets:
  prices=model_market_set(match); present=[k for k,v in prices.items() if v is not None]
  if present: baseline["with_any_market"]+=1
  for k in present: baseline["marketCoverage"][k]["fixtures"]+=1
 report={"generatedAt":datetime.now(timezone.utc).isoformat(),"provider":"API-Sport / MelBet","providerFixtureCount":len(fixtures),"melbet":overall,"betboomStoredBaseline":baseline,"byCompetition":by_comp,"matchedFixtures":matched_rows,"notes":["Read-only: does not change model feeds, app, production bookmaker, or filters.","Counts exact active full-time prematch prices for the 14 approved selections.","Fixture matching uses existing team-name and kickoff matching rules.","BetBoom baseline is the currently stored feed snapshot; its timestamp may differ from live MelBet pull."]}
 out=ROOT/"artifacts"; out.mkdir(exist_ok=True)
 (out/"melbet-odds-coverage.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
 md=render(report); (out/"melbet-odds-coverage.md").write_text(md,encoding="utf-8")
 summary=os.getenv("GITHUB_STEP_SUMMARY")
 if summary:
  with open(summary,"a",encoding="utf-8") as f: f.write(md)
 print(json.dumps({"provider":report["provider"],"providerFixtures":len(fixtures),"eligible":overall["eligible"],"matched":overall["matched"],"withAnyMarket":overall["with_any_market"],"betboomStoredWithAnyMarket":baseline["with_any_market"],"byCompetition":{k:{x:y for x,y in v.items() if x!="marketCoverage"} for k,v in by_comp.items()}},ensure_ascii=False))

def render(r):
 rows=["# MelBet — покрытие коэффициентами","",f"Сформирован: {r['generatedAt']}","","| Турнир | Матчей в выборке | Сопоставлено с MelBet | Есть нужный рынок |","|---|---:|---:|---:|"]
 for code,v in sorted(r["byCompetition"].items()): rows.append(f"| {v['competition']} ({code}) | {v['eligible']} | {v['matched']} | {v['with_any_market']} |")
 rows += [f"| **Всего** | **{r['melbet']['eligible']}** | **{r['melbet']['matched']}** | **{r['melbet']['with_any_market']}** |","","## Покрытие 14 рынков","","| Рынок | MelBet: матчей с ценой | BetBoom: матчей с сохранённой ценой |","|---|---:|---:|"]
 for k,label in MARKETS: rows.append(f"| {label} | {r['melbet']['marketCoverage'][k]['fixtures']} | {r['betboomStoredBaseline']['marketCoverage'][k]['fixtures']} |")
 rows += ["","## Покрытие по турнирам",""]
 for code,v in sorted(r["byCompetition"].items()):
  rows += [f"### {v['competition']} ({code})","","| Рынок | Матчей с коэффициентом |","|---|---:|"]
  for k,label in MARKETS: rows.append(f"| {label} | {v['marketCoverage'][k]['fixtures']} |")
  rows.append("")
 rows += ["## Методика",""]+["- "+x for x in r["notes"]]
 return "\n".join(rows)+"\n"

if __name__=="__main__": main()
