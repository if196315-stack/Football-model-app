# Release hardening refresh — 2026-11-23

This refresh supersedes stale status statements below where noted. The prior audit body is retained as historical evidence; its original baseline and workflow run IDs must not be read as current-main verification.

## Current baseline and scope

- Verified current `main` before this hardening branch: `cc31ca46687f65c2fb8fb73bca9f0501bb8e2d97`.
- This refresh reviewed the current README, Android Gradle configuration, Android build workflow, three JVM test classes, app manifest/source inventory, and prior audit evidence.
- Android CI on the current baseline: run [45077016756](https://github.com/if196315-stack/Football-Model-app/actions/runs/45077016756) passed unit tests, debug APK build and artifact upload.
- The most recent confirmed backend regression and data/model audit runs remain on the earlier baseline `0a01705b9dc36c05018aac7fbf47da42014a6df8`; backend source was unchanged in the comparison to current main, but this is not a fresh full backend pass on `cc31ca4`.
- The current Android workflow builds a debug APK only. No signed release APK/AAB or GitHub Release has been verified.
- No UI instrumentation suite or physical-device QA evidence was found. Three JVM test classes cover selected analytics, settlement and prediction-scope logic; they do not cover every screen, lifecycle, network, notification or WorkManager path.

## Hardening changes prepared on branch

Branch: `release/hardening-before-signing`.

1. Removed the three duplicate Kotlin-DSL Gradle files (`android/build.gradle.kts`, `android/settings.gradle.kts`, `android/app/build.gradle.kts`). The active Groovy files remain, matching the existing CI invocation. This removes ambiguity and the conflicting minSdk declarations; the surviving app configuration sets minSdk 23.
2. Corrected README's active-market description to the eight markets enforced by Android tests: 1X, X2, BTTS Yes, match Over 2.5, home Over 0.5/1.5 and away Over 0.5/1.5. Match Over 1.5 is not part of the active set.
3. Added `.github/workflows/android-release-candidate.yml`: it runs JVM unit tests, release lint and `assembleRelease`, then uploads the explicitly unsigned release APK. It does not sign or publish the app.
4. These changes are awaiting PR CI at the time this addendum was written; do not treat them as passed until the workflow reports success.

## Remaining release gates (not waived)

- Configure signing only after the owner supplies/sets the keystore and passwords as protected GitHub Actions secrets; never commit signing material.
- Run the new unsigned release-candidate workflow and fix every build/lint/test failure.
- Add and execute UI/instrumentation tests, then install and exercise the candidate on supported Android versions and screen sizes.
- Re-run backend regression, current data-feed audits, AI/Line parity and odds-coverage diagnostics against the exact release candidate commit.
- Decide explicitly whether the existing one-leader/no-fallback signal policy is accepted; this hardening branch does not change strategy semantics.
- Recheck current MelBet quote coverage. The low coverage figures in the historical addendum below are dated snapshots, not live guarantees.
- Do not claim complete functional certification or production readiness based only on compilation and JVM tests.

**Status at refresh:** hardening changes prepared; signing intentionally not attempted. The release remains uncertified until the gates above are evidenced.

---

# Final release audit — 2026-11-23

Repository: `if196315-stack/Football-model-app`
Audit baseline: `main` at `80d5ecf18fa114b5377576961e8f5f7fa82c35e7`
Audit scope: Android app, data feeds, backend model/odds pipelines, AI signal selection, analytics/settlement, GitHub Actions, build and release path.

## Executive result

**Release status: NOT READY for a production/final release.** The project builds as a debug APK and the baseline backend and Android unit-test workflows passed, but the audit found production-feed and release-process defects. A physical-device visual/interaction pass and signed release build have not been completed; they cannot be inferred from a successful debug build.

## Verified architecture

- Native Android application in Kotlin; one `MainActivity` hosts the match centre and five primary tabs (matches, desk, history, stats, help).
- Match data is static JSON published from GitHub Pages/raw GitHub: `docs/data.json` for domestic competitions and `docs/uefa-data.json` for UCL/UEL/UECL. `AutoPaperWorker` fetches and merges UEFA into domestic data, caches the merged payload locally, and runs periodically with WorkManager.
- Python backend refreshes PitchAPI fixtures/results/xG, collects API-Sport/MelBet snapshots, builds feeds, evaluates paper signals and settles paper bets.
- Android settlement and analytics have focused JVM unit tests. Backend has a broad unittest suite.
- No live/automatic bookmaker bet placement is configured; paper automation is local tracking only.

## Findings

### Blocker — production release artifact is not available
The configured workflow assembles `assembleDebug` and uploads `Football-Model-debug-apk`. The checked app module has no verified signed release build/signing configuration in the audited release workflow. Therefore a successful APK workflow is not proof that a distributable production release is ready.

### High — MelBet availability is sparse
The latest eight-competition signal audit (2026-11-22 20:34 UTC) counted 1,405 future fixtures with model predictions, but only 118 had a MelBet board/line leader. 1,287 (91.6%) had no board. The model forecast coverage was 1,405/1,405 in that snapshot, but usable live bookmaker pricing was only 8.4%. This materially limits signal availability and user-visible odds coverage.

### High — stale market whitelist in domestic feed eligibility (fixed in PR #273)
The domestic MelBet feed gate still treated full-match Over 1.5 as a strategy market, even though the active Android display/strategy is the eight-market set and the UI does not render Over 1.5. That could retain a fixture on a quote the user cannot see. The PR removes Over 1.5 from feed eligibility and the Android-parity audit, and adds regression tests.

The active set is now explicitly: 1X, X2, BTTS Yes, match Over 2.5, home Over 0.5/1.5, away Over 0.5/1.5.

### High — UEFA paper signal evaluator used a legacy BetBoom archive (fixed in PR #273)
The collector writes current MelBet snapshots to `artifacts/all-eight-competitions-melbet-snapshots.jsonl`, but the UEFA paper evaluator previously read `artifacts/uefa-betboom-odds-snapshots.jsonl` and accepted only `betboom-fulltime-v2`. Thus the scheduled pipeline was not evaluating the fresh MelBet rows that the app displays. The PR switches the evaluator to the unified MelBet archive, requires `melbet-fulltime-v2`, and filters candidate rows to the same eight active market lines.

### High — concurrent feed writers caused a real workflow failure (fixed in PR #273)
Run 45066678942 completed data generation but failed at `git pull --rebase` with a content conflict in `docs/uefa-data.json`. The log shows the calibrated-feed workflow updated the same feed while the MelBet collector was committing under a different concurrency group. The PR unifies these writers under one concurrency group to serialize shared-feed writes.

### High — signal selection can suppress a valid alternative
The app/audit selects one Line leader per match (maximum full-Kelly among positive-EV priced candidates; otherwise highest probability), then applies AI gates to that leader only: probability ≥60%, EV ≥5%, edge ≥5 pp, odds ≥1.50, BTTS No excluded. There is no fallback to the next eligible market if the leader fails an AI gate. This is deterministic and tested, but can hide a second market that passes all gates. It is a strategy-design decision and was not changed in this hardening PR.

### Medium — UI is not covered by instrumentation tests
The repository contains focused unit tests for settlement and analytics, but no Espresso/UIAutomator instrumentation suite was found. Navigation, taps, swipe gestures, rendering on small screens/tablets, notification permission flows and offline recovery have not been exercised on a physical device/emulator during this audit.

### Medium — debug build is not device QA
Main-branch workflow 45066678966 successfully ran Android unit tests and assembled a 2.69 MB debug APK. This verifies compilation and the current JVM test suite only. It does not verify installation/startup on a real device, runtime network behaviour, notification delivery, background-work restrictions or visual layout.

### Medium — duplicate Gradle DSL files
The Android tree contains both Groovy and Kotlin DSL variants for app build, root build and settings. The successful CI run proves one configuration path builds, but keeping parallel build scripts is an avoidable source of ambiguity and drift. Consolidate to one DSL in a separate controlled change after confirming which files Gradle selects.

### Model validation limitation
The X2 vector-scaling calibrator is active in the model code. Its holdout comparison is against an expanding historical-frequency baseline, not bookmaker closing odds. Only X2 showed a globally positive 95% paired-bootstrap interval in the recorded calibration analysis. No profitability, ROI, EV realization or CLV claim is established by that analysis.

## CI evidence

- Android debug build + JVM unit tests: run 45066678966 — success (baseline commit `eba965b3`).
- Backend odds tests: run 45066678965 — success (baseline commit `eba965b3`).
- Eight-competition data audit: run 45067179208 — success (main commit `80d5ecf`).
- Eight-competition AI/Line audit: run 45067179259 — success (main commit `80d5ecf`).
- MelBet snapshot collector: run 45066678942 — failed at concurrent rebase conflict (details above).
- Calibrated production feed refresh: run 45066379456 — success.

## What is not certified

- Signed release APK / Play-ready bundle.
- Installation and end-to-end operation on a physical Android device.
- Pixel-level layout audit across screen sizes and Android versions.
- Real push/notification delivery under battery saver/background restrictions.
- Live MelBet coverage beyond the audited snapshot.
- Betting profitability or real-world expected value.

## Release gate

Do not label the current build as the final production release until:
1. PR #273 hardening changes pass their own backend regression, Android build/unit tests and eight-competition audit.
2. A signed release artifact is produced and install-tested.
3. Device QA covers all tabs, match cards, filters, paper bet create/edit/settle, analytics, notifications, cache restore, network loss and recovery.
4. MelBet coverage is improved or the app clearly communicates unavailable odds without implying full live coverage.
5. The one-leader/no-fallback selection policy is explicitly accepted or changed and regression-tested.


## Post-merge verification addendum

The hardening changes were squash-merged in PR #273 as commit `0a01705b9dc36c05018aac7fbf47da42014a6df8`. Post-merge checks on that commit:

- Android unit tests + debug APK build: run 45068783667 — success.
- Backend regression suite: run 45068783669 — success.
- Eight-competition model/data audit: run 45068783611 — success.
- Eight-competition Android-parity AI/Line audit: run 45068783787 — success.
- Unified MelBet collector, paper evaluator, settlement and feed publication: run 45068783629 — success.
- Calibrated production model feed refresh: run 45068783680 — success.

The successful MelBet collector run reported 1,408 upcoming fixtures in its cache, 1,269 matched PitchAPI fixtures, 125 active prematch MelBet boards with supported markets, and zero missing model predictions among matched fixtures. Domestic feed publication reported 79 visible MelBet-priced matches out of 1,207 eligible upcoming domestic fixtures; 1,128 had no fresh active MelBet quote. This remains a material odds-coverage limitation.

The corrected MelBet paper evaluator processed 30,851 archived MelBet signal observations: 547 BET, 7,890 REJECT and 22,414 WAIT; it added 10 paper-bet entries (37 total). These are paper-only outcomes, not executed bets or proof of profitability.

The final APK was built from the merged application source on commit `0a01705b9dc36c05018aac7fbf47da42014a6df8`, artifact run 45068783667. It is still a **debug APK**, not a signed production release.
