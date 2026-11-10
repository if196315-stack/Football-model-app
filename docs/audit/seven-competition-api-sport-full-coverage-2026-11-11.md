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


## Independent API-Sport tournament-ID cross-check

**Workflow:** [42923945771](https://github.com/if196315-stack/Football-model-app/actions/runs/42923945771)  
**Expanded unmatched-fixture classification:** [42924957647](https://github.com/if196315-stack/Football-model-app/actions/runs/42924957647)

The initial fixture matcher does not validate competition identity. A separate read-only pass therefore queried the seven API-Sport tournament IDs independently (PL 17, PD 8, SA 23, BL1 35, FL1 34, UCL 7, UEL 679), scanning all pages in each date window. The complete scan returned 50,267 API-Sport rows across 106 requests; no date window hit the 30-page safety ceiling.

| Competition | API-Sport tournament ID | Target fixtures | Matched in expected tournament | Coverage |
|---|---:|---:|---:|---:|
| Premier League | 17 | 280 | 279 | 99.6% |
| La Liga | 8 | 260 | 260 | 100.0% |
| Serie A | 23 | 270 | 236 | 87.4% |
| Bundesliga | 35 | 225 | 202 | 89.8% |
| Ligue 1 | 34 | 216 | 215 | 99.5% |
| Champions League | 7 | 72 | 68 | 94.4% |
| Europa League | 679 | 72 | 65 | 90.3% |
| **Total** | — | **1,395** | **1,325** | **95.0%** |

The expanded classification then checked every one of the 70 remaining unmatched targets against all 50,267 API-Sport rows returned by the date-window scans, without restricting the search to the expected tournament. **None of the 70 had a candidate match in another API-Sport tournament in those responses** (unmatchedWithCandidateInAnotherTournament = 0). Thus the remaining misses are not explained by the fixtures simply being assigned to a different tournament ID in the scanned data.

Unmatched targets by competition: PL 1, PD 0, SA 34, BL1 23, FL1 1, UCL 4, UEL 7. This means the fixtures were not found in the API-Sport rows retrieved for their scanned date windows; it does not prove that API-Sport never lists them, nor distinguish provider omissions from schedule/date/team-name discrepancies in the source calendars.

Both cross-check workflows completed successfully. The expanded diagnostic is read-only; production data sources, matching rules, odds filters, predictions, snapshots, and paper bets were not changed. The original 1,388-fixture board scan remains a separate result: 121 BetBoom boards, 1,025 market-category records with stage unspecified, and zero quotes accepted by the production prematch parser. Do not relax that parser filter.
