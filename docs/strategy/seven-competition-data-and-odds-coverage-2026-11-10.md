# Seven-competition data and BetBoom coverage audit — 2026-11-10

> **Historical coverage snapshot:** the 2026-11-11 audit supersedes its old 138/156 fixture match rate. After adding cross-provider French/Spanish club aliases, the current five-league sample matches 156/156 fixtures and has 96 BetBoom boards. See [current audit](./seven-competition-audit-2026-11-11.md).


## Executive status

The seven-competition xG/model research set is complete for ENG, ESP, ITA, GER, FRA, UCL and UEL. A separate calibration report records the chronological all-market backtest and calibration results:

- [Seven-competition probability calibration report](./seven-competition-calibration-report-2026-11-10.md)
- Historical xG matches: 10,031 across all seven competitions.
- Model predictions: 9,798.
- Holdout: 619 matches from 2026-07-01.
- Markets evaluated: 17.
- Calibration remains research-only; no fitted parameters have been promoted to production.

This audit covers the prospective BetBoom archive and current quote coverage. It does not claim historical odds coverage for the 10,031 xG matches.

## Domestic leagues — current 30-day collection

The domestic updater now requests a 30-day window and reports coverage only against fixtures in that same window. Latest successful refresh: 2026-11-09 22:05 UTC.

| Competition | Eligible fixtures | Matched to API-Sport | Fixtures with selected BetBoom quotes | Team-total quotes |
|---|---:|---:|---:|---:|
| ENG — Premier League | 40 | 40 | 20 | 0 |
| ESP — La Liga | 32 | 29 | 18 | 0 |
| ITA — Serie A | 30 | 30 | 20 | 0 |
| GER — Bundesliga | 27 | 27 | 18 | 0 |
| FRA — Ligue 1 | 27 | 12 | 7 | 0 |
| **Total** | **156** | **138** | **83** | **0** |

Aggregate updater diagnostics:

- 156 eligible fixtures in the 30-day window.
- 138 matched to provider fixtures.
- 83 fixtures received supported BetBoom prices.
- 55 matched fixtures had no supported BetBoom quote set.
- 18 eligible fixtures were not matched to an API-Sport fixture.

Latest domestic archive capture appended 1,017 timestamped records:

| League | Snapshot rows |
|---|---:|
| ENG | 260 |
| ESP | 218 |
| ITA | 248 |
| GER | 204 |
| FRA | 87 |
| **Total** | **1,017** |

Market distribution across those rows:

| Market family | Rows |
|---|---:|
| Double chance | 249 |
| Handicap (zero line only) | 146 |
| Match total | 456 |
| Both teams to score | 166 |
| Team total | **0** |

The current app feed contains prices for 83 domestic fixtures; none has a parsed team-total quote.

## UEFA — UCL and UEL

Latest successful UEFA capture: 2026-11-09 22:05 UTC.

- Upcoming UEFA fixtures in isolated cache: 144 (72 UCL, 72 UEL).
- API-Sport fixture matches: 44.
- Active prematch BetBoom boards: 28.
- Model predictions: 44 total (31 UCL, 13 UEL).
- Snapshot rows appended: 628.

| Competition | Snapshot rows | Model predictions |
|---|---:|---:|
| UCL | 351 | 31 |
| UEL | 277 | 13 |
| **Total** | **628** | **44** |

UEFA market distribution:

| Market family | Rows |
|---|---:|
| BTTS | 56 |
| Double chance | 84 |
| Handicap | 52 |
| Match total OVER | 80 |
| Match total UNDER (reference only) | 80 |
| Team total OVER | 138 |
| Team total UNDER (reference only) | 138 |
| **Total** | **628** |

Team totals are therefore present in the UEFA archive for both competitions.

## Team-total investigation and conclusion

The parser supports the BetBoom team-total forms observed in the source, including `total_team_1`, `total_team_2`, `team_goals_home` and `team_goals_away`. It retains exact line and market-group metadata, and excludes first-half, second-half and other non-full-time markets. Regression tests cover these forms.

In the latest API-Sport response, diagnostics found 549 active full-time team-total market entries across the 30-day provider fixture set and 2,746 parsed team-total quote rows overall. However, none of the 138 provider fixtures matched to the five domestic target leagues carried a team-total quote. No active full-time team-like market remained unclassified in the diagnostic sample.

**Conclusion:** the missing domestic team totals are not explained by the parser failing to recognize the currently observed full-time team-total naming patterns. For the current five-league matched fixture set, this is a source-side BetBoom market-availability gap. We should not fabricate prices, infer them from match totals, or weaken the freshness/market filters. The scheduled collector will continue checking new boards; if BetBoom begins publishing domestic team totals for these fixtures, they can enter the archive through the existing parser.

## Operational safeguards

- Domestic BetBoom refresh and snapshot collection: scheduled every 6 hours.
- UEFA BetBoom collection, paper-signal evaluation, settlement and app-feed build: scheduled every 3 hours.
- App-visible odds must remain source-fresh (maximum age 300 seconds); stale prices are omitted.
- UNDER prices are reference-only where configured; BTTS NO is excluded from AI candidate selection.
- No automatic real-money bets are placed.
- No ROI or betting-market superiority is claimed. Historical BetBoom prices are not available for the full xG backtest history, so a valid retrospective edge/EV/ROI study across all seven competitions is not yet possible.

## Next validation gate

Continue accumulating timestamped snapshots and settled paper results. Before enabling domestic team-total candidates, require actual BetBoom quotes on matched domestic fixtures, exact market/line parsing, fresh source timestamps, and a separate chronological paper evaluation. Calibration parameters remain research-only until independently replicated.
