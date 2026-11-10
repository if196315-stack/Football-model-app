# Football Model App

Android-клиент IQ Match и конвейер футбольных данных, прогнозов и paper-оценки.

## Источники данных

- **PitchAPI** — матчи, результаты и shot-level expected goals (xG).
- **API-Sport** — букмекерские коэффициенты BetBoom. Используется секрет GitHub Actions `API_SPORT_KEY`.
- Другие букмекерские источники не подменяют BetBoom; отсутствующие котировки остаются отсутствующими.

## Поддерживаемые соревнования

Основной национальный контур:
- ENG — Англия, Premier League.
- ESP — Испания, La Liga.
- ITA — Италия, Serie A.
- GER — Германия, Bundesliga.
- FRA — Франция, Ligue 1.

Отдельный еврокубковый контур:
- UCL — UEFA Champions League.
- UEL — UEFA Europa League.

Национальные соревнования публикуются в `docs/data.json`; еврокубки — в `docs/uefa-data.json`. Для семи соревнований ведутся отдельные отчёты по историческому качеству прогнозов и покрытию данных в `docs/strategy/`.

## Модель и контроль качества

- Национальная xG-модель использует до 30 последних матчей команды, half-life 12 и shrinkage 3, независимые распределения Пуассона.
- Прогнозы строятся хронологически. Результаты матчей той же календарной даты не должны попадать в историю для прогнозов на эту дату.
- Отсутствующий xG не подменяется нулём и не импутируется.
- Исторические оценки качества вероятностей (Brier Score, Log Loss, calibration) не равны ROI и не доказывают прибыльность ставок.
- Параметры калибровки, исследованные на holdout, остаются исследовательскими и не применяются к production-вероятностям без отдельного подтверждения.
- Котировки показываются только при точном сопоставлении матча, рынка и линии; неизвестные или устаревшие цены не восстанавливаются искусственно.

## Обновление данных

- `.github/workflows/update-data.yml` обновляет матчи, результаты и xG из PitchAPI, затем запускает обновление коэффициентов через API-Sport.
- `.github/workflows/update-odds-only.yml` обновляет BetBoom odds через API-Sport и добавляет неизменяемые временные снимки котировок каждые шесть часов.
- `.github/workflows/collect-uefa-betboom-snapshots.yml` обновляет еврокубковый контур, снимки BetBoom, paper-сигналы, расчёт результатов и feed каждые три часа.
- Секреты GitHub Actions: `PITCHAPI_API_KEY` и `API_SPORT_KEY`.
- Android-приложение получает национальный feed из `docs/data.json` и отдельный UEFA feed из `docs/uefa-data.json`.

## Архивы и диагностика

- `artifacts/odds-snapshots.jsonl` — временные снимки национальных BetBoom-котировок.
- `artifacts/uefa-betboom-odds-snapshots.jsonl` — снимки еврокубковых BetBoom-котировок.
- `artifacts/market-observation-results.jsonl` и UEFA paper-архивы — результаты наблюдений и paper-сигналов.
- `backend/diagnose_api_sport.py` — read-only диагностика доступности API-Sport, квоты и BetBoom-рынков.
- `backend/backtest_pitchapi.py` — воспроизводимый хронологический backtest национального xG-контура.
- `backend/predict_uefa_markets.py` и `backend/evaluate_uefa_paper_signals.py` — отдельная walk-forward оценка и paper-контур UCL/UEL.
- Подробные результаты и ограничения: `docs/strategy/seven-competition-calibration-report-2026-11-10.md`, `docs/strategy/seven-competition-data-and-odds-coverage-2026-11-10.md` и `docs/strategy/seven-competition-audit-2026-11-11.md`.

## Ограничения и безопасность

- Paper-сигналы и paper-ставки не размещают реальные ставки у букмекера.
- Нельзя считать вероятность, edge, EV или ROI подтверждёнными только по факту успешной сборки приложения.
- Историческая xG-выборка без исторических BetBoom-котировок позволяет оценить качество вероятностей, но не восстановить историческую доходность.
- Не запускать квотозатратные ручные сборы без необходимости; использовать существующие архивы и кеш.
