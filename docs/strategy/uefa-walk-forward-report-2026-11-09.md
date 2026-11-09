# UEFA walk-forward model report — 2026-11-09

## Executive summary

This is a historical **probability-quality** test of the existing xG/Poisson model on UEFA Champions League (UCL) and UEFA Europa League (UEL) fixtures. It is **not a betting ROI report**: historical BetBoom prices at the time of each match were not available in the source artifacts, so EV, edge, CLV, profit, drawdown and BET/WAIT/REJECT counts cannot be reconstructed.

- UEFA fixture archive: 1,840 matches; 1,696 finished.
- Shot-level xG diagnostic: 1,696/1,696 finished fixtures returned valid xG; 1,693 had paired home/away xG; 44,227 valid shots; 0 request errors.
- Model produced walk-forward predictions for 1,551/1,696 finished fixtures (91.45%); 145 were skipped because the model did not yet have enough prior team/competition history.
- Holdout: 850 predictions dated 2024-07-01 or later — UCL 430, UEL 420.
- The 3 fixtures without paired xG were not imputed. They could still be predicted if prior team histories existed.

## Method

- Reconstructed fixture rows from the isolated UEFA fixture-cache artifact and joined complete xG diagnostic results by exact PitchAPI match ID.
- Reused current backtest parameters: 30-match team history window, 12-match half-life, shrinkage 3, independent Poisson goal model, goal grid 0–12, expected goals clipped to 0.05–4.5.
- Strict calendar-date walk-forward: every prediction for a date was made before any result or xG from that date was added to team history.
- Only paired, non-null shot-level xG updates team histories. Missing xG was not converted to zero or imputed.
- Holdout boundary: 2024-07-01. No model parameter tuning was performed on the holdout.
- Binary market tables report Brier score; Log Loss was also computed as a cross-check and is listed in the pooled holdout table. The reference scores are the constant-probability baseline equal to the observed event frequency in the same evaluation subset. Lower is better.
- “P≥60% hit rate” is a descriptive filter-only diagnostic, **not** a qualifying bet. It ignores odds, margin, edge, EV, quote freshness and staking rules.

## 1X2 diagnostic (not an enabled betting market)

| Cohort | N | Model Brier | Baseline Brier | Model Log Loss | Baseline Log Loss | Model accuracy |
|---|---:|---:|---:|---:|---:|---:|
| All predictions | 1,551 | 0.576 | 0.627 | 0.969 | 1.039 | 56.9% |
| Holdout | 850 | 0.573 | 0.616 | 0.966 | 1.020 | 57.2% |
| UCL holdout | 430 | 0.550 | 0.604 | 0.931 | 0.999 | 60.2% |
| UEL holdout | 420 | 0.597 | 0.629 | 1.002 | 1.041 | 54.0% |

The 1X2 diagnostics outperform the simple historical-frequency reference on this sample, but 1X2 remains disabled in the app as previously decided.

## Selected markets — pooled holdout

All market probabilities below are derived from the same Poisson goal rates. For ITB, “home/away over X.5” is the team-total market for that side.

| Market | N | Brier | Base Brier | Log Loss | Base Log Loss | P≥60% hit | P≥60% N |
|---|---:|---:|---:|---:|---:|---:|---:|
| Double chance 1X | 850 | 0.202 | 0.223 | 0.591 | 0.639 | 73.4% | 655 |
| Double chance X2 | 850 | 0.228 | 0.250 | 0.647 | 0.693 | 68.8% | 308 |
| Match total OVER 1.5 | 850 | 0.155 | 0.155 | 0.490 | 0.489 | 80.8% | 849 |
| Match total OVER 2.5 | 850 | 0.233 | 0.238 | 0.659 | 0.668 | 67.3% | 327 |
| Match total OVER 3.5 | 850 | 0.230 | 0.237 | 0.652 | 0.667 | 66.7% | 12 |
| Home team total OVER 0.5 | 850 | 0.156 | 0.161 | 0.490 | 0.502 | 79.9% | 840 |
| Home team total OVER 1.5 | 850 | 0.231 | 0.250 | 0.654 | 0.693 | 67.9% | 165 |
| Home team total OVER 2.5 | 850 | 0.191 | 0.202 | 0.565 | 0.593 | 54.5% | 11 |
| Home team total OVER 3.5 | 850 | 0.118 | 0.121 | 0.358 | 0.376 | — | 0 |
| Away team total OVER 0.5 | 850 | 0.200 | 0.209 | 0.586 | 0.608 | 71.8% | 786 |
| Away team total OVER 1.5 | 850 | 0.225 | 0.239 | 0.642 | 0.671 | 65.6% | 32 |
| Away team total OVER 2.5 | 850 | 0.132 | 0.137 | 0.448 | 0.477 | — | 0 |
| Away team total OVER 3.5 | 850 | 0.053 | 0.056 | 0.228 | 0.249 | — | 0 |
| BTTS YES | 850 | 0.245 | 0.248 | 0.683 | 0.689 | 59.3% | 297 |
| BTTS NO | 850 | 0.245 | 0.248 | 0.683 | 0.689 | 66.7% | 3 |

A tiny P≥60% sample (for example 3, 11 or 12 signals) is not evidence of a stable hit rate. In particular BTTS YES is overconfident in the P≥60% subset: mean model probability was about 65.0%, while observed hit rate was 59.3%.

## Competition split — holdout

| Market | UCL Brier (baseline) | UCL P≥60% hit (N) | UEL Brier (baseline) | UEL P≥60% hit (N) |
|---|---:|---:|---:|---:|
| DC 1X | 0.194 (0.225) | 75.6% (311) | 0.210 (0.221) | 71.5% (344) |
| DC X2 | 0.225 (0.250) | 69.9% (156) | 0.231 (0.250) | 67.8% (152) |
| Match OVER 1.5 | 0.144 (0.146) | 82.3% (430) | 0.167 (0.164) | 79.2% (419) |
| Match OVER 2.5 | 0.220 (0.223) | 70.5% (227) | 0.246 (0.247) | 60.0% (100) |
| Home ITB OVER 0.5 | 0.150 (0.156) | 80.9% (425) | 0.163 (0.166) | 78.8% (415) |
| Home ITB OVER 1.5 | 0.232 (0.246) | 69.6% (112) | 0.230 (0.249) | 64.2% (53) |
| Away ITB OVER 0.5 | 0.194 (0.205) | 72.8% (401) | 0.205 (0.212) | 70.6% (385) |
| Away ITB OVER 1.5 | 0.224 (0.243) | 69.0% (29) | 0.226 (0.233) | 33.3% (3) |
| BTTS YES | 0.245 (0.246) | 59.1% (198) | 0.245 (0.249) | 59.6% (99) |

The full market/line-level scores, including OVER 3.5 and higher team totals, are in the pooled table above. For several higher lines, the P≥60% subset is too small or empty to support a selection decision.

## Markets not scored

- Exact handicap: excluded. The historical source did not provide a verified exact handicap line and contemporaneous price per fixture.
- 1X2: probability diagnostic only; it remains disabled in the app.
- UNDER match totals and UNDER team totals: intentionally hidden and not evaluated as selectable markets.

## Interpretation and next decisions

1. **Keep DC 1X / X2 in the candidate set for further paper testing.** Both beat the constant-frequency Brier reference in the pooled holdout and in each competition.
2. **Keep match OVER 2.5 and home ITB OVER 1.5 as research candidates, not approved bets.** Their holdout scores are modestly better than baseline; the UEL OVER 2.5 P≥60% hit rate is only 60.0%.
3. **Do not promote BTTS based on this test.** The overall score improvement is very small and the P≥60% BTTS YES subset underperforms its average predicted probability.
4. **Do not use higher ITB lines or OVER 3.5 as a rule yet.** The qualifying subsets are too small or empty.
5. **No ROI or EV claim is justified.** Before any market can be approved, collect time-stamped BetBoom odds for each exact line and run a separate price-aware backtest with vig removal, edge/EV thresholds, quote-age validation, CLV and bankroll drawdown.
6. The strategy's required 500 historical + 500 paper signals per market has not been satisfied by this report. These are probability evaluations, not a validated deployment.

## Source artifacts

- UEFA fixture-history workflow run: 42678975388; artifact 13702146063 (pitchapi-uefa-fixture-history-1).
- Full UEFA shot/xG diagnostic workflow run: 42680760961; artifact 13702837713 (pitchapi-uefa-xg-diagnostic-2).
- xG scan summary: 1,696 finished fixtures, 1,696 valid-xG responses, 1,693 paired-xG fixtures, 44,227 valid shots, zero request errors.
- Model implementation reference: backend/backtest_pitchapi.py (walk-forward team-strength and Poisson methodology).
