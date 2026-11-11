# IQ MATCH Strategy v1.2

**Status:** current strategy specification  
**Selection:** STRICT  
**Risk:** MODERATE, stake 1% of current bank with optional user-defined minimum; pause at 10% drawdown and stop at 20%  
**Launch:** LIMITED  
**Bookmaker/data source:** BetBoom odds supplied through API-Sport only

This document is the source of truth for the current IQ MATCH strategy. Thresholds are starting engineering parameters, not validated optimal values and not a promise of profitability.

## 1. Decision pipeline

1. Validate fixture, market, line, BetBoom quote and data freshness.
2. Obtain the model probability for the exact market and line.
3. Calculate break-even probability, edge and expected value (EV).
4. Apply signal-quality and market-specific validation.
5. Apply duplicate-market protection and drawdown controls. There is no daily aggregate exposure cap; different markets in the same match are permitted.
6. Return exactly one decision: BET, WAIT or REJECT, with a reason.

No bet is a valid outcome. A high predicted probability alone is not sufficient.

**Fixture horizon:** there is no fixed 14-day, 30-day, or calendar-month cutoff for upcoming fixtures. The app, paper-signal selection, automatic paper-entry worker, and API-Sport odds ingestion may process every upcoming fixture present in their respective feeds. Past fixtures remain excluded from new entries; existing history and settlement are preserved.

## 2. Data-quality gate

A BET is prohibited unless all applicable checks pass:

- Fixture is matched unambiguously to the correct teams and kickoff.
- Quote originates from BetBoom through API-Sport; no other bookmaker is substituted.
- The quote is a real BetBoom quote refreshed within the final 12 hours before kickoff; no separate 5-minute cutoff is applied.
- Market, side and line match the model probability exactly.
- Quote and probability are present, finite and valid.
- No critical missing or contradictory input exists.
- For lineup-sensitive markets, missing lineup information must be reflected in the decision; do not silently assume a confirmed lineup.

Stale or incomplete data produces WAIT or REJECT, never BET.

## 3. Strict signal gate

Initial Class A / BET eligibility thresholds:

- Model probability >= 60%.
- Edge over break-even probability >= 5 percentage points.
- EV >= +5%.
- Model has been evaluated for the corresponding market type.
- Signal remains reasonably stable under small changes in assumptions.
- All data and risk checks pass.

Definitions:

- Break-even probability = 1 / decimal odds.
- Edge = model probability - break-even probability.
- EV = model probability * decimal odds - 1.

Example only: P=0.60 and odds=1.90 gives break-even=52.63%, edge=7.37 pp and EV=+14%. This is an arithmetic example, not a match recommendation.

Signal classes:
- A: all quality, value and risk filters pass; may become BET.
- B: promising but missing confirmation or data; WAIT.
- C: inadequate/negative value, poor data or unacceptable risk; REJECT.

For multi-outcome market analysis, account for bookmaker margin when comparing model probabilities with the market. Do not compare different lines or sides.

## 4. Bank and exposure policy

- Stake size is 1% of the current bank, but never below the minimum amount explicitly set by the user.
- The minimum stake is unset by default. When unset, the stake is exactly 1% of the current bank; there is no hard-coded 25 ₽ default.
- If the user sets a minimum, stake = max(1% of current bank, user-defined minimum). The configured minimum may therefore make the stake exceed 1%.
- There is no daily aggregate exposure cap and no aggregate per-match cap in this strategy.
- Different markets in the same match are permitted; duplicate protection blocks only a repeat of the same market for the same match.
- No chasing losses, no automatic stake increase after wins, and no full Kelly in v1.2.
- Drawdown is measured from the local bank high-water mark.
- 10% drawdown: pause new automatic paper entries.
- 20% drawdown: stop automatic paper entries until explicit review and approval.

The 10% pause and 20% stop are risk controls for paper-bet automation. They do not submit, authorize, or execute real bookmaker bets.

## 5. Market scope

Only use exact, supported BetBoom quotes through API-Sport:

- Double Chance 1X / X2.
- Match Total OVER.
- Team Total OVER.
- BTTS YES only; BTTS NO is disabled.
- Handicaps only when the exact handicap line and price are ingested and validated.

Do not offer 1X2 until its prices are correctly ingested. UNDER match/team totals are hidden from the current user-facing betting scenario. Never fabricate or infer missing prices.

## 6. Required decision record

Persist every candidate, including rejected candidates:

- strategy version and decision timestamp;
- fixture identifier, teams and kickoff;
- bookmaker, market, side and exact line;
- quoted odds and quote timestamp;
- model probability, break-even probability, edge and EV;
- data-quality status and model/market validation status;
- signal class, BET/WAIT/REJECT decision and human-readable reason;
- stake amount, stake percentage, bank snapshot and exposure after decision;
- eventual settlement and result, when available.

The recorded quote and model probability must not be overwritten after the match. Keep rejected signals for selection-bias analysis.

## 7. Validation plan

### Stage 1 — data audit
Test fixture matching, quote freshness, market/side/line mapping, missing values, duplicates and bookmaker isolation. Fail closed on ambiguous mapping.

### Stage 2 — historical backtest
Use chronological splits for training, validation and a final untouched test set. Prevent look-ahead leakage, including same-date result leakage. Use only features and odds that would have been available at decision time. Report results separately by market and competition.

### Stage 3 — paper trading
Run against current BetBoom quotes without placing real bets. Record BET, WAIT and REJECT candidates, decision-time odds and pre-kickoff closing odds. Do not change thresholds mid-cohort; version every change.

### Stage 4 — limited real launch
Only after prior gates pass. Begin with 1% stakes, apply any user-defined minimum stake, retain the 10% drawdown pause and 20% drawdown stop, and manually review results. Do not expand markets or limits automatically.

### Stage 5 — ongoing review
Review market-level performance, calibration, CLV, drawdown, data quality and rejection reasons. Change one rule group at a time and retest on independent data.

Initial evaluation volume targets: at least 500 historical candidate signals and 500 paper-trading signals overall; preferably 150–200 observations per market being evaluated. These are screening targets, not proof of statistical significance. Report uncertainty and confidence intervals.

## 8. Monitoring metrics

- ROI and yield, with explicit denominators.
- Closing Line Value (CLV), with consistent odds convention.
- Maximum drawdown.
- Brier Score and Log Loss.
- Calibration by probability band and market.
- Win rate, as a secondary metric only.
- EV and realized performance by market.
- Counts and reasons for WAIT/REJECT.
- Quote age, missing quote rate and market-mapping errors.

A positive backtest alone is insufficient. Require out-of-sample and paper-trading evidence; account for sample uncertainty and selection bias.

## 9. Kill switches

- Quote feed stale or unavailable: block new BET decisions.
- Widespread fixture/market mapping errors: block new BET decisions.
- Invalid or abnormal model probabilities: block new BET decisions.
- Daily exposure cap reached: block further bets for the day.
- 10% drawdown: pause automatic paper entries and audit.
- 20% drawdown: stop automatic paper entries pending explicit review.

A pause must not silently reset the next day. Resumption requires checking the cause.

## 10. Product presentation

The Stats screen must keep this strategy guide accessible, including selection gates, value formulas, bank limits, market scope, test stages and risk stop rules. Each decision should expose its reason. Strategy version must be attached to every recorded signal.

**Governance:** v1.2 thresholds remain provisional until independently validated. No profit guarantee; no automatic execution is authorized by this document.
