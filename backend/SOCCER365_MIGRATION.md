# MatchIQ: Soccer365 migration plan

## Agreed source boundary

- Soccer365 is the only source for football fixtures, competition metadata, results and match statistics.
- The Odds API remains the only source for bookmaker prices.
- Football-Data.org and Openplay are removed from the production data path after the Soccer365 replacement is validated.
- Do not merge Soccer365 and legacy-provider values into a single historical sample.

## Canonical match record

Every record must retain:
- stable Soccer365 match URL/ID;
- country, stable competition ID/name, season and competition type (league, domestic cup, international cup, friendly, national-team match);
- kickoff in UTC, status and final score;
- home/away team names and stable Soccer365 team IDs when available;
- source URL and retrieval timestamp;
- each statistic as nullable, with explicit availability/provenance.

Country is a presentation grouping only. Competition and match type remain separate model dimensions. League, cup and friendly histories must not be pooled indiscriminately.

## Model rules

- Rebuild team histories from Soccer365 match records only.
- Use full-match xG values as supplied by Soccer365; do not add first-half and second-half xG to full-match xG.
- Missing xG remains null. No zero-filling, provider substitution, or synthetic estimates.
- Predictions require a minimum sample and competition/type compatibility. If coverage is insufficient, show the match but suppress the forecast with a clear reason.
- Keep the existing Poisson model parameters only as a candidate baseline; recalibrate and backtest on Soccer365 history before retaining them.
- Keep the immutable prediction archive and its evaluation history. New archive keys must use Soccer365 IDs/URLs and must not collide with old Football-Data.org IDs.

## Odds integration

- Keep The Odds API unchanged as the bookmaker-price provider.
- Replace competition-code-only matching with strict matching on normalized home/away names, kickoff proximity and competition context.
- Do not attach a quote when multiple Soccer365 matches are plausible or the match type/competition is inconsistent.
- Preserve old quotes only when the exact Soccer365 match identity remains stable.

## Rollout gates

1. Confirm Soccer365 terms/robots and an allowed access method. Do not bypass Cloudflare, bot checks or access controls.
2. Validate access reliability and parser behavior against real pages from every intended competition type.
3. Verify coverage by country, competition, season, match status and xG availability. Missing coverage is reported, not guessed.
4. Build a Soccer365-only historical store and compare duplicate, score, date, team and xG integrity.
5. Backtest model calibration and market performance separately by competition type.
6. Update the JSON contract and Android country-grouped UI.
7. Only then switch the production workflow and remove legacy Football-Data.org/Openplay dependencies.

## Current implementation status

The first reusable HTML adapter is isolated in `backend/soccer365_source.py`. It extracts match links and nullable match metadata/xG, and intentionally does not modify the live pipeline. It must pass real-page fixtures and access-policy checks before production use.


## Autonomous validation notes (2026-11-08)

- Parser CI passes after the conservative status change. Match status is now populated only from explicit JSON-LD `eventStatus` values; generic page text is not treated as match status.
- A normal fetch of `https://soccer365.net/` returned HTTP 503 in the available web retrieval environment. The `robots.txt` and candidate terms URL could not be retrieved. This does not prove that automated access is prohibited, and it does not grant permission either.
- No Cloudflare bypass, proxy, browser impersonation, or other access-control workaround was attempted.
- Real-page parser validation and permission to automate access therefore remain open rollout gates. Until those gates are resolved, scores/status/xG must not be inferred from unverified page-wide text, and the production source remains unchanged.


## Data-quality gate (isolated prototype)

`backend/soccer365_quality.py` validates normalized records without changing them:
- stable match ID, distinct home/away identities, competition ID, season and competition type;
- timezone-aware UTC kickoff and recognized match status;
- non-negative integer scores and score/status consistency;
- finite non-negative xG values, nullable pairs and the `xgAvailable` flag;
- repeated source IDs and likely duplicate fixtures.

Potential duplicate fixtures are warnings, not automatic merges. They are grouped only when ordered team identity, competition ID, competition type and season agree, with kickoff timestamps within five minutes. Stable team IDs are preferred when present; otherwise normalized names are used. This conservative check is a quarantine signal, not a deduplication decision.

The parser now carries an optional `season` value supplied by a future competition-page collector. It does not infer season from the calendar year. Missing competition IDs, season, type or timezone-aware kickoff remain visible as quality warnings and block model-readiness decisions downstream.

The same module exposes `summarize_model_coverage()`: a read-only report that counts finished results for competition baselines and paired-xG samples per team. It partitions strictly by competition ID and competition type, prefers stable team IDs, and never fuzzy-merges teams. The candidate thresholds default to 30 paired-xG matches per team (the existing model's 30-match window) and 100 finished results per competition baseline (the existing baseline minimum). These are reporting gates, not a production model switch; recalibration/backtesting may change them.


## Append-only historical store (isolated prototype)

`backend/soccer365_store.py` provides a JSONL store utility for future Soccer365 history ingestion. It is not wired into the production update workflow and does not fetch pages or invent a competition catalogue.

- New source match IDs are appended; re-importing an identical record is idempotent.
- A changed payload under an existing ID is reported as a conflict and never overwrites the stored record. Corrections require an explicit review/migration process.
- Records with integrity errors are rejected; incomplete-but-valid source evidence (for example, missing xG) is retained as null rather than fabricated.
- Writes use a temporary file plus atomic replacement. Malformed existing JSONL or duplicate/missing IDs fail closed.
- This prototype does not resolve possible duplicate fixtures with different IDs; those remain a separate quality-review task.

The store is deliberately format-neutral at the application boundary: it stores normalized records as JSONL and does not alter `docs/data.json`, the Android contract, prediction archive, or production workflow.


## Explicit competition catalogue (isolated prototype)

`backend/soccer365_catalog.py` validates a manifest supplied from verified Soccer365 competition pages. Each entry must explicitly provide country, stable competition ID, competition name, competition type, season, and a verified HTTPS Soccer365 URL.

Supported type labels are `league`, `domestic_cup`, `international_cup`, `friendly`, and `national_team`. The validator rejects duplicate competition-season identities, duplicate URLs, unknown types, non-Soccer365 hosts, non-HTTPS URLs, nonstandard ports, query strings and fragments.

The validator deliberately does not generate URLs, infer seasons, resolve redirects, fetch pages, or populate a catalogue with guessed competition IDs. Different seasons for the same competition are separate manifest entries. A real catalogue must be populated only after the corresponding pages and permitted access method have been verified.


## Manifest-driven collector (isolated prototype)

`backend/soccer365_collector.py` connects the verified manifest, competition-page link extraction and match-page parser. It requests only URLs explicitly present in the manifest and match links found on those pages. It does not crawl site navigation, guess season routes, follow redirects itself, or bypass access controls; the supplied `Soccer365Client` still rejects redirects and non-canonical hosts.

The collector returns normalized records plus per-competition counts, warnings and errors. Any failed competition or match page makes the run incomplete. A match URL appearing under multiple competition entries is quarantined as an identity error rather than silently assigned to the first competition. An invalid manifest causes zero HTTP requests.

The collector does not write to the JSONL store. A future runner must require an explicitly complete collection, review its quality report, and only then call the append-only store. Partial records are diagnostic evidence, not a complete season snapshot. The module is not wired into scheduled CI or production; tests use a fake client and synthetic HTML only.
