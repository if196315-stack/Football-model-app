# API-Sport/PitchAPI raw kickoff timestamp audit — 2026-11-11

## Scope

Read-only follow-up for the 39 future fixtures whose calendar dates differed by one day in the earlier pair-ID audit:

- 30 Serie A fixtures: PitchAPI dates 2027-05-16, 2027-05-23 and 2027-05-30; API-Sport dates one day earlier.
- 9 Bundesliga fixtures: PitchAPI date 2027-03-02; API-Sport date 2027-03-03.

The diagnostic queried only two API-Sport date windows:
- Serie A: 2027-05-15 through 2027-05-30, tournament ID 23.
- Bundesliga: 2027-03-02 through 2027-03-03, tournament ID 35.

It selected the previously reviewed ordered home/away team-ID pairs and captured raw `dateEvent`, `startTimestamp`, normalized UTC timestamp, tournament and team IDs. It also read the checked-in PitchAPI calendar snapshots for the corresponding scheduled fixture rows. No fixture detail endpoints were called.

Workflow: [42929636877](https://github.com/if196315-stack/Football-model-app/actions/runs/42929636877)  
Diagnostic commit: `aee7945e2a7ecc9dc603e182b2ec58be215fd807`  
Conclusion: success. API requests including usage check: **7**. Both date windows completed without pagination truncation. Account usage before the run reported 43,371 requests remaining (daily limit field was not supplied by the provider).

## Results

All **39/39** expected ordered team-ID pairs were found exactly once in the selected API-Sport windows. No target had multiple matching candidates.

| Competition | Fixtures | PitchAPI scheduled date | API-Sport dateEvent | API-Sport normalized UTC |
|---|---:|---|---|---|
| Serie A | 10 | 2027-05-16 | 2027-05-15 | 2027-05-15 13:00 UTC |
| Serie A | 10 | 2027-05-23 | 2027-05-22 | 2027-05-22 13:00 UTC |
| Serie A | 10 | 2027-05-30 | 2027-05-29 | 2027-05-29 13:00 UTC |
| Bundesliga | 9 | 2027-03-02 | 2027-03-03 | 2027-03-03 10:00 UTC |

The PitchAPI snapshot stores all 39 affected scheduled fixtures as date-only strings (`YYYY-MM-DD`), with no kickoff time or UTC offset. The API-Sport rows contain numeric `startTimestamp` values; those normalize to 13:00 UTC for every affected Serie A fixture and 10:00 UTC for every affected Bundesliga fixture.

## Interpretation

1. The one-day calendar-date differences are confirmed in the current checked-in snapshots and current API-Sport responses for these 39 ordered team pairs.
2. A timezone conversion is **not established as the cause**. PitchAPI's affected values are date-only and carry no time or offset to convert. API-Sport's `dateEvent` agrees with the UTC calendar date derived from its `startTimestamp`.
3. API-Sport assigns exactly the same timestamp to every fixture within each affected round/date group (all 10 Serie A matches at 13:00 UTC; all 9 Bundesliga matches at 10:00 UTC). This looks like round-level/default scheduling metadata rather than independently verified per-match kickoff times, but the provider's semantics are not documented by this diagnostic. Do not treat these values as confirmed kickoff times.
4. The audit cannot determine which provider's calendar date is authoritative, whether either provider has a provisional round date, or whether the fixtures will be rescheduled.
5. This confirms candidate fixture identity/date metadata only. It does not establish odds availability, prematch acceptance, CLV, ROI, or profitability.

## Production safety

Read-only diagnostic only. No production matcher, source selection, ingestion, odds parsing, prematch-stage filter, probabilities, paper bets, or app files were changed. Do not relax date/time matching based on these results. A safe production change would require authoritative fixture scheduling evidence and independent negative-case tests first.
