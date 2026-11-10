# IQ Match — полный аудит семи соревнований
**Дата:** 2026-11-11  
**Область:** модель вероятностей, данные PitchAPI, котировки BetBoom через API-Sport, хронологическая валидация и эксплуатационные ограничения.  
**Режим:** аудит и исследовательская калибровка; production-параметры не менялись.

## 1. Зафиксированный набор соревнований

| Код | Соревнование | Контур |
|---|---|---|
| ENG | Англия — Premier League | Национальный |
| ESP | Испания — La Liga | Национальный |
| ITA | Италия — Serie A | Национальный |
| GER | Германия — Bundesliga | Национальный |
| FRA | Франция — Ligue 1 | Национальный |
| UCL | UEFA Champions League | Еврокубки |
| UEL | UEFA Europa League | Еврокубки |

Национальные данные публикуются в `docs/data.json`; UEFA feed — в `docs/uefa-data.json`. Эти контуры имеют отдельные сборщики, архивы и диагностические процедуры.

## 2. Проверенная архитектура данных

- PitchAPI — fixtures, результаты и shot-level xG.
- API-Sport — коэффициенты BetBoom.
- Национальный контур: `backend/update_data_pitchapi.py`, `backend/update_data.py`, `backend/update_odds_api_sport.py`.
- UEFA контур: `backend/refresh_pitchapi_uefa_fixtures.py`, `backend/update_uefa_xg_history.py`, `backend/predict_uefa_markets.py`, `backend/collect_uefa_betboom_snapshots.py`.
- Национальный feed и UEFA feed остаются раздельными.
- Котировки без точного соответствия fixture/market/line и свежести не должны превращаться в BET; отсутствие данных не заполняется синтетически.
- Реальные ставки не отправляются; paper-архивы предназначены для исследовательской оценки.

## 3. Историческая выборка и walk-forward

Последний сохранённый семичемпионатный отчёт калибровки (2026-11-10) фиксирует:
- 10 031 исторический матч в семи соревнованиях;
- 9 798 walk-forward прогнозов;
- 9 179 строк глобального calibration training;
- 619 holdout матчей с 2026-07-01;
- 17 исследованных исходов/рынков.

Протокол: строгий хронологический walk-forward; все матчи одной календарной даты прогнозируются до добавления результатов и xG этой даты. Калибратор обучается только на данных до 2026-07-01 и оценивается на holdout после этой даты.

## 4. Калибровка: результаты и решение

Испытана регуляризованная multinomial vector scaling модель вида `softmax(log(p)/temperature + class_bias)`. Глобальный результат на holdout неоднороден:

- Улучшение Brier и Log Loss отмечено для 1X2, double chance 1X/X2, match OVER 1.5, home OVER 1.5 и away OVER 0.5.
- Изменения большинства остальных totals/team totals близки к нулю или слегка отрицательны.
- BTTS практически не улучшился.
- Персональная по соревнованию калибровка улучшила средний score для ENG, FRA, ITA и UCL; ухудшила для ESP, GER и особенно UEL.

Средние raw-minus-calibrated изменения Brier по соревнованиям:
| Соревнование | Holdout N | Δ Brier | Δ Log Loss | Рынки с улучшением Brier |
|---|---:|---:|---:|---:|
| ENG | 98 | +0.000649 | +0.001300 | 11/17 |
| ESP | 116 | -0.000541 | -0.000791 | 7/17 |
| FRA | 89 | +0.000781 | +0.001205 | 11/17 |
| GER | 79 | -0.000418 | -0.000860 | 10/17 |
| ITA | 110 | +0.003916 | +0.004640 | 14/17 |
| UCL | 66 | +0.008697 | +0.012435 | 14/17 |
| UEL | 61 | -0.007639 | -0.010567 | 2/17 |

Положительная delta означает, что calibrated score ниже raw score и лучше. Значения — абсолютные score differences, не проценты.

**Решение:** не переносить эти калибраторы в production и не менять ими пороги AI-отбора. Выигрыш небольшой и нестабильный по рынкам/лигам; UEL особенно нуждается в дополнительной выборке. Сохранить текущие production-вероятности до независимого повторного holdout-теста.

## 5. UEFA отдельная диагностика

Исторический отчёт UEFA от 2026-11-09:
- 1 840 матчей в fixture archive, 1 696 завершённых;
- 1 696/1 696 завершённых матчей прошли xG diagnostic; 1 693 имели paired home/away xG;
- 1 551/1 696 получили walk-forward прогноз (91.45%); 145 пропущены из-за недостатка предшествующей истории;
- holdout: 850 прогнозов (UCL 430, UEL 420).
- Double chance 1X/X2 превосходили chronological expanding-frequency baseline по Brier в обеих лигах.
- BTTS и некоторые высокие team-total линии имеют слабую/малую выборку; для отдельных P≥60% подмножеств N крайне мало.
- Нет основания утверждать прибыльность: исторические BetBoom odds для всей xG-истории отсутствуют.

## 6. Покрытие BetBoom / API-Sport

Последний сохранённый coverage snapshot (2026-11-09 22:05 UTC):

| Соревнование | Eligible fixtures | API-Sport match | Fixture с выбранными BetBoom котировками |
|---|---:|---:|---:|
| ENG | 40 | 40 | 20 |
| ESP | 32 | 29 | 18 |
| ITA | 30 | 30 | 20 |
| GER | 27 | 27 | 18 |
| FRA | 27 | 12 | 7 |
| **Итого** | **156** | **138** | **83** |

UEFA snapshot: 144 upcoming fixtures (72 UCL, 72 UEL), 44 сопоставлены с API-Sport, 28 имели активные prematch BetBoom boards; 44 модельных прогноза (31 UCL, 13 UEL).

Выявленные ограничения:
- FRA имеет заметно более низкий API-Sport fixture matching (12/27) — это приоритет для проверки alias/fixture identity.
- В национальном контуре на проверенном срезе не было team-total quotes, хотя парсер распознавал известные полные рынки; это было классифицировано как отсутствие котировок в полученных BetBoom boards, а не доказанная ошибка парсера.
- UEFA архив содержит team-total рынки.
- 1X2 BetBoom mapping в национальном архиве не подтверждён; не заявлять его доступность.
- Отсутствие котировки — не повод подставлять коэффициент из другого источника или синтезировать цену.

## 7. Диагностика реализации и контрольные точки

Проверенные исходники содержат:
- ограничение истории команды 30 матчами, half-life 12, shrinkage 3;
- независимую Poisson модель с ограничением λ в диапазоне 0.05–4.5;
- отсечение будущих и same-date результатов в walk-forward;
- отсутствие xG imputation;
- отдельный UEFA predictor и отдельный paper-signal evaluator;
- regression suites для API-Sport ingestion, market parsing, xG, UEFA predictions, snapshots, settlement и feed.

Проверка регрессии: backend workflow run [42904112065](https://github.com/if196315-stack/Football-model-app/actions/runs/42904112065) завершился успешно на backend revision `7a44b29885e62c6eff3a33b932df2589b279cec0`; прошли компиляция ingestion/model scripts и весь набор regression tests, включая API-Sport, xG, backtest, UEFA predictions, odds snapshots и settlement.

Остаются следующие production gates:
1. Провести свежий read-only API-Sport diagnostic для текущей квоты, fixture matching и BetBoom board coverage (без лишних повторных запросов).
2. Повторить семисоревновательный chronological holdout на новой временной выборке с фиксированной датой отсечения и сравнить с сохранённым отчётом.
3. Проверить отдельно FRA fixture mapping и причины 18 unmatched domestic fixtures.
4. Накопить более длинную BetBoom snapshot историю с settlement и провести price-aware forward evaluation: exact line, odds timestamp, vig removal, EV, CLV, ROI, drawdown и confidence intervals.
5. Утвердить минимальные sample-size gates до использования калибровки или market-specific threshold tuning.

## 8. Что не подтверждено

Этот аудит не даёт права заявлять, что модель — «лучшая» или гарантированно прибыльная. Нет полного исторического ряда BetBoom odds на все xG матчи, а 619 holdout матчей распределены неравномерно по турнирам. Результаты калибровки — исследовательские, не production-проверка ROI. Успешная компиляция/сборка Android не подтверждает качество прогнозов.

## 9. Связанные отчёты

- [Семичемпионатная калибровка 2026-11-10](./seven-competition-calibration-report-2026-11-10.md)
- [Покрытие данных и BetBoom 2026-11-10](./seven-competition-data-and-odds-coverage-2026-11-10.md)
- [UEFA walk-forward 2026-11-09](./uefa-walk-forward-report-2026-11-09.md)
- [Спецификация стратегии IQ Match](./iq-match-strategy-v1.md)
