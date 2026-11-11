# API-Sport team alias review — 2026-11-11

## Purpose

This is a human-review candidate registry derived from the read-only 70-fixture pair-ID audit. It is **not** imported by production code and must not be used to bypass the production matcher's thresholds.

The API-Sport team and tournament IDs below were observed together in downloaded fixture rows. The mapping establishes a plausible canonical team-name alias in that tournament; it does not independently certify a specific fixture or kickoff time.

## Confirmed competition-scoped candidates

| PitchAPI name | API-Sport observed name | API-Sport team ID | Tournament ID | Competition | Review status |
|---|---|---:|---:|---|---|
| Roma | AS Roma | 2702 | 23 | Serie A | Candidate alias |
| Paderborn | SC Paderborn 07 | 2561 | 35 | Bundesliga | Candidate alias |
| Elversberg | SV 07 Elversberg | 2598 | 35 | Bundesliga | Candidate alias |
| Augsburg | FC Augsburg | 2600 | 35 | Bundesliga | Candidate alias |
| Union Berlin | 1. FC Union Berlin | 2547 | 35 | Bundesliga | Candidate alias |
| Mainz 05 | 1. FSV Mainz 05 | 2556 | 35 | Bundesliga | Candidate alias |
| Borussia Mönchengladbach | Borussia M'gladbach | 2527 | 35 | Bundesliga | Candidate alias |
| Freiburg | SC Freiburg | 2538 | 35 | Bundesliga | Candidate alias |
| Hoffenheim | TSG Hoffenheim | 2569 | 35 | Bundesliga | Candidate alias |
| Bayern München | FC Bayern München | 2672 | 7 | Champions League | Candidate alias |
| Schalke 04 | FC Schalke 04 | 2530 | 35 | Bundesliga | Candidate alias |
| Werder Bremen | SV Werder Bremen | 2534 | 35 | Bundesliga | Candidate alias |
| Lens | RC Lens | 1648 | 34 | Ligue 1 | Candidate alias |
| Monaco | AS Monaco | 1653 | 34 | Ligue 1 | Candidate alias |
| Salzburg | Red Bull Salzburg | 2046 | 679 | Europa League | Candidate alias |
| Ararat Armenia | FC Ararat-Armenia | 262229 | 679 | Europa League | Candidate alias |

These 16 entries are the non-exact-name mappings selected for the isolated review registry. Review each mapping before any production adoption.

## Unresolved / not approved for alias registry

| PitchAPI name | Diagnostic observation | Decision |
|---|---|---|
| Slavia Prague | Pair-ID diagnostics found API-Sport team `SK Slavia Praha`, team ID `2216`, in Champions League tournament `7`; it appeared in ordered pairs against Villarreal, Bayern München, FC Porto and Aston Villa. Name similarity was only `0.626`. | Candidate observed, but not manually approved; keep unresolved in the alias registry. |

**Correction to the earlier wording:** it was inaccurate to say that no senior Slavia candidate was observed. The pair-ID log does contain the `SK Slavia Praha` candidate (ID `2216`). The reason it remains excluded is that the name-only similarity is weak and the alias has not undergone the same explicit review as the 16 registry entries. This evidence alone does not authorize adding it to production or to the reviewed registry.

## Negative examples and safeguards

- Do not map Borussia Mönchengladbach to a youth/U19 team; the observed senior candidate is team ID `2527` in Bundesliga `35`.
- Do not map Bayern München to Bayern II; the observed Champions League candidate is team ID `2672` in tournament `7`.
- Do not map Ararat Armenia to Ararat-Armenia II; the observed candidate is team ID `262229` in Europa League `679`.
- Do not map Slavia Prague to Sparta Prague or a Slavia youth team. The observed `SK Slavia Praha` candidate is distinct from Sparta Prague, but remains unapproved pending review.
- Do not accept a candidate solely because its fuzzy name similarity is high or because it appears in a pair-ID search. Require the expected tournament and a reviewed team identity.
- Do not infer a fixture from a pair of team aliases alone. The exact ordered pair, tournament, date/time, and duplicate-candidate handling must be evaluated independently.
- Do not globally lower fuzzy thresholds. The 70-pair audit found ambiguous candidates, including a weak Schalke/Union Berlin false positive.

## Relationship to the 70-fixture audit

The pair-ID audit found all 70 ordered team pairs in the expected tournament within the 50,267 API-Sport rows scanned. This registry documents 16 selected name-to-team-ID resolutions used during that analysis. It does not resolve the observed calendar discrepancies: 30 Serie A pairs were listed one calendar day earlier by API-Sport, and 9 Bundesliga pairs one day later.

The pair-ID diagnostic used a permissive name-candidate floor of `0.60` and ignored kickoff time when looking for ordered pairs. Its 70/70 result is therefore evidence that candidate team-ID pairs occur in the downloaded tournament rows, not independent confirmation that every fixture or date is correct.

Source report: [API-Sport pair-ID follow-up](api-sport-70-unmatched-pair-id-followup-2026-11-11.md).

## Production status

**No production adoption is authorized or implemented by this document.** The registry is documentation-only. PitchAPI remains the fixture/xG source, API-Sport remains the odds source, and the strict prematch-stage acceptance filter must remain unchanged. Any future code change requires separate negative-example tests, duplicate-resolution tests, and explicit approval.
