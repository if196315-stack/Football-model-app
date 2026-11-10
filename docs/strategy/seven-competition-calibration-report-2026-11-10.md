# Seven-competition probability calibration report — 2026-11-10

> **Superseded for current decisions:** use the reproducible main-branch report [seven-competition-audit-2026-11-11](./seven-competition-audit-2026-11-11.md). It reruns the 10,031-match cohort on `main`, counts 15 independent outcomes (the two DNB labels duplicate 1X2), and adds paired bootstrap confidence intervals. This file preserves the original feature-branch research run.


## Status

Research-only calibration completed successfully in GitHub Actions run [42705865885](https://github.com/if196315-stack/Football-model-app/actions/runs/42705865885).

- Calibration implementation commit: `f786bd9d36656189acbb87076e6c2c682867922d` on the isolated `feature/pitchapi-data-source` branch.
- Artifact: `seven-competition-all-market-backtest-21` (ID `13712598335`).
- Historical input: 10,031 matches across ENG, ESP, FRA, GER, ITA, UCL and UEL.
- Model predictions: 9,798 matches.
- Calibration training rows per market (global): 9,179.
- Holdout: 619 matches from 2026-07-01; 619 observations for each of 17 markets.

This report is a research result, not a production model change.

## Method

1. Generate chronological walk-forward Poisson/xG probabilities. Same-calendar-day results are not available to any other match on that date.
2. Split predictions by kickoff date: calibration training uses only rows before 2026-07-01; holdout uses rows on or after that date.
3. Fit regularized multinomial vector scaling independently for each market:
   `softmax(log(p) / temperature + class_bias)`.
4. Evaluate the fitted calibrator once on holdout. Global calibrators use all competitions; per-competition calibrators are fit separately using only that competition's pre-holdout rows.
5. No odds, bookmaker comparison, edge, EV or ROI are used in this calibration experiment.

A positive `delta` below means raw score minus calibrated score, so positive values indicate the calibrated score is lower/better. These are absolute score differences, not percentage points.

## Global calibration results by market

| Market | Raw Brier | Calibrated Brier | Brier delta | Raw Log Loss | Calibrated Log Loss | Log Loss delta |
|---|---:|---:|---:|---:|---:|---:|
| 1X2 | 0.573067 | 0.570499 | +0.002568 | 0.968130 | 0.964900 | +0.003231 |
| DC 1X | 0.396016 | 0.395054 | +0.000961 | 0.581663 | 0.580838 | +0.000825 |
| DC X2 | 0.428553 | 0.425932 | +0.002621 | 0.617313 | 0.613977 | +0.003335 |
| BTTS YES | 0.480661 | 0.480693 | -0.000032 | 0.673392 | 0.673435 | -0.000044 |
| Match OVER 1.5 | 0.304357 | 0.304161 | +0.000196 | 0.478988 | 0.478657 | +0.000331 |
| Match OVER 2.5 | 0.455955 | 0.456040 | -0.000084 | 0.647002 | 0.647218 | -0.000215 |
| Match OVER 3.5 | 0.449136 | 0.449160 | -0.000024 | 0.640087 | 0.640140 | -0.000053 |
| Home OVER 0.5 | 0.319214 | 0.319958 | -0.000745 | 0.490582 | 0.491304 | -0.000722 |
| Home OVER 1.5 | 0.444745 | 0.444339 | +0.000406 | 0.635460 | 0.635009 | +0.000450 |
| Home OVER 2.5 | 0.333589 | 0.333685 | -0.000095 | 0.508731 | 0.508848 | -0.000117 |
| Home OVER 3.5 | 0.176723 | 0.176807 | -0.000084 | 0.298801 | 0.298877 | -0.000076 |
| Away OVER 0.5 | 0.376341 | 0.376294 | +0.000047 | 0.561332 | 0.561295 | +0.000038 |
| Away OVER 1.5 | 0.450769 | 0.450899 | -0.000130 | 0.642062 | 0.642211 | -0.000149 |
| Away OVER 2.5 | 0.276571 | 0.276679 | -0.000108 | 0.444703 | 0.444997 | -0.000294 |
| Away OVER 3.5 | 0.115455 | 0.115621 | -0.000166 | 0.214204 | 0.214448 | -0.000244 |
| DNB home (W/P/L) | 0.573067 | 0.570499 | +0.002568 | 0.968130 | 0.964900 | +0.003231 |
| DNB away (W/P/L) | 0.573067 | 0.570499 | +0.002568 | 0.968130 | 0.964900 | +0.003231 |

DNB is evaluated as three outcomes (win/push/loss). Its Brier and Log Loss match the 1X2 result because DNB is the same three-outcome score vector under a relabelling of win/loss classes.

## Per-competition calibrator diagnostics

Each cell is the average raw-minus-calibrated score delta across the 17 markets, not a percentage. Positive is better. The rightmost column counts markets whose Brier score improved.

| Competition | Holdout matches | Mean Brier delta | Mean Log Loss delta | Markets Brier improved |
|---|---:|---:|---:|---:|
| ENG | 98 | +0.000649 | +0.001300 | 11/17 |
| ESP | 116 | -0.000541 | -0.000791 | 7/17 |
| FRA | 89 | +0.000781 | +0.001205 | 11/17 |
| GER | 79 | -0.000418 | -0.000860 | 10/17 |
| ITA | 110 | +0.003916 | +0.004640 | 14/17 |
| UCL | 66 | +0.008697 | +0.012435 | 14/17 |
| UEL | 61 | -0.007639 | -0.010567 | 2/17 |

## Interpretation and decision

- Global calibration improves 1X2, both double-chance markets, match OVER 1.5, home OVER 1.5 and away OVER 0.5 by small absolute amounts.
- It is neutral-to-worse on most other totals and team totals. BTTS changes are negligible and slightly negative.
- Competition-specific calibration is not stable across all competitions. It is positive on average for ENG, FRA, ITA and UCL, but negative for ESP, GER and especially UEL.
- UEL has only 61 holdout matches; the large negative delta should be treated as a warning requiring more data, not as a definitive permanent adjustment.
- Do not apply these calibration parameters to production or betting thresholds yet. Keep raw probabilities for strategy decisions until the effect is replicated on additional chronological holdouts and checked against BetBoom prices.

## Data and betting limitations

- This is probability calibration against match outcomes, not a comparison with bookmaker closing prices.
- Historical BetBoom odds are not available for the full 10,031-match xG history, so this experiment cannot establish edge, EV, ROI or profitability.
- A prospective BetBoom archive has now been fixed and started for the five domestic leagues. It must accumulate multiple captures and settled outcomes before a forward betting-market evaluation is meaningful.
- No automatic real bets are placed.
