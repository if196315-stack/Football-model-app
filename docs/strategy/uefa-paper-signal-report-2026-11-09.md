# UEFA BetBoom paper-signal report — 2026-11-09

## Status

**18 unique paper-only BET entries** passed the current engineering filters in one timestamped capture. These are not placed bets and are not evidence of realized profit.

- Capture time: 2026-11-09T20:13:32.391143+00:00
- Competition split: UCL 11, UEL 7
- Market split: btts: 4; team_total_over: 10; match_total_over: 2; double_chance: 2
- Maximum bookmaker-board age among entries: 24.2 seconds (limit 300 seconds)
- Model: `uefa-xg-poisson-v1`; xG history cutoff precedes each match date
- Thresholds: model probability ≥60%, edge ≥5 percentage points, EV ≥5%, source-side prematch board age ≤5 minutes

## Summary of model estimates

| Metric | Result |
|---|---:|
| Unique paper entries | 18 |
| Mean modeled edge | 15.8% |
| Median modeled edge | 13.3% |
| Mean modeled EV | 21.5% |
| Median modeled EV | 17.2% |

These are model-implied values at the captured prices, not realized returns. The 2 double-chance entries use a conservative raw implied-probability edge because 1X/X2 outcomes overlap; the other markets use two-way overround removal from matched complementary prices within the same provider market group.

## Qualifying paper entries

| League | Kickoff | Match | Market | Odds | Model p | Fair/reference p | Edge | EV |
|---|---|---|---|---:|---:|---:|---:|---:|
| UCL | 2026-11-24 | Manchester City — Napoli | ОЗ — да | 1.73 | 64.6% | 53.9% | +10.7% | +11.7% |
| UCL | 2026-11-24 | Manchester City — Napoli | ИТБ гости 0.5 | 1.62 | 70.1% | 57.3% | +12.9% | +13.6% |
| UCL | 2026-11-24 | Bodø/Glimt — LASK | ИТБ гости 0.5 | 1.24 | 85.5% | 74.2% | +11.3% | +6.0% |
| UEL | 2026-11-26 | Sunderland — Jagiellonia Białystok | ИТБ гости 0.5 | 1.83 | 62.2% | 50.4% | +11.8% | +13.9% |
| UCL | 2026-11-25 | Sabah FK — Barcelona | ОЗ — да | 1.58 | 72.4% | 59.1% | +13.3% | +14.3% |
| UCL | 2026-11-25 | Sabah FK — Barcelona | ИТБ хозяева 0.5 | 1.55 | 76.6% | 59.8% | +16.8% | +18.7% |
| UEL | 2026-11-26 | Union St.Gilloise — Lech Poznań | ТБ 2.5 | 1.41 | 84.8% | 65.0% | +19.7% | +19.5% |
| UEL | 2026-11-26 | Union St.Gilloise — Lech Poznań | ТБ 3.5 | 2.11 | 69.0% | 43.4% | +25.6% | +45.6% |
| UEL | 2026-11-26 | Union St.Gilloise — Lech Poznań | ИТБ хозяева 1.5 | 1.34 | 88.3% | 68.2% | +20.1% | +18.3% |
| UEL | 2026-11-26 | Union St.Gilloise — Lech Poznań | ИТБ хозяева 2.5 | 2.16 | 71.3% | 42.7% | +28.6% | +54.1% |
| UCL | 2026-11-25 | Atlético Madrid — Viking | ОЗ — да | 2.13 | 64.9% | 43.8% | +21.1% | +38.2% |
| UCL | 2026-11-25 | Atlético Madrid — Viking | ИТБ гости 0.5 | 2.06 | 72.8% | 45.1% | +27.8% | +50.0% |
| UCL | 2026-11-24 | Arsenal — Borussia Dortmund | ОЗ — да | 1.86 | 60.5% | 50.3% | +10.2% | +12.5% |
| UCL | 2026-11-24 | Arsenal — Borussia Dortmund | ИТБ гости 0.5 | 1.66 | 70.6% | 55.9% | +14.8% | +17.2% |
| UEL | 2026-11-26 | Beşiktaş — Hapoel Beer Sheva | ИТБ гости 0.5 | 1.61 | 69.4% | 57.3% | +12.1% | +11.7% |
| UEL | 2026-11-26 | Torreense — Celtic | DC 1X | 2.04 | 61.1% | 49.0% | +12.1% | +24.7% |
| UCL | 2026-11-24 | Galatasaray — Aston Villa | DC X2 | 1.48 | 74.3% | 67.6% | +6.7% | +10.0% |
| UCL | 2026-11-24 | Galatasaray — Aston Villa | ИТБ гости 1.5 | 1.79 | 60.1% | 51.8% | +8.3% | +7.5% |

## Interpretation and restrictions

- This is a forward paper-signal cohort. These fixtures had not settled at capture time; there is no hit rate, ROI, yield, CLV or drawdown result yet.
- Repeated snapshots are retained in the decision log, but this table counts only the first qualifying BET per fixture/market/line/team/outcome. That prevents duplicate paper stakes.
- UNDER and DC 12 are reference-only prices used for comparison; they are not selectable markets.
- The earlier 107 preliminary BET entries were invalidated because the parser did not retain provider-market identity and did not fully exclude half-time markets. They are not included here.
- Model probabilities are experimental and not yet validated for staking. The 500 historical + 500 paper signals per market requirement remains unmet. No automatic bet placement is enabled.

## Source files

- Raw timestamped odds: `artifacts/uefa-betboom-odds-snapshots.jsonl`
- Full BET/WAIT/REJECT decision history: `artifacts/uefa-paper-signals.jsonl`
- Deduplicated paper entries: `artifacts/uefa-paper-bets.jsonl`
- Model xG history: `artifacts/uefa-xg-history.json`
- Market-mapping audit: `docs/strategy/uefa-paper-signal-audit-2026-11-10.md`
