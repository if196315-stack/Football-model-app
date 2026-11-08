# Alternative football-data provider research

Date checked: 2026-11-08

## Decision boundary

Soccer365 remains the agreed target source for fixtures, results, competition metadata and match statistics. The Odds API remains the sole bookmaker-odds source. This note records contingency options only; it does not authorize a source switch, mixed-provider history, or production integration.

The existing production pipeline is unchanged. Any fallback must pass the same competition/season, xG coverage, identity, historical completeness, access-policy and walk-forward backtest gates as Soccer365.

## Candidate 1: SStats.net Football API

Public documentation:
- https://api.sstats.net/docs/
- https://sstats.net/api

Documented capabilities:
- `GET /Leagues`
- `GET /Games/list` (the quick-start example filters with `LeagueId` and `Year`)
- `GET /Games/{id}`
- `GET /Games/glicko/{id}` (the quick-start describes this as Glicko 2 and xG data)
- New matches are said to be added several times per day; pre-match information for a match is said to refresh every three hours.
- The docs state a free service with unauthenticated limits of 300 requests/minute globally and 30/minute per IP; registration provides a personal free API key.

Unverified / blocking:
- The publicly readable documentation reviewed here does not provide a competition-by-season xG coverage matrix or per-season paired-xG counts.
- The exact historical depth and xG fill rate for ENG, ESP, ITA, GER and FRA have not been measured.
- No authenticated or unauthenticated live API response was captured during this review. Endpoint existence is not evidence that all required seasons or match fields are populated.
- No API key was created or stored.

Assessment: easy to investigate, but insufficient public coverage transparency to qualify as a data-source replacement yet.

## Candidate 2: Soccer Data API (soccer-data.co)

Public documentation:
- https://soccer-data.co/docs/reference/coverage/leagues
- https://soccer-data.co/docs/reference/coverage/league
- https://soccer-data.co/docs/concepts/rate-limits

Documented capabilities:
- A competition coverage endpoint lists competitions, loaded season ranges, total finished matches and data-type availability.
- A season coverage endpoint reports season-by-season finished/total match counts and xG `covered_events` plus `coverage_pct`. This is materially better for validating the exact xG sample before integration.
- The docs' Premier League 2025/26 example reports 380 finished matches and xG on 380 (100%). This is a documentation example, not a verified live response and not evidence for the other four leagues.
- The docs show 204 competitions in the example coverage response.
- Free plan: 50 requests/day and 10/minute; paid plans raise quotas. Odds endpoints require paid access. The product recommends caching server-side.
- Requests use an API key in the `X-Api-Key` header.

Unverified / blocking:
- Actual live coverage for all five target leagues and each needed season has not been queried.
- Actual xG coverage percentages and historical depth are therefore unknown.
- Pricing, terms, retention and personal-use suitability should be checked before relying on it.
- No API key was created or stored.

Assessment: strongest documented fallback for a controlled coverage audit because it exposes season-level xG counts and percentages. The free quota appears adequate for a small initial coverage probe, but not yet verified against live data.

## Required fallback qualification test

If Soccer365 cannot be used through a permitted, stable method, qualify one fallback in isolation:

1. Enumerate the five target competitions and their exact season IDs.
2. Capture season coverage for every intended season; record finished matches, expected match totals, xG-covered matches and xG coverage percentage.
3. Fetch a small sample of match records and confirm stable match/team/competition IDs, UTC kickoff, final scores, nullable paired xG and corrections/reschedules.
4. Compare provider match identity and scores against independently checkable public fixtures; quarantine ambiguous matches.
5. Import only into a separate test archive, run the existing quality and walk-forward backtest tools, and compare calibration by competition and season.
6. Keep The Odds API separate and validate strict fixture matching independently.
7. Do not mix fallback-provider records with Soccer365 or legacy-provider histories without an explicit, reviewed provenance-aware migration.

## Current conclusion

No alternative provider is qualified. Soccer365 remains the target; SStats is a discovery candidate with insufficient published xG coverage detail; Soccer Data is a more promising contingency because its docs expose season-level xG coverage counts, but live coverage and commercial terms still need verification. No production code or workflow has been switched.
