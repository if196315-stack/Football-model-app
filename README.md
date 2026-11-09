# Football Model App

Android client and scheduled football-data pipeline.

## Data sources

- **PitchAPI** supplies fixtures, results and shot-level expected goals (xG).
- **The Odds API** supplies bookmaker prices where supported by the event and bookmaker.
- No Openplay or Football-Data.org integration is used by the active pipeline.

The production updater is `.github/workflows/update-data.yml`. After deployment to `main`, it refreshes PitchAPI data and then bookmaker odds every six hours. The odds refresh is a separate step in the same workflow so the two providers remain independently configured. A single production workflow avoids duplicate scheduled odds requests.

## Model and data rules

- Competitions: ENG, ESP, ITA, GER, FRA.
- The forecasting model uses the last 30 team matches, half-life 12 and shrinkage 3, with independent Poisson score probabilities.
- Predictions are generated chronologically; matches on the same date must not leak results into one another.
- Missing xG remains missing. The pipeline does not impute or fabricate xG values.
- Historical backtests use a clean cohort with paired, non-null xG and report forecast metrics. Those metrics are not betting ROI.
- The app displays 1X2 reference prices, supported double-chance and non-zero handicap prices, and available totals/BTTS observations. BTTS and match totals are informational only and are not offered as bet selections.
- AH0 / Draw No Bet is excluded. ITB/team-total bookmaker prices are not shown unless the provider actually returns that market; current tested coverage did not confirm it.
- Odds availability varies by match and bookmaker. Missing prices must remain missing, not be inferred.
- The app does not place bets automatically. Bet-tracker entries and manually entered stakes remain local to the Android device.

## GitHub setup

1. Add repository secrets under **Settings → Secrets and variables → Actions**:
   - `PITCHAPI_API_KEY`
   - `ODDS_API_KEY`
2. Enable GitHub Pages from the `main` branch, folder `/docs`.
3. Run **Update football data** from Actions for a manual refresh, or let the six-hour schedule run.
4. Build the Android app from the `android/` folder using Android Studio, or use the APK artifact from the build workflow.

The Android app reads `docs/data.json` from the repository's GitHub Pages URL. Until Pages is enabled and a successful data update is published, it may show a data-unavailable message.

## Quota-conscious diagnostics

The prospective odds-snapshot and market-probe workflows are manual-dispatch only because they consume The Odds API quota. Snapshots retain 1X2, double chance, supported non-zero handicaps and BTTS observations. Do not run it for routine code changes. The regular production workflow refreshes only the configured supported markets.

## Migration and validation

PitchAPI coverage audits, clean-cohort exports, and chronological backtests are available as GitHub Actions workflows. The CI unit-test job runs the backend regression suite. The migration preview is branch-only and does not publish production data.
