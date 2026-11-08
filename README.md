# Football Model App

Android client and scheduled data pipeline for the frozen Openplay v1.3 football model.

## Model contract
- Five competitions: ENG, ESP, ITA, GER, FRA.
- Frozen parameters: all-match team history, last 30 matches, half-life 12, shrinkage 3.
- Independent Poisson score model.
- Predictions must be generated before updating history for matches on the same date.
- Openplay xG is the only xG source. Football-data.org is used for fixtures/results only.
- No odds, ROI, or betting recommendations are provided.
- If a team cannot be mapped reliably to an Openplay team ID, or current-season xG is absent, do not fabricate a prediction.

Openplay data attribution: **openplay (Noah Bair)**, CC-BY 4.0.

## Setup
1. In GitHub: Settings → Secrets and variables → Actions → New repository secret.
2. Add `FOOTBALL_DATA_TOKEN` with your Football-Data.org token. Never commit the token.
3. Enable GitHub Pages from the `main` branch, folder `/docs`.
4. Run the workflow **Update football data** from Actions.
5. Build the Android app from the `android/` folder using Android Studio.

The Android app reads `docs/data.json` from this repository's GitHub Pages URL. Until Pages is enabled and the first successful workflow completes, it shows a data-unavailable message.

## Important current limitation
The published Openplay v0.1.0 snapshot covers seasons through 2025/26 and is not a live xG feed. The app must display the snapshot date and must not label historical xG forecasts as fresh 2026/27 predictions.
