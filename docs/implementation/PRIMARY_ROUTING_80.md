# Source80: query-local primitive ordinals вместо сортировки Integer

**Актуализация:** full79session51226 завершён, exact variants79==78; target79 всё ещё занят
roads79session69123. Full80session59635 продолжается. Основной TOWARD correctness-fix вынесен
отдельно в [81](PRIMARY_ROUTING_81.md); для80 по-прежнему ожидается exact equivalence с79.

25.09.2026, `codex/routing-63-geometry`, `global-tree-80`. Отдельная behavior-preserving
оптимизация поверх79, не изменение геометрических норм или результата выбора маршрута.
**End-to-end ускорение и качество свежего portfolio80 пока не подтверждены.** Runtime61/VPS прежние.

## Изменение горячего пути

`ConstraintIndex.query` использует тот же STRtree, те же индексируемые envelopes, порог128
и исходные ordinal. Visitor собирает ordinal в query-local `int[]`, затем `Arrays.sort`
возвращает **тот же возрастающий исходный порядок**, включая разные вхождения одного Constraint.
Это важно для индексов навигационных вершин и разрешения равенств в поиске.

Для0–2попаданий используются scalar-поля без массива; массив создаётся на третьем попадании
и растёт по мере необходимости. Результаты0/1/2 остаются immutable, >2 — отдельный mutable
ArrayList, как прежде. Сборщик принадлежит одному вызову; общих буферов, ThreadLocal и кэша нет.

Не менялись расширение road envelope до точного радиуса, геометрические предикаты, допуски,
heading/traversal, фильтр препятствий, граф, tie-breaking и малый линейный путь. Старый
boxed comparator sort убран; Tree API используется через существующий ItemVisitor overload.

## Evidence и ограничения

- 5 новых постоянных тестов в `OfficialConstraintIndexTest`: 0/1/2/3 и границы роста до 4096 hits,
  1500random mixed queries, duplicate occurrences, последовательность больших/пустых/малых
  запросов, mutability/изоляция результатов,400параллельных запросов.
- Контроль — старое тело query на **том же production STRtree**, включая реально расширенные
  envelopes; сравниваются точные ссылки и порядок, не только множество ID.
- Scoped81PASS: index, road-clearance review, segment/JTS differential, prepared crossings,
  final-ДУ shared junction и terminal orientation.
- **Clean/fast Maven1227cases /1224PASS /0fail /0error /3scale skipped**,117классов.
  Исключены только original/corridor/compact классы. Snapshot исходников совпал с checkout.
- Web36 Vitest +37script tests, lint/typecheck PASS.
- Предшествующий bounded prototype:1614exact comparisons, медианы400hits34,282→17,383мкс,
  2500hits264,347→116,073мкс; при1hit allocation104→56Б. Mostly-empty case191→208нс.
  Это synthetic microprobe под параллельной нагрузкой, не общий выигрыш CPU/SLA. Фактическое
  распределение query hits и эффект именно production80 ещё не измерены.

Оптимизация не лечит3плохих угла из77/78. Исправление preservation79 проверяется независимо;
равенство80с79 будет доказывать отсутствие изменения результата, а не автоматически его качество.

## Живые расчёты / следующий шаг

| Проверка | Session | Замороженный target |
| --- | --- | --- |
| Clean/full80 | 59635 | `.tooling/source80-build.GwJekn/apps/api/target` |
| Clean/full79 | 51226 | `.tooling/source79-build.RQBOQ4/apps/api/target` |
| Roads79 | 69123 | **тот же target79**, runner `.tooling/scenario79.Vhd4HA` |
| Roads77 | 2599 | `.tooling/source77-build.FeShm8/apps/api/target`, runner `.tooling/scenario77.iPcahu` |

Не очищать target79 после full79 до завершения roads79. Full78 завершён, итоги в78.
Не перезапускать тихие живые процессы. Логи/result/diagnostic находятся в `.tooling/intake-20260925/`.

Дождаться79/80; сравнить `result.variants` точно, отдельно проверить geometry/sizing/depth/
economics/strict export,17of17, камеры, повороты и **engineering всех трёх ролей**. Существующие
fixture/helper assertions пропускают engineering cheapest — появление accepted bundle недостаточно.
Дождаться roads79, затем решать вопрос roads80 benchmark и дальнейшей геометрии. Если79 ещё
теряет качество, разобрать partially-invalid incumbent: whole-network fast path не сохраняет
поэлементно допустимые ветви внутри сети с другим нарушением. Не ослаблять нормы ради сохранения.

После подтверждения качества — реальные изображения и native HTTP→DB→export. `pwsh`/Docker
отсутствуют, Compose не проверен. Compact/G2/scale/R и вся цель открыты. На checkpoint оставалось
около2%лимита, поэтому исходники/проверки/продолжение сохраняются в Git до новых широких изменений.

## Evidence (ignored `.tooling`)

- `query80-focused.22Ai4s/focused.log`;
- `ordinal-scalar-probe.ZpZnNR/OrdinalQueryProbe.java`, `result.log`;
- `intake-20260925/source80-fast.log`, `source80-fast-reports/`, `source80-web.log`;
- `intake-20260925/source80-full.log`, ожидаемые `source80-result.json`/`source80-diagnostic.json`;
- `source79-full.log`, `source79-roads.log`, `source77-roads.log` для параллельных контрольных прогонов.
