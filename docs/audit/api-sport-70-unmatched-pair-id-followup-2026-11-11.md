# API-Sport pair-ID follow-up for 70 unmatched fixtures — 2026-11-11

## Scope and method

Read-only follow-up to the seven-competition API-Sport tournament-ID cross-check. The diagnostic re-examined all 70 PitchAPI fixtures that had not matched by the original kickoff-date/time + team-name matcher.

For each target, it resolved the two team names to API-Sport team IDs in the expected tournament, then searched the already downloaded API-Sport fixture rows for the exact ordered home/away team-ID pair, ignoring kickoff date/time.

Workflow: [42927594539](https://github.com/if196315-stack/Football-model-app/actions/runs/42927594539)  
Diagnostic commit: `4c12b5ffeba063a729cca62a70a46f08f32f794f`  
Job conclusion: success. API-Sport rows scanned: 50,267. No truncated date windows. Read-only; no production sources or filters changed.

## Results

All 70/70 target pairs were found in the expected API-Sport tournament after resolving team-name aliases.

| Competition | Targets | Pair found | Same calendar date | Different date |
|---|---:|---:|---:|---:|
| Premier League (PL) | 1 | 1 | 1 | 0 |
| Serie A (SA) | 34 | 34 | 4 | 30 |
| Bundesliga (BL1) | 23 | 23 | 14 | 9 |
| Ligue 1 (FL1) | 1 | 1 | 1 | 0 |
| Champions League (UCL) | 4 | 4 | 4 | 0 |
| Europa League (UEL) | 7 | 7 | 7 | 0 |
| **Total** | **70** | **70** | **31** | **39** |

No target lacked a candidate home-team ID or away-team ID in its expected tournament. No pair was absent from the scanned rows.

### Date differences among the 39 pairs

- Serie A: 30 pairs are exactly one day earlier in API-Sport than in PitchAPI:
  - PitchAPI 2027-05-16 → API-Sport 2027-05-15: 10 fixtures.
  - PitchAPI 2027-05-23 → API-Sport 2027-05-22: 10 fixtures.
  - PitchAPI 2027-05-30 → API-Sport 2027-05-29: 10 fixtures.
- Bundesliga: 8 pairs are one day later in API-Sport:
  - PitchAPI 2027-03-02 → API-Sport 2027-03-03.
- Bundesliga: one additional pair, Schalke 04–1. FC Köln, is found on 2027-02-06 in API-Sport while the PitchAPI target date is 2027-03-02 (24 days earlier in API-Sport). This is a pair-only match, not a date-confirmed match; it warrants separate schedule verification.

The other 31 pairs have the same calendar date in both sources. The pair search ignores kickoff time, so same-date results do not by themselves prove kickoff-time agreement.

## Interpretation and limitations

1. The earlier 70 unmatched cases are not evidence that API-Sport lacks these team pairings. All 70 ordered pairs occur in the downloaded rows under the expected tournament IDs.
2. For 31 targets, the pair and calendar date agree; the original matcher may have failed because of kickoff-time or exact team-name representation differences, or duplicate/provider row details.
3. For 38 targets, the observed pair has a one-day calendar-date offset (30 Serie A and 8 Bundesliga). The common one-day pattern across whole Serie A rounds suggests a systematic calendar/date convention discrepancy between the two feeds, but this diagnostic alone does not establish which provider's date is authoritative.
4. The Schalke–Köln result is a larger 24-day discrepancy and must not be treated as a confirmed reschedule without independent verification.
5. Pair identity was inferred using team-name similarity thresholds and team IDs scoped to the expected tournament. It is stronger than free-text name matching, but not an independent official fixture confirmation.
6. The search was limited to the 50,267 API-Sport rows fetched for the scanned date windows. It does not prove API-Sport never lists a fixture outside those windows.
7. This is a future-fixture catalogue coverage diagnostic, not historical odds coverage, and it says nothing about ROI, CLV, or profitability.

## Production safety

No production ingestion, source selection, probabilities, odds parser, prematch-stage filter, or matching behavior was changed. In particular, do not relax the production prematch filter based on these findings. The existing strict prematch acceptance rule remains in force.

## Per-fixture date discrepancies

| Competition | PitchAPI date | API-Sport date | PitchAPI fixture | API-Sport fixture ID |
|---|---|---|---|---:|
| BL1 | 2027-03-02 | 2027-03-03 | Bayer Leverkusen – Hoffenheim | 16434225 |
| BL1 | 2027-03-02 | 2027-03-03 | Bayern München – Freiburg | 16434223 |
| BL1 | 2027-03-02 | 2027-03-03 | Borussia Mönchengladbach – VfB Stuttgart | 16434226 |
| BL1 | 2027-03-02 | 2027-03-03 | Eintracht Frankfurt – Borussia Dortmund | 16434235 |
| BL1 | 2027-03-02 | 2027-03-03 | Elversberg – RB Leipzig | 16434229 |
| BL1 | 2027-03-02 | 2027-03-03 | Hamburger SV – Paderborn | 16434234 |
| BL1 | 2027-03-02 | 2027-02-06 | Schalke 04 – 1. FC Köln | 16434198 |
| BL1 | 2027-03-02 | 2027-03-03 | Union Berlin – Augsburg | 16434233 |
| BL1 | 2027-03-02 | 2027-03-03 | Werder Bremen – Mainz 05 | 16434227 |
| SA | 2027-05-16 | 2027-05-15 | Bologna – Frosinone | 16285250 |
| SA | 2027-05-16 | 2027-05-15 | Cagliari – Torino | 16285253 |
| SA | 2027-05-16 | 2027-05-15 | Como – Atalanta | 16285251 |
| SA | 2027-05-16 | 2027-05-15 | Juventus – Inter | 16285254 |
| SA | 2027-05-16 | 2027-05-15 | Lazio – Udinese | 16285255 |
| SA | 2027-05-16 | 2027-05-15 | Lecce – Fiorentina | 16285256 |
| SA | 2027-05-16 | 2027-05-15 | Milan – Roma | 16285257 |
| SA | 2027-05-16 | 2027-05-15 | Monza – Parma | 16285258 |
| SA | 2027-05-16 | 2027-05-15 | Napoli – Genoa | 16285259 |
| SA | 2027-05-16 | 2027-05-15 | Sassuolo – Venezia | 16285260 |
| SA | 2027-05-23 | 2027-05-22 | Atalanta – Lecce | 16285261 |
| SA | 2027-05-23 | 2027-05-22 | Fiorentina – Monza | 16285263 |
| SA | 2027-05-23 | 2027-05-22 | Frosinone – Cagliari | 16285264 |
| SA | 2027-05-23 | 2027-05-22 | Genoa – Bologna | 16285262 |
| SA | 2027-05-23 | 2027-05-22 | Inter – Lazio | 16285266 |
| SA | 2027-05-23 | 2027-05-22 | Parma – Juventus | 16285265 |
| SA | 2027-05-23 | 2027-05-22 | Roma – Como | 16285267 |
| SA | 2027-05-23 | 2027-05-22 | Torino – Napoli | 16285269 |
| SA | 2027-05-23 | 2027-05-22 | Udinese – Sassuolo | 16285268 |
| SA | 2027-05-23 | 2027-05-22 | Venezia – Milan | 16285271 |
| SA | 2027-05-30 | 2027-05-29 | Bologna – Parma | 16285270 |
| SA | 2027-05-30 | 2027-05-29 | Cagliari – Roma | 16285275 |
| SA | 2027-05-30 | 2027-05-29 | Como – Genoa | 16285273 |
| SA | 2027-05-30 | 2027-05-29 | Juventus – Frosinone | 16285278 |
| SA | 2027-05-30 | 2027-05-29 | Lazio – Fiorentina | 16285274 |
| SA | 2027-05-30 | 2027-05-29 | Lecce – Venezia | 16285285 |
| SA | 2027-05-30 | 2027-05-29 | Milan – Udinese | 16285283 |
| SA | 2027-05-30 | 2027-05-29 | Monza – Torino | 16285286 |
| SA | 2027-05-30 | 2027-05-29 | Napoli – Atalanta | 16285287 |
| SA | 2027-05-30 | 2027-05-29 | Sassuolo – Inter | 16285284 |


## Why the 31 same-date pairs were still rejected by the original matcher

A follow-up comparison applied the current production name-score thresholds to the best team candidates scoped to the expected tournament:

- Only **1 of the 31** same-calendar-date pairs clears the current name thresholds (`home >= 0.68`, `away >= 0.68`, combined score `>= 1.48`).
- **30 of 31** fall below at least one of those name thresholds. The recurring low-similarity aliases include Roma/AS Roma, Napoli/SSC Napoli, Milan/AC Milan, Paderborn/SC Paderborn 07, Elversberg/SV 07 Elversberg, Slavia Prague and Salzburg.
- The remaining Chelsea–Aston Villa pair has two API-Sport rows with the same team IDs and date. The production matcher deliberately rejects near-tied duplicate candidates, which explains why a pair can exist but still not be selected unambiguously.

This is a diagnostic explanation, not a recommendation to lower fuzzy-match thresholds. Lowering thresholds globally could create false positives, especially where similarly named reserve/youth teams exist. A safe future improvement would require a reviewed, competition-scoped alias map and explicit duplicate handling, validated against negative examples before any production change.

## Important correction to interpretation

The 31 same-date cases are **not** evidence that kickoff times agree. The pair-ID output retained only the date field, not the full raw kickoff timestamp. The 39 date-offset cases also require raw timestamp/time-zone verification before attributing the discrepancy to a provider calendar convention. No additional API-Sport detail calls were made in this follow-up, to avoid spending API budget. The date-offset pattern is observed in the downloaded fixture rows, but its cause is not yet proven.
