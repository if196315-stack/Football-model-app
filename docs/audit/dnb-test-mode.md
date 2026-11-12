# MelBet handicap 0 (DNB) — isolated test mode

Status: **test branch only**. This work is intentionally not merged into `main` and does not enable DNB in the production Android candidate selector.

## Scope

- Parse only full-time, prematch Asian handicap line `0` from MelBet.
- Keep home and away selections separate and retain the provider market identity.
- Reject non-zero lines, live markets, and first-/second-half markets.
- Archive both sides with exact MelBet price and source-board timestamp.
- Preserve model win, push, and loss probabilities separately where the UEFA xG/Poisson feed provides them.
- Settle a draw as `PUSH`, not as a win or loss.

## Probability and value convention

For one selected team, let:

- (p_W): probability that the selected team wins;
- (p_D): probability of a draw (stake returned);
- (p_L): probability that the selected team loses;
- (K): decimal odds.

The three outcome probabilities must be finite, each in [0, 1], and sum to 1.

The model probability used for the DNB edge check is conditional on a decisive result:

`P_DNB = p_W / (p_W + p_L)`

Break-even probability is `1 / K`, and edge is `P_DNB - 1/K`.

Expected net value per unit stake, including the push outcome, is:

`EV = p_W × (K - 1) - p_L`

The full-Kelly fraction conditional on a decisive result is:

`f = max(0, (P_DNB × K - 1) / (K - 1))`

The isolated math helper applies the configured default gates of +5 percentage points on conditional probability edge and +5% unconditional EV. This is a test calculation only, not an instruction to stake.

## Separate conditional-probability calibration

The chronological all-market research backtest now reports two DNB views:

1. **W/P/L calibration:** the full three-class outcome vector. It is the same match-outcome partition as 1X2, so it is retained for settlement diagnostics but is not counted as independent evidence.
2. **Decisive-only calibration:** a binary W/L model fitted only on matches where the result was not a draw. Draws are excluded from both the training and holdout scoring for this conditional diagnostic. The target is (P(\text{win} \mid \text{no draw})).

The decisive-only calibrator is trained on pre-holdout walk-forward predictions only, then evaluated on the later chronological holdout. It reports Brier, Log Loss and paired match-level bootstrap confidence intervals. Per-competition results are diagnostic; small samples are not automatically promoted.

For live matched DNB prices, the read-only audit calculates:

- raw bookmaker implied probabilities (1/K_H) and (1/K_A);
- two-way overround (1/K_H + 1/K_A);
- no-vig conditional probabilities by normalizing those two implied probabilities;
- raw model DNB EV (p_W(K-1)-p_L);
- experimental calibrated conditional edge against no-vig.

The live edge comparison is marked experimental. It is not a production filter and does not prove profitability. When experimental calibrated conditional probability is combined with the model's raw push probability to estimate EV, the report must identify that mixed convention explicitly.

## Data limitations

The historical paired-xG dataset contains match results and xG, not a complete historical MelBet DNB odds archive. Therefore historical DNB price edge, CLV, EV and ROI cannot be backtested from that cohort. Those require timestamped two-sided MelBet prices, verified final scores, consistent settlement, and enough completed observations. Until the archive accumulates that evidence, only probability calibration and read-only live price comparisons are possible.

## What this does not authorize

- It does not make DNB a production Model №1 recommendation.
- It does not infer domestic DNB probabilities from 1X/X2 or from bookmaker prices.
- It does not claim the production Android selector has parity for DNB.
- It does not claim that UEFA xG/Poisson probabilities are calibrated production probabilities.
- It does not change `main`, production data, thresholds, or live recommendations.

## Remaining validation before any merge decision

1. Confirm CI on this branch, including parser, archive, settlement, and DNB math tests.
2. Inspect representative real MelBet prematch payloads to verify the provider's outcome keys and handicap argument conventions.
3. Verify domestic model output can supply defensible win/push/loss probabilities, or explicitly keep domestic DNB out of scope.
4. Add Android/backend selector parity only after the DNB probability contract is agreed and tested.
5. Run an out-of-sample DNB backtest with push handling and enough observations before considering production use.

Until all gates pass, DNB remains **test-only / not eligible for Model №1 recommendations**.
