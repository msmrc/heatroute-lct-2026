# Source78: физическое направление ввода и разметка пересечений

25.09.2026, `codex/routing-63-geometry`, `global-tree-78`. Отдельное correctness-исправление
после77. **Не исправляет доказанную регрессию качества portfolio77 и не является готовым релизом.**
Runtime61/VPS сохранены. G2, compact-control, native/Compose/scale и R остаются открытыми.

## Что исправлено

Terminal builder строит координаты demand→root, затем сохраняет их root→demand. Прежний
поиск проверял угол входа в road/tram до обращения. Воспроизведены: допуск неправильного
40° входа; объезд155,146м вместо допустимой прямой151м в зеркальном случае; неправильная
стена ОКС; сохранённый угол секции90° вместо фактических60°. На замороженном77 новый
18-test набор дал4PASS/14RED. Это не предположение по картинке.

`RouteTraversal` явно задаёт физическое направление относительно координат построения:

- Старые generic API остаются `AS_GIVEN`; demand-пути передают `REVERSED`. Порядок узлов,
  A*, preference, epsilon/ties и возвращаемые координаты не переворачиваются.
- Неизменяемое направление живёт в `ConstraintIndex`; только road/tram visibility и
  whole-line assessment используют физическое направление. Запрещённые области, отступы,
  shared-node identity и проверка полного special не отключаются.
- Нормаль сохраняет наружное представление start→exit. Её пригодность, navigation margin
  и независимая проверка ожидаемой нормали учитывают входящий ввод. Финальный валидатор
  по-прежнему проверяет настоящие сохранённые координаты, не доверяя флагу поиска.
- Sections заново строятся на физически ориентированной целой линии, затем возвращаются
  в порядок построения. `RoutePath.reversed()` не изменён; угол входа и special не теряются.
- Направление проведено через обычный/no-egress terminal search, heading/depth recovery,
  final-ДУ repair, engineering regularization, prepared terminal corridors и retained approaches.
  Уже сохранённая root→demand геометрия в retention остаётся `AS_GIVEN`.
- Обратный generic поиск не читает/не пишет старый per-run route-cache: в его ключе нет
  физического направления. Heading/shared-junction search остаётся uncached. Новый кэш не вводится.

### Ввод, заканчивающийся внутри дороги

У входящего suffix фиксирован конец demand. Нельзя достроить защитные3м за ним или применить
outgoing prefix к обратной линии. Если наружный порт находится внутри уже встреченной дороги,
прямое продолжение прослеживается до реальной границы исходного полигона, а не до обрезки
«порт+3м». Иначе неправильная ближайшая стена остаётся допустимым кандидатом, хотя полный
поиск затем всегда отказывает.

Независимое ревью нашло связанную цепочку: первая дорога заканчивается на10м, защита требует
прямую до13м, следующий компонент начинается на12,5м. Он тоже неизбежен. Исправление удерживает
необрезанную границу обязательного прямого продолжения и расширяет её транзитивно. Компоненты,
начинающиеся после этой границы (контроли13,5м/20м), не навязывают лишний угол. Старый outgoing
код и порядок арифметики его продолжения сохранены. Допуски не увеличены.

### Добавление подхода к камере

`withCheckedDemandSuffix` сохраняет фактический нормальный префикс своего ОКС и проверяет
всю расширенную линию заново. Generic suffix не мог применяться к такой линии: он запрещал
сам собственный ввод. Льгота остаётся только на префиксе, не переносится на чужие области
или повторный вход в здание; изменение обязательной нормали при добавлении dogleg отклоняется.

## Проверки конечного source

- **50 новых постоянных тестов:**18 planner/validator/sections orientation,
  19 router propagation/cache-isolation/retention/own-suffix,13 low-level terminal direction.
  Включены catalog boundary, road/tram, отражение/поворот/UTM, защита demand, несколько
  компонентов, cancellation, неизменность геометрии, отрицательные cases.
- **Конечный clean/fast Maven:1216cases /1213PASS /0fail /0error /3scale skipped**,117классов.
  Исключены только три долгих original/corridor/compact класса. Snapshot побайтно совпал с source.
- Web36 Vitest +37script tests, lint/typecheck PASS.
- Independent geometry overlay:175PASS до последней коррекции suffix; scoped own-suffix
  review8PASS. Это не проверка всей конечной сборки. Последний reviewer-контрпример с12,5м
  повторно запущен родительским агентом на конечных Maven classes: false/true/true для
  12,5/13,5/20м; три постоянных regression-теста включены в конечный Maven.

Промежуточный1192-case gate имел один реальный failure неправильного nearest normal;
1209-case gate ещё не включал последние три continuation-теста и четыре own-suffix-теста.
Не подменять ими конечные1216cases. Это correctness checkpoint, не подтверждённое ускорение.

## Живые проверки и границы scope

- **Clean/full78session46700**, `.tooling/source78-build.X5TXbQ/apps/api`, лог
  `.tooling/intake-20260925/source78-full.log`; `source78-result.json` после assertions,
  `source78-diagnostic.json` до них. Target заморожен до завершения. Accepted78 ещё нет.
- **Fresh roads77session2599**, `.tooling/scenario77.iPcahu`, использует frozen
  `.tooling/source77-build.FeShm8/apps/api/target`; лог `source77-roads.log`.
  Не перезаписывать target и не перезапускать тихий процесс. Engineering cheapest проверять
  дополнительно: существующий helper и dataset test не включают его в quality assertions.
- Full77 и roads75 завершены, а не продолжают работать; их итоги в [77](PRIMARY_ROUTING_77.md).
- `pwsh`/Docker отсутствуют. Прямые Java11/web gates не заменяют Compose smoke,
  новый native HTTP→DB→export или нагрузочные проверки. Live runtime/VPS не менялись.

Это направление **terminal-путей**, не исправление всех направленных связей графа. Отдельно
остались generic nonterminal reverse callers (pair trunk, rewired tie-in, relocation/link approaches)
и undirected corridor trunk links, у которых направление задаётся после rooting. Требование
обоих углов не является допустимой заменой этой работы. Инвентарь ниже сохраняет точные callers.

## Следующий приоритет: сохранять уже допустимую геометрию без потери качества

Bounded audit замороженных75/77 подтвердил причину изменения двух ранее правильных ветвей:
`hasMandatoryEgress`, независимый валидатор и terminal guard без avoidance проходят. С контекстом
ранее принятых рёбер отказывает только синтетический поисковый буфер соседа:
`JoinedRouteContact.protectedRemainder.intersects(candidate)`; пересечение самих осей — ровно
общий узел. На75 repair тоже не проходил, но возвращал null из-за заблокированного узла и
сохранял исходную ветвь. На77 repair начал работать и заменил её, добавив плохие углы.

Replay: ветви15/2 —192,877→175,985м и20,472→19,055м; official geometry issues0до/после,
expert angles0→2. Это не весь portfolio: третий плохой угол ветви4 и смена shortest ещё не объяснены.

Следующий отдельный bugfix: воспроизводящий тест `PRESERVE_VALID` и согласование preservation
с авторитетной final-ДУ проверкой полной геометрии/сети. Сначала подтвердить статус0,20м
поискового буфера относительно официальных требований. Сохранить буфер для поиска новых замен,
все официальные отступы и shared-node-only intersections; не убирать целиком incident edges.
После — свежий расчёт всех трёх ролей с проверкой engineering cheapest, затем performance.

Гипотеза ускорения `ConstraintIndex.query` (primitive ordinals вместо boxed sort) описана в77:
пока только1048exact comparisons и microbench, **не включена в production78**.

## Evidence (ignored `.tooling`)

- `terminal-orientation78-tests-FChO6e/final-red77.log` — исходные14RED;
- `terminal78-demand-suffix.LJCWsk/focused66.log` —18+19+29PASS;
- `direction78-review-N2wfbV/REVIEW.md` — полный инвентарь direction callers;
- `direction78-geometry-gT9J2B/EVIDENCE.md` —175geometry checks;
- `demand-suffix78-review-Bl1hhw/probe.log` —8scoped suffix checks;
- `suffix78-review-VgRrWK/CONTINUATION_REVIEW.md` — reviewer-контрпример;
- `terminal78-focused.rCVPdl/final-continuation-review.log` — контроль последнего исправления;
- `intake-20260925/source78-final-fast.log`, `source78-final-fast-reports/`, `source78-web.log`;
- `retention77-audit.qOrCQm/RetentionPredicates77.java`, `LegacyRetention75.java` — trace regression77.
