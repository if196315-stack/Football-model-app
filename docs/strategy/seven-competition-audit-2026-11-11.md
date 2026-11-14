# IQ Match — полный аудит семи соревнований
**Дата:** 2026-11-11  
**Проверяемая ветка:** `main`  
**Актуальный all-market audit:** [run 42914090260](https://github.com/if196315-stack/Football-model-app/actions/runs/42914090260)  
**Решение:** рабочие вероятности и пороги отбора не менялись; исправлены fixture mapping, очереди обновлений и воспроизводимость аудита.

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

Национальные данные публикуются в `docs/data.json`; еврокубки — в `docs/uefa-data.json`.

## 2. Архитектура и источники

- **PitchAPI** — матчи, результаты и shot-level xG.
- **API-Sport** — букмекерские коэффициенты **BetBoom**, секрет `API_SPORT_KEY`.
- Национальный контур: `backend/update_data_pitchapi.py`, `backend/update_data.py`, `backend/update_odds_api_sport.py`.
- UEFA контур: `backend/refresh_pitchapi_uefa_fixtures.py`, `backend/update_uefa_xg_history.py`, `backend/predict_uefa_markets.py`, `backend/collect_uefa_betboom_snapshots.py`.
- Национальный и UEFA feeds остаются раздельными; источники данных и архитектура не заменялись.
- Нет синтетических коэффициентов и fallback на другой букмекер.
- Реальные ставки не отправляются; приложение создаёт только paper bets.
- Старый ручной Odds API diagnostic workflow удалён; старый Odds API snapshot job из PitchAPI audit workflow удалён. Проверка всех активных workflow больше не находит `ODDS_API_KEY` или ссылок на старого odds provider. Legacy Python adapter-файлы сохранены для старых unit tests, но ни один production workflow их не запускает.

## 3. Воспроизводимая историческая проверка на main

Добавлен offline workflow: [Seven-competition model audit — run 42914090260](https://github.com/if196315-stack/Football-model-app/actions/runs/42914090260). Он восстановил кеш PitchAPI основной ветки, прогнал unit tests, экспортировал paired-xG выборку и выполнил walk-forward плюс bootstrap без сетевых запросов к букмекеру.

| Показатель | Результат |
|---|---:|
| Domestic paired-xG matches | 8 338 |
| UEFA paired-xG matches | 1 693 |
| Всего матчей | **10 031** |
| Walk-forward прогнозов | **9 798** |
| Не прогнозировались из-за недостаточной предшествующей истории | 233 |
| Training до 2026-07-01 | 9 179 |
| Holdout с 2026-07-01 | **619** |

Входная выборка:

| Код | Матчей |
|---|---:|
| ENG | 2 000 |
| ESP | 1 638 |
| FRA | 1 767 |
| GER | 1 305 |
| ITA | 1 628 |
| UCL | 823 |
| UEL | 870 |

Holdout по соревнованиям: ENG 98, ESP 116, FRA 89, GER 79, ITA 110, UCL 66, UEL 61.

**Сравнение с expanding-frequency baseline.** Baseline для каждого турнира использует только частоты исходов в предыдущих матчах с Laplace smoothing. Положительный skill означает, что xG-модель лучше baseline. На общей holdout-выборке все 15 независимых рынков имеют положительный Brier Skill и Log Loss Skill:

| Рынок | Brier Skill | Log Loss Skill |
|---|---:|---:|
| 1X2 | +10.14% | +8.09% |
| Double chance 1X | +12.01% | +9.48% |
| Double chance X2 | +13.92% | +10.67% |
| BTTS | +2.35% | +1.75% |
| Match OVER 1.5 | +3.53% | +3.41% |
| Match OVER 2.5 | +5.57% | +4.25% |
| Match OVER 3.5 | +3.55% | +2.84% |
| Home OVER 0.5 | +5.78% | +5.94% |
| Home OVER 1.5 | +11.06% | +8.34% |
| Home OVER 2.5 | +9.20% | +8.04% |
| Home OVER 3.5 | +11.38% | +14.79% |
| Away OVER 0.5 | +5.01% | +4.19% |
| Away OVER 1.5 | +8.04% | +6.10% |
| Away OVER 2.5 | +4.35% | +4.63% |
| Away OVER 3.5 | +4.85% | +11.75% |

Средний Brier Skill по 15 рынкам по соревнованиям: ENG 4.58% (15/15 рынков лучше baseline), ESP 8.94% (15/15), FRA 6.40% (14/15), GER 5.37% (12/15), ITA 9.12% (15/15), UCL 10.51% (15/15), UEL 6.45% (14/15). Слабые исключения: FRA Match OVER 3.5, GER Match OVER 1.5 и Away OVER 2.5/3.5, UEL Home OVER 3.5. Это сравнение с частотным baseline, не с букмекерским рынком и не с ROI.

**Протокол:** строгий chronological walk-forward; все матчи одной календарной даты прогнозируются до добавления результатов и xG этой даты. В историю попадает только paired, non-null xG. Пропущенный xG не заменяется нулём и не импутируется.

## 4. Калибровка: результаты и решение

Исследована регуляризованная multinomial vector scaling калибровка: `softmax(log(p)/temperature + class_bias)`. Обучение использует только данные до 2026-07-01; оценка — только holdout после этой даты.

17 выходных labels содержат **15 независимых разбиений исходов**. DNB_HOME и DNB_AWAY используют те же W/P/L события, что и 1X2, лишь с переименованными классами. Они оставлены в полном JSON-отчёте, но исключены из агрегированных league improvement counts, чтобы не удваивать вес 1X2.

`Δ` = raw score минус calibrated score; положительное значение означает улучшение. 95% CI рассчитан paired match-level bootstrap, 1 000 повторов, условно на уже обученном калибраторе.

| Рынок | Δ Brier | 95% CI для Δ Brier | Δ Log Loss |
|---|---:|---:|---:|
| 1X2 | +0.002568 | [+0.000654; +0.004459] | +0.003231 |
| Double chance 1X | +0.000961 | [-0.001212; +0.003005] | +0.000825 |
| Double chance X2 | +0.002621 | [+0.000902; +0.004462] | +0.003335 |
| BTTS | -0.000032 | [-0.001050; +0.000920] | -0.000044 |
| Match OVER 1.5 | +0.000196 | [-0.000813; +0.001119] | +0.000331 |
| Match OVER 2.5 | -0.000084 | [-0.001429; +0.001235] | -0.000215 |
| Match OVER 3.5 | -0.000024 | [-0.000939; +0.000906] | -0.000053 |
| Home OVER 0.5 | -0.000745 | [-0.002026; +0.000429] | -0.000722 |
| Home OVER 1.5 | +0.000406 | [-0.000004; +0.000799] | +0.000450 |
| Home OVER 2.5 | -0.000095 | [-0.000204; +0.000016] | -0.000117 |
| Home OVER 3.5 | -0.000084 | [-0.000165; -0.000009] | -0.000076 |
| Away OVER 0.5 | +0.000047 | [-0.000217; +0.000286] | +0.000038 |
| Away OVER 1.5 | -0.000130 | [-0.000378; +0.000120] | -0.000149 |
| Away OVER 2.5 | -0.000108 | [-0.000298; +0.000083] | -0.000294 |
| Away OVER 3.5 | -0.000166 | [-0.000408; +0.000052] | -0.000244 |

Только 1X2 и DC X2 имеют глобальный 95% CI полностью выше нуля. Для Home OVER 3.5 интервал полностью ниже нуля — калибровка ухудшает Brier. Интервалы остальных независимых рынков пересекают ноль.

**Среднее изменение по 15 независимым рынкам для каждого соревнования:**

| Соревнование | CI полностью >0 | CI полностью <0 | CI пересекает 0 | Δ Brier | Δ Log Loss |
|---|---:|---:|---:|---:|---:|
| ENG | 0/15 | 0/15 | 15/15 | +0.000348 | +0.000714 |
| ESP | 0/15 | 0/15 | 15/15 | -0.000800 | -0.001089 |
| FRA | 0/15 | 0/15 | 15/15 | +0.000639 | +0.001057 |
| GER | 0/15 | 1/15 | 14/15 | -0.000604 | -0.001178 |
| ITA | 2/15 | 0/15 | 13/15 | +0.003506 | +0.004160 |
| UCL | 4/15 | 0/15 | 11/15 | +0.007337 | +0.009722 |
| UEL | 0/15 | 4/15 | 11/15 | -0.006579 | -0.008970 |

Bootstrap CI условны на параметрах уже обученного калибратора: они не учитывают неопределённость обучения и временную/командную зависимость матчей. Это более строгая проверка, чем одни средние score, но не доказательство переносимости улучшения на будущие сезоны.

**Production-решение:** не переносить vector-scaling параметры в приложение и не менять пороги AI-отбора. ITA/UCL выглядят лучше в среднем, UEL — хуже; по отдельным рынкам большинства лиг интервалы пересекают ноль. Нужен новый независимый holdout.

## 5. UEFA: xG, прогнозы и BetBoom

Сохранённый UEFA xG archive содержит 1 693 paired-xG матча: UCL 823, UEL 870.

Последний успешный capture [run 42910703981](https://github.com/if196315-stack/Football-model-app/actions/runs/42910703981):
- 144 предстоящих fixtures (72 UCL, 72 UEL);
- 58 матчей сопоставлены с API-Sport;
- 28 имели активную prematch BetBoom board;
- 58 получили модельный прогноз (UCL 31, UEL 27);
- за capture добавлено 628 odds snapshots.

Текущий UEFA feed: 72 матча в каждом турнире; BetBoom odds присутствуют у 15 UCL и 13 UEL матчей; 11 UCL и 7 UEL paper-сигналов показаны в feed.

Накопленные архивы:
- 12 016 UEFA odds snapshots;
- 4 966 оценок сигналов: 234 решения BET и 4 732 REJECT;
- 18 уникальных paper bets (11 UCL, 7 UEL). Повторные BET-оценки на следующих снимках не равны новым уникальным ставкам;
- settlement archive пока не содержит завершённых результатов; ROI по этим ставкам ещё не оценён.

## 6. Национальные лиги: исправление сопоставления API-Sport

До исправления alias mapping сопоставлялось 138/156 fixtures. Диагностика показала 18 ложных несовпадений названий, а не отсутствующие матчи: 15 во Франции и 3 в Испании. Примеры: Nice/OGC Nice, Lyon/Olympique Lyonnais, Brest/Stade Brestois, Rennes/Stade Rennais, Marseille/Olympique de Marseille, Racing Santander/Real Racing Club.

Добавлены нормализованные официальные названия клубов и регрессионные тесты. Последний успешный updater [run 42911444590](https://github.com/if196315-stack/Football-model-app/actions/runs/42911444590) подтвердил **156/156 fixtures matched**.

| Соревнование | Eligible | Matched | С BetBoom odds |
|---|---:|---:|---:|
| ENG / Premier League | 40 | 40 | 20 |
| ESP / La Liga | 32 | 32 | 20 |
| ITA / Serie A | 30 | 30 | 20 |
| GER / Bundesliga | 27 | 27 | 18 |
| FRA / Ligue 1 | 27 | 27 | 18 |
| **Итого** | **156** | **156** | **96** |

60 сопоставленных матчей не имели подходящей BetBoom board/котировки на момент снимка. Это отсутствие предложения, а не ошибка сопоставления.

Последний национальный capture добавил 1 184 snapshots: 794 содержали model probability, 390 — нет. Handicap цены архивируются, но текущая xG-модель не выдаёт для них отдельную probability; они не становятся AI-сигналами только из-за наличия коэффициента.

**Team totals:** в этих 156 матчах пяти национальных лиг не найдено ни одной выбранной BetBoom team-total котировки. Parser распознал 763 активных full-time team-total market в общем API-Sport наборе fixtures. Это указывает на отсутствие таких рынков в целевом наборе матчей, а не на поломку классификатора. Не подставлять цены из других лиг или источников.

Национальный capture также не подтверждает доступность 1X2 BetBoom mapping; не считать его доступным. В проверенном срезе подтверждены double chance, totals, BTTS и handicap; team totals в целевых пяти лигах отсутствовали.

## 7. Расписание, котировки и paper фиксация

Была выявлена операционная проблема:
- scheduled domestic odds run 42885970297 завершился rebase conflict в `docs/data.json`;
- UEFA run 42884381446 был отменён, ожидая общий write concurrency group.

Исправлены workflow:
- отдельные concurrency groups для национальных и UEFA writers;
- `actions/checkout` явно использует актуальный `ref: main`, чтобы отложенный scheduled run не начинал работу со старого event SHA.

После исправления национальный и UEFA jobs успешно выполнялись параллельно: [domestic run 42910697943](https://github.com/if196315-stack/Football-model-app/actions/runs/42910697943), [UEFA run 42910703981](https://github.com/if196315-stack/Football-model-app/actions/runs/42910703981). Последующий domestic run 42911444590 также завершился успешно.

Национальный odds updater работает каждые 6 часов, UEFA collector — каждые 3 часа. В Android сохранена прежняя логика: timestamp источника сохраняется, а odds фиксируются в paper bet при создании; строгий пяти минутный фильтр в приложении не включён, поскольку при текущем расписании он почти полностью блокировал бы автоматическую фиксацию между обновлениями. UEFA collector по-прежнему отбирает source-fresh snapshots на момент самого capture. Историческая цена paper bet не переписывается последующими обновлениями.

## 8. Регрессия и сборка

- [Backend odds tests — run 42914090489](https://github.com/if196315-stack/Football-model-app/actions/runs/42914090489): **106 tests passed**, включая alias mapping Франции/Испании, all-market walk-forward и bootstrap CI.
- [Seven-competition model audit — run 42912729789](https://github.com/if196315-stack/Football-model-app/actions/runs/42912729789): успешны unit tests, экспорт paired-xG выборки, полный 7-competition backtest, bootstrap intervals и upload отчёта.
- [Android APK build — run 42912049905](https://github.com/if196315-stack/Football-model-app/actions/runs/42912049905): успешно; artifact `Football-Model-debug-apk`.

## 9. Риски и ограничения

1. Нет исторических BetBoom odds для всех 10 031 xG матчей. Нельзя восстановить достоверные исторические EV, CLV и ROI по всей выборке.
2. Новые snapshots ещё не дали достаточной завершённой выборки settlements; ROI по новым paper-сигналам пока не подтверждён.
3. Holdout 619 матчей распределён неравномерно; per-league N составляет 61–116. Bootstrap CI условны на fitted calibrator и не учитывают временную зависимость.
4. Независимая Poisson модель не моделирует зависимость низких счетов; альтернативные модели (например, Dixon–Coles) требуют отдельного walk-forward сравнения, а не замены без теста.
5. Разные рынки одного матча разрешены. Это может создавать коррелированную совокупную экспозицию; дневного exposure cap нет по принятой конфигурации. Сохраняются 1% банка на ставку, drawdown pause 5% и stop 10%.
6. Отсутствие team totals и 1X2 в текущем domestic BetBoom наборе ограничивает ассортимент AI-сигналов. Не исправлять это синтетическими котировками.

## 10. Итоговое решение

- Семь соревнований зафиксированы и проверяются одним воспроизводимым workflow.
- Fixture mapping Испании и Франции исправлен; сейчас 156/156 национальных матчей сопоставляются с API-Sport.
- Национальный и UEFA data pipelines больше не блокируют друг друга общей очередью; оба подтверждены успешными запусками.
- 106 backend tests и all-market seven-competition audit прошли.
- xG-модель превзошла expanding-frequency baseline по Brier и Log Loss на всех 15 независимых рынках глобально; в пяти соревнованиях — на всех 15 рынках, во FRA и UEL — на 14, в GER — на 12.
- Bootstrap показал, что глобальная калибровка уверенно улучшает Brier только для 1X2 и DC X2; для Home OVER 3.5 она уверенно ухудшает его; по большинству рынков CI пересекают ноль.
- Исследовательская калибровка не внедрена в production. Основной следующий критерий качества — settled BetBoom snapshots и price-aware forward evaluation: CLV, EV, ROI, drawdown и confidence intervals на будущей выборке.

## 11. Связанные материалы

- [Семичемпионатная калибровка 2026-11-10](./seven-competition-calibration-report-2026-11-10.md)
- [Покрытие данных и BetBoom 2026-11-10](./seven-competition-data-and-odds-coverage-2026-11-10.md)
- [UEFA walk-forward 2026-11-09](./uefa-walk-forward-report-2026-11-09.md)
- [Спецификация стратегии IQ Match](./iq-match-strategy-v1.md)
- [Reproducible all-market audit workflow](../../.github/workflows/seven-competition-model-audit.yml)


## 10. Повторная проверка GitHub Actions и production feed

### Seven-competition audit workflow

Run [42914090260](https://github.com/if196315-stack/Football-model-app/actions/runs/42914090260) завершился успешно (2026-11-10 22:23 UTC). Все шаги зелёные: восстановление кеша PitchAPI, unit tests all-market walk-forward/calibration, экспорт clean paired-xG cohort, семичемпионатный chronological audit и upload трёх артефактов.

Результаты непосредственно из лога этого run:
- Входной cohort: **10 031 матч**.
- Предсказания построены для **9 798 матчей**.
- Вход / прогнозы по соревнованиям: ENG 2 000 / 1 981; ESP 1 638 / 1 619; FRA 1 767 / 1 751; GER 1 305 / 1 288; ITA 1 628 / 1 611; UCL 823 / 766; UEL 870 / 782.
- Holdout после 2026-07-01: 619 матчей; по 619 наблюдений на каждый из 15 независимых рынков.
- Относительно expanding-frequency baseline на holdout Brier skill положительный для всех 15 рынков: 1X2 10.14%; DC 1X 12.01%; DC X2 13.92%; BTTS 2.35%; totals 1.5/2.5/3.5: 3.53% / 5.57% / 3.55%; home totals 0.5/1.5/2.5/3.5: 5.78% / 11.06% / 9.20% / 11.38%; away totals 0.5/1.5/2.5/3.5: 5.01% / 8.04% / 4.35% / 4.85%.
- По 1X2 Brier skill положительный в каждом соревновании: ENG 6.51%, ESP 9.12%, FRA 9.90%, GER 8.89%, ITA 17.15%, UCL 14.36%, UEL 3.62%. Наименьший holdout N — UEL 61, UCL 66, GER 79; не трактовать эти значения как точные рейтинги силы лиг.
- DNB_HOME/AWAY не являются дополнительными независимыми рынками: это та же W/P/L разбивка исхода 1X2 с push; отчёт корректно исключает их из числа независимых рынков и агрегатов.

Артефакт воспроизводимого запуска: [seven-competition-model-audit-8](https://github.com/if196315-stack/Football-model-app/actions/runs/42914090260/artifacts/13807499722), содержит clean cohort CSV, manifest и полный JSON отчёт.

### Read-only API-Sport diagnostic

Run [42909406938](https://github.com/if196315-stack/Football-model-app/actions/runs/42909406938) завершился успешно 2026-11-10 21:53 UTC. Диагностика не меняла данные и не создавала odds snapshots.
- API account usage: план 50 000 запросов/день; remaining 50 000 в account usage payload. Rate-limit header после диагностического прогона: 49 992 — выполнено 8 запросов.
- В качестве целевого примера взята ближайшая дата из национального feed — 2026-11-21. В ограниченном пятистраничном скане найдено 1 188 API-Sport fixtures.
- Все 5 проверенных ближайших модельных матчей Premier League были найдены в API-Sport по командам/дате; у всех пяти exact fixture details подтвердили BetBoom board и открытые prematch markets.
- Это точечная проверка только 5 ENG fixtures на одну дату, а не полное покрытие всех семи соревнований. Нельзя экстраполировать её на ESP/ITA/GER/FRA/UCL/UEL.
- В общем sample из 100 API-Sport fixtures BetBoom board был у всех 100; основные открытые рынки result/double chance/total — 100, BTTS/handicap — 97. Team-total slugs встретились у 18/100, но выборка смешанная и не даёт league-specific coverage для пяти целевых лиг.

### Интерпретация калибровки

Новый workflow подтверждает преимущество raw Poisson модели над expanding-frequency baseline на holdout по всем 15 независимым рынкам. Это не означает, что найденная vector scaling калибровка должна быть включена в production: paired bootstrap CI часто пересекает ноль, а per-competition calibration неоднородна. Калибровка остаётся исследовательской; production probability, edge, EV thresholds и paper-bet policy не менялись.

### Статус полного аудита

Подтверждено: исторический хронологический backtest воспроизводится, unit tests проходят, текущая read-only диагностика API-Sport отработала без изменения данных, а проверенные 5 ENG fixtures имеют BetBoom prematch boards.

Остаётся не подтверждено: актуальное league-by-league fixture/market coverage для всех семи турниров на одной временной точке, длительная price-aware доходность/CLV и устойчивость калибровки на следующем независимом временном окне. Для закрытия этих пунктов нужен отдельный ограниченный API-Sport/PitchAPI coverage run и дальнейшее накопление snapshot + settlement истории; это не следует подменять общим mixed-league sample.


## 11. Seven-competition API-Sport spot-check (2026-11-10 22:50 UTC)

A bounded read-only diagnostic was implemented and executed from PR #87 (not merged into production). It selected the nearest upcoming fixture in each of the seven feeds and checked exact fixture identity plus BetBoom board presence.

Run [42918105199](https://github.com/if196315-stack/Football-model-app/actions/runs/42918105199) completed successfully. The unit-test step passed and the diagnostic made 14 API-Sport requests.

| Competition | Selected fixture | Exact API-Sport match | BetBoom board | Open market slugs in exact detail |
|---|---|---:|---:|---|
| ENG / PL | Manchester City — Fulham | Yes | Yes | result, double chance, total, BTTS, handicap |
| ESP / PD | Athletic Club — Espanyol | Yes | Yes | result, double chance, total, BTTS, handicap |
| ITA / SA | Lazio — Lecce | Yes | Yes | result, double chance, total, BTTS, handicap |
| GER / BL1 | Bayern München — 1. FC Köln | Yes | Yes | result, double chance, total, BTTS, handicap |
| FRA / FL1 | Troyes — Le Havre | Yes | Yes | result, double chance, total, BTTS, handicap |
| UCL | Arsenal — Borussia Dortmund | Yes | Yes | main markets plus first/second-half markets and team totals |
| UEL | Beşiktaş — Hapoel Beer Sheva | Yes | Yes | main markets plus first/second-half markets and team totals |

**Spot-check result: 7/7 exact fixture matches and 7/7 BetBoom boards.** No target competition was missing from the sample. API-Sport account reported 50,000 daily limit, 174 requests used and 49,826 remaining at that diagnostic time.

Important limitation: the exact-fixture details response returned market stage as unspecified and did not expose the prematch-active flag, even though open market slugs and BetBoom boards were present. Therefore this test confirms fixture identity and board presence, but not that every listed market is eligible for the production prematch parser. Production ingestion still independently requires its existing prematch stage checks. No odds were written and no model/worker settings were changed.

The diagnostic and workflow are in [PR #87](https://github.com/if196315-stack/Football-model-app/pull/87), currently unmerged. The PR changes only read-only diagnostics and tests; it does not change the production model or ingestion.
