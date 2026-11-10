# Seven-competition API-Sport / BetBoom coverage — full upcoming-fixture pass

**Snapshot date:** 2026-11-11 UTC  
**Source calendars:** `docs/data.json` and `docs/uefa-data.json` on the diagnostic branch  
**Workflow:** [42919626540](https://github.com/if196315-stack/Football-model-app/actions/runs/42919626540)  
**Job:** `diagnose` / `130194390925`

## Scope and method

Enumerated every future `SCHEDULED` or `TIMED` fixture in the seven target competition feeds: Premier League (PL), La Liga (PD), Serie A (SA), Bundesliga (BL1), Ligue 1 (FL1), Champions League (UCL), and Europa League (UEL). Deduplicated the two source feeds and excluded the seven fixture/date pairs already checked in workflow run 42919194097.

For each remaining fixture, queried API-Sport for all pages on the fixture date (maximum five pages per date; no date reached the page cap), applied the same production `match_fixture` matching function, fetched the detail payload for each accepted match, and passed that payload through the production `parse_odds` parser. This was a read-only diagnostic: it did not write odds snapshots, alter predictions, or modify production ingestion.

## Results

| Competition | Remaining fixtures checked | API-Sport fixture matches | BetBoom boards |
|---|---:|---:|---:|
| Premier League (PL) | 279 | 278 | 19 |
| La Liga (PD) | 259 | 258 | 19 |
| Serie A (SA) | 269 | 235 | 19 |
| Bundesliga (BL1) | 224 | 200 | 17 |
| Ligue 1 (FL1) | 215 | 214 | 17 |
| Champions League (UCL) | 71 | 65 | 15 |
| Europa League (UEL) | 71 | 64 | 15 |
| **Total** | **1,388** | **1,314** | **121** |

- Exact fixture matches under the current production team-name/kickoff matcher: **1,314 / 1,388**.
- No exact match found: **74**.
- BetBoom boards found: **121**.
- Open market categories across those boards: **1,025**.
- All **1,025** returned market-category records had stage `unspecified`; no market was explicitly tagged `prematch`.
- Production parser accepted quotes: **0**.
- API requests: **1,464** (including the account-usage request).
- Date pages truncated at the configured maximum: **0**.
- All seven target competitions were covered.

## Interpretation and limitations

1. Board presence is not proof of prematch eligibility. All 121 boards had market-stage metadata unspecified in the returned detail payload. The production parser correctly rejected these quotes. **Do not weaken the prematch-stage filter** to turn these into accepted prices.
2. The 1,314 matches are accepted by the existing production matcher, which compares kickoff date/time and home/away team-name similarity. It does not independently validate API-Sport's competition/league identifier. Therefore this is a fixture-level match count, not an independently audited league-ID mapping.
3. The 1,388 count covers future scheduled fixtures present in the two checked-in calendar snapshots at the snapshot date, excluding the seven previously tested fixtures. It is not a historical all-seasons fixture census.
4. The results establish neither historical nor future betting profitability. There is still no basis here to claim ROI, CLV, or settled paper-bet performance.
5. No production source, filters, model probabilities, stake rules, odds snapshots, or paper bets were changed.

## Conclusion

The complete remaining upcoming-fixture pass finished successfully and covered all 1,388 untested fixtures. API-Sport returned 1,314 fixture-level matches and 121 BetBoom boards, but **zero quotes passed the production parser** because prematch stage was not explicitly supplied. Preserve the existing safety filter. Investigate the provider's stage metadata separately before making any ingestion change.
