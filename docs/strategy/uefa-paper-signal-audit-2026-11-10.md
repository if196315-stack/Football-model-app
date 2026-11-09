# UEFA paper-signal audit note — 2026-11-10

## Status: preliminary signals invalidated

An initial screening run produced 107 unique paper-only BET entries. Their average modeled EV was unusually high. During review, the parser was found to classify any market containing the word "total" as a match/team total without explicitly excluding first-half, second-half, period and other non-full-time markets. The quote payload also did not preserve provider market identity, so complementary OVER/UNDER prices could be paired across different provider markets.

**All 107 preliminary paper entries are invalidated and must not be used for ROI, calibration, staking or deployment decisions.** The old paper signal and paper bet ledgers were removed from the active workspace. The timestamped raw odds snapshots remain as historical source records, but snapshots without `market_parser_version=betboom-fulltime-v2` are excluded from future signal evaluation.

## Corrective controls

- Reject first-half, second-half, period, corner, card, shot, offside and player markets from the selected match/team goal-total parser.
- Preserve provider market slug, normalized market group and market name on each total/handicap quote.
- Pair complementary prices only when they share the exact normalized provider market group, team (for team totals) and line.
- Mark new snapshots with `market_parser_version=betboom-fulltime-v2`.
- The evaluator accepts only that parser version. UNDER and DC 12 remain reference-only; they are not selectable bets.
- Rebuild paper signals from clean v2 snapshots before interpreting any BET count or EV.

This is a data-quality correction, not evidence that the underlying model or BetBoom prices are profitable.
