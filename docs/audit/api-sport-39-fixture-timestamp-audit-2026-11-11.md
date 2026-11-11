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


## Important limitation found in the PitchAPI production path

A subsequent source review found that PitchAPI normalization preserves both provider fields:
- `date` → cached `date`
- `time_utc` → cached `timeUtc`

However, `backend/update_data_pitchapi.py` builds the app feed using only `row["date"]` for `utcDate`. It does not pass `row["timeUtc"]` into `docs/data.json`. Thus the app snapshot is date-only by construction in this path, even when the upstream PitchAPI cache may contain a kickoff time.

This means the previous timestamp audit did **not** compare PitchAPI raw kickoff timestamps. It compared API-Sport raw timestamps against PitchAPI's date-only app snapshot. Its statement that PitchAPI has no time/offset is therefore only true of the published app snapshot, not established for the upstream PitchAPI response/cache.

The current evidence still cannot establish the root cause. A conclusive provider-to-provider comparison requires retrieving the original PitchAPI `time_utc` values for these 39 fixture IDs from the persisted `backend/cache/pitchapi_history.json` or from a narrowly scoped PitchAPI fixture listing, and comparing them in UTC with API-Sport `startTimestamp`. The persisted cache is maintained by GitHub Actions cache and is not committed to the repository, so it was not available in this read-only repository inspection.

No production code was changed. Do not infer a timezone defect or change matching rules until the raw PitchAPI `time_utc` values are actually compared.


## Raw PitchAPI cache follow-up (no provider API calls)

The Actions workflow restored the existing default-branch cache `pitchapi-history-Linux-main-42890641451` and extracted the current-season rows (`2026/2027`, status `not_started`) for the 39 target fixtures. No PitchAPI or API-Sport HTTP requests were made. Cache freshness: `2026-11-10T20:25:51Z`.

- 39/39 targets have exactly one current-season PitchAPI cache row.
- Serie A: all 30 rows have `date` on 2027-05-16 / 23 / 30 and `timeUtc` at 16:30 UTC on the corresponding same date.
- Bundesliga: all 9 rows have `date` 2027-03-02 and `timeUtc` at 19:30 UTC on that same date.
- API-Sport's previously captured timestamps remain 13:00 UTC on the preceding calendar day for Serie A, and 10:00 UTC on the following calendar day for Bundesliga.

Thus, the date offsets are present even when comparing the providers' raw UTC fields; they are **not** an artifact of the app-feed dropping PitchAPI's `timeUtc`. However, both providers assign one identical timestamp to every fixture in each round/date group. This strongly indicates placeholder/round-level scheduling metadata rather than confirmed individual kickoff times, but this diagnostic cannot establish either provider's scheduling semantics or which calendar date is authoritative.

The earlier section's finding that the app feed drops `timeUtc` remains correct as a code-path observation, but it was not the explanation for the provider discrepancy. The correct conclusion is: both providers' current season snapshots disagree on provisional round dates, and neither set of uniform round-level times should be treated as confirmed match kickoff times.

Workflow: [PitchAPI cached raw kickoff audit 42930280973](https://github.com/if196315-stack/Football-model-app/actions/runs/42930280973). Cache restore succeeded; audit succeeded; API requests: 0.
