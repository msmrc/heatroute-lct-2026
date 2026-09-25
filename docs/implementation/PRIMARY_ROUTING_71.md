# Доводка71: сокращение уже допустимых вводов

Дата:25.09.2026. Ветка `codex/routing-63-geometry`, work in progress.
Последний отправленный checkpoint: `704e440` / source70. Full compiled70 ещё выполняется;
новые исходники71 не меняют его замороженные классы. Runtime61/master/VPS сохранены.

## Воспроизведённый пробел

`regularizeEngineeringDraft` и `CorridorJunctionAssignment` имеют repair-only контракт:
если углы уже допустимы, короткие альтернативы ввода не запрашиваются. Менять это на всём
дорогом portfolio ради каждой локальной попытки не требуется.

Диагностический replay принятого69 через существующий `CorridorTerminalRouter.localAlternatives`
показал2допустимых сокращения, без общего поиска. После полного finish/строгого export:
2090,416→2068,785м,11новых камер сохранены,23→22поворота,0expert issues,
284949407,70→283006390,18₽,все17подключены/depth complete.1,330с.
Это не fresh71, не live job и не доказательство полной оптимальности. Источник:
`.tooling/intake-20260925/source70-terminal-shortening.log/json`, ignored.

## Реализованное изменение

Отдельный `FinalizedTerminalShortener`: ограниченная доводка выбранных готовых сетей,
после переноса камер, а не каждого portfolio draft. Локальные предложения ориентированы
по существующему стволу, сохраняют геометрию камеры/корня/потребителя. Каждый изменённый
кандидат проходит полный finish (ДУ/геометрия/depth/economics) и инженерную оценку.
Без потери подключений, увеличения длины/цены/поворотов/камер и без новых нарушений.
Исходные роли остаются в итоговом отборе. Готовые ответы между расчётами не сохраняются.
Бюджет на вариант:2прохода,24листа/проход,до8путей на лист,не более48finish-вызовов;
путь≤1000координат. Порядок leaf IDs детерминирован. Исходные источники ДУ и координаты
потребителей взяты из текущего импорта, не из reference. Перед finish проверяется геометрия
до/после миллиметрового округления; старый depth profile не переносится. Cancel и неожиданные
exceptions не поглощаются. Это консервативный локальный поиск, не обещание глобального минимума:
в этой итерации даже допустимое изменение ДУ/других участков после finish отклоняется.

Planner integration regression уже дал реальный RED: допустимое80м дерево остаётся80м
вместо60м при том же корне/камере/двух потребителях. После начальной ошибки имени isolated
runner она исправлена; `source71-integration-red.log` содержит именно assertion80≠60,
не compile/runner failure. Тест проверяет оба режима глубины и независимый validator.
Дополнительный случай содержит настоящий park: короткий прямой ввод через него недопустим.

## Проверенный checkpoint

Изолированный javac/Java11:125PASS/0FAIL (`source71-focused.log`). Включены16новых component
tests,2planner integration tests, существующие relocation/attachment/selector/terminal/port/
retained-input тесты. Component также имеет отдельный genuine no-op RED, затем16GREEN
(`.tooling/source71-terminal.Hzcjes/red.log`, `green.log`). Подтверждены80→60м,оба режима
глубины, бюджет/cancellation, нет мутации контроля/потери coverage, неизменные камеры/корни,
отказ от плохих координат/пересечений/подорожания/роста поворотов и неполного depth.

Replay всех трёх принятых69ролей через production `shortenSelectedTerminals`,2,520с:
shortest/cheapest2068,786м/11камер/22поворота/0expert/283006479,92₽;balanced остаётся
2192,523м/14камер/25поворотов/302839881,84₽. Его предложение2192,300м не вытеснило контроль
в инженерном отборе. Все17/17/depth complete и strict export3ролей PASS.
В отличие от первоначальной probe, production использует исходную точку потребителя из features,
а не округлённый node; здесь принят отдельный результат2068,786м, не2068,785м.
Evidence `source71-selected-terminal-replay.log/json`.
Первый вызов helper забыл этап rank и получил ожидаемый экспортный отказ; helper исправлен,
production rank/export не менялся. Это по-прежнему **не fresh71**.
Web36/scripts37/lint/typecheck PASS (`source71-web.log`).

## До принятия

- [x] Component/integration focused tests, budgets/cancellation/no-loss/ошибочные предложения.
- [x] Replay фактических трёх ролей через новый production helper, strict export.
- [ ] Full clean/fresh71 после завершения и snapshot70; не переносить gate70 на71.
- [ ] Новый roads+kindergarten fresh/export; source69 был1054,672с/17of17/3rolesPASS.
- [ ] Сопоставимые изображения и метрики всех ролей; не выдавать replay за fresh.
- [x] Web36/scripts37/lint/typecheck.
- [ ] Локальный native gate; Compose/scale отдельно.

Compact-control≤13камер/<1860м, G2, общее качество/скорость, R-этапы и общая цель открыты.
