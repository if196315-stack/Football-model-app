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
