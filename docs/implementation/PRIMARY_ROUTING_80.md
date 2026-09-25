# Source80: query-local primitive ordinals вместо сортировки Integer

**Актуализация:** full80session59635 завершён; `result.variants` точно равны79 (Node deep equality).
Full79session51226 тоже завершён; target79 всё ещё занят roads79session69123. Основной TOWARD
correctness-fix вынесен отдельно в [81](PRIMARY_ROUTING_81.md); его full81 пока выполняется.

25.09.2026, `codex/routing-63-geometry`, `global-tree-80`. Отдельная behavior-preserving
оптимизация поверх79, не изменение геометрических норм или результата выбора маршрута.
**Эквивалентность свежего portfolio80 подтверждена; ускорение и качество не приняты.** Runtime61/VPS прежние.

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

## Итог полного контрольного прогона

Full80: **1231 cases /1227 PASS /1 прежний compact failure /0 errors /3 scale skipped**,
120классов. Единственное падение — `OfficialCorridorControlRecoveryTest`: длина compact draft
не укладывается в1860м. Original fixture613,702с, concave4,838с. Предыдущий79 —595,410с;
нагрузка параллельных процессов отличается, поэтому ни ускорение, ни регрессия времени этим
сравнением не доказаны. Нужен изолированный повторяемый benchmark.

Проверено точное равенство **всех** `run.result.variants` между79 и80, без исключения полей:
геометрия, topology/ДУ/глубина/стоимость/диагностика не изменились.17/17подключений у каждой роли,
geometry/sizing issues пусты, глубина/экономика и строгий экспорт3ролей проходят. Balanced:
2194,257м/14новых камер; shortest2194,034м/14; cheapest2073,965м/11. У cheapest остаются
3плохих угла (`EXPERT_BEND_ANGLE_OUT_OF_RANGE`), у двух других ролей engineering issues пусты.
Это контроль отсутствия изменения результата, **не quality acceptance**. Старые fixture assertions
не требуют пустого engineering cheapest; этот пробел не скрываем за зелёным dataset-тестом.

## Живые расчёты / следующий шаг

| Проверка | Session | Замороженный target |
| --- | --- | --- |
| Clean/full81 (живой) | 87519 | `.tooling/source81-build.Ndryzg/apps/api/target` |
| Roads79 | 69123 | **тот же target79**, runner `.tooling/scenario79.Vhd4HA` |
| Roads77 | 2599 | `.tooling/source77-build.FeShm8/apps/api/target`, runner `.tooling/scenario77.iPcahu` |

Target80 свободен; отчёты сохранены в `source80-full-reports/`. Не очищать target79
(`.tooling/source79-build.RQBOQ4/apps/api/target`) до завершения roads79. Full78 завершён, итоги в78.
Не перезапускать тихие живые процессы. Логи/result/diagnostic находятся в `.tooling/intake-20260925/`.

Дождаться81 и проверить **engineering всех трёх ролей**,17of17, камеры, полную геометрию/ДУ/
глубину/экономику/strict export. Затем roads и native smoke. Actual TOWARD call-site и повтор
порчи допустимых ветвей разобраны в81; partially-invalid incumbent остаётся отдельным риском.
Не ослаблять нормы ради сохранения. Появление accepted bundle само по себе недостаточно.

После подтверждения качества — реальные изображения и native HTTP→DB→export. `pwsh`/Docker
отсутствуют, Compose не проверен. Compact/G2/scale/R и вся цель открыты. На checkpoint оставалось
около2%лимита, поэтому исходники/проверки/продолжение сохраняются в Git до новых широких изменений.

## Evidence (ignored `.tooling`)

- `query80-focused.22Ai4s/focused.log`;
- `ordinal-scalar-probe.ZpZnNR/OrdinalQueryProbe.java`, `result.log`;
- `intake-20260925/source80-fast.log`, `source80-fast-reports/`, `source80-web.log`;
- `intake-20260925/source80-full.log`, `source80-full-reports/`, `source80-result.json`/`source80-diagnostic.json`;
- `source79-full.log`, `source79-roads.log`, `source77-roads.log` для параллельных контрольных прогонов.
