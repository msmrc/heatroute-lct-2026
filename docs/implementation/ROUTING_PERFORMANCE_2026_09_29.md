# Оптимизация расчёта — рабочие замеры 29 сентября 2026

Статус: ночная оптимизация зафиксирована на R15; дальнейшие эксперименты приостановлены.
По поручению пользователя от 29 сентября публикуется только performance-снимок R15:
последующие правки построения маршрута source104/105 остаются локальными и в него не входят.
Повторные тесты и расчёты ради публикации не запускались; ниже сохранены результаты
проверок соответствующих неизменяемых снимков, а не текущего рабочего дерева.
Полные baseline, R3, R5, R6, R8, R9, R10, R12, R13 и R14 прошли; все 2 505 значений
доменного результата каждого кандидата совпали точно. Последний полный wall-clock замер:
**2 032,870 → 905,058 с (−55,48%, около2,246×)**. R14 расширяет доказанные raw cones;
R13 добавляет guarded raw reject
к внутренним angle cones R12, подготовке входного направления R10 и array-sequence R11.
Corridor gates R8–R13 PASS. Повторная
контрольная пара без вспомогательной нагрузки остаётся открытой. R8 HTTP/job/export PASS:
1721,878 с, первая попытка; это отдельный замер, не пара к in-memory baseline.
R10 build/fast/micro/control/full PASS. Новый layout-эксперимент существует только в ignored
evidence, не в production; на official footprints выигрыш около1% и неоднороден, поэтому
он не принят. **Matched default2D API-пара PASS:2554,766262 →1658,202680 с,
−35,09%,1,5407×; все2092 значения результата совпали точно.** Оба17/17, оба strict exports200.
Это единичная пара; повторяемость пока не установлена. R11 закончил работу exit0/noOOM,
но точный результат/время теста ещё не собраны: исправленный наблюдатель требует отдельного
разрешения на повтор. R12 (внутренние angle cones) прошёл fast2447/controls6/full:
997,725 с, exact2505,17/17 оба. Дополнительный выигрыш к R10 —9,73% в одном запуске;
повторяемость не установлена. Следующий R13 raw-reject также прошёл full:
968,891 с, exact2505, оба17/17; дополнительный выигрыш к R12 —2,89% в этом запуске.
Первый R13 default2D API job завершился: **1531,358837 с (25:31,36), −40,06%**
к прежнему API baseline2554,766262 с. Exact2092, оба17/17, strict exports200;
это сравнение с ранее измеренной базой, повторяемость не установлена. Временные API/db
остановлены с сохранением томов. R14 wide raw cones прошли isolated36997-case probe,
первый build/fast2452/0/0/3 и controls6/0/0/0. Первый полный R14 PASS:905,058 с,
exact2505, оба17/17, exit0/noOOM; ещё−6,59% к R13 в одном запуске, не repeatability.
Рабочее дерево уже содержит отдельный R15: удалена неиспользуемая начальная
загрузка feature windows при depth=false. Это изменение не входит в текущий
замер R14; первый R15 build/fast2454/0/0/3 и controls6/0/0/0 PASS. Первый R15 API
завершился05:25:01.595314 UTC, attempt1: **1464,763484 с (24:24,76), −42,67%**
к прежнему API baseline. Exact2092, оба17/17, strict exports200; повторяемость не установлена.

| Снимок | Полный тест, с | Снижение относительно baseline | Точный результат |
| --- | ---: | ---: | --- |
| baseline 052dedb | 2032,870 | — | эталон сравнения |
| R3 | 1686,424 | 17,04% | 2505 значений совпали |
| R5 | 1351,748 | 33,51% | 2505 значений совпали |
| R6 | 1313,941 | 35,37% | 2505 значений совпали |
| R8 | 1166,131 | 42,64% | 2505 значений совпали |
| R9 | 1147,438 | 43,56% | 2505 значений совпали |
| R10 | 1105,241 | 45,63% | 2505 значений совпали |
| R12 | 997,725 | 50,92% | 2505 значений совпали |
| R13 | 968,891 | 52,34% | 2505 значений совпали |
| R14 | 905,058 | 55,48% | 2505 значений совпали |

## База и условия

- Перед изменениями выполнены `git fetch origin` и обычное объединение `origin/master`.
  Зафиксирован baseline `052dedb466e653efc6ffb69c32b67177664bf38f` (remote `33eae27`).
- Прочитан `docs/implementation/NETWORK_SOLVER_MANIFEST.md`: новый CP-SAT pipeline ещё
  не допущен в `stable`. Здесь ускоряются общие геометрические вычисления, инженерные
  ограничения и выбор маршрута не ослабляются.
- Конкурсный файл `datasets/official/lct-2026.geojson`, SHA-256
  `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`, 144 объекта, 17 ОКС.
- Полный baseline: `OfficialDatasetRoutingTest#officialDatasetProducesValidatedObstacleAwareVariants`,
  **depth=true**, in-memory input, включая независимые проверки готовых вариантов и strict export.
  Это не HTTP/job-замер и не прямое сравнение с предыдущими 3 796,71 с при depth=false.
- Отдельный контейнер `heatroute-benchmark-baseline-052dedb`, лимит 2 CPU / 4 GiB,
  JVM `-Xms256m -Xmx2560m -XX:ActiveProcessorCount=2`, JFR `settings=profile`.
  Runtime и Maven берутся из фиксированного project build image. Работающий локальный API
  и его сохранённые результаты не заменяются этим тестовым контейнером.
- Snapshot исходников, Dockerfile, профили, отчёты тестов и микроэксперименты находятся
  в игнорируемом `.tooling/optimization-20260929/`.
- Первая подготовка offline runner завершилась до запуска теста из-за отсутствующего
  `surefire-junit-platform:2.22.2`; зависимость добавлена в образ. Реальный baseline
  стартовал в контейнере 28 сентября в 21:27:03 UTC.

## Профиль исходного кода

Начальная фаза `independent`: 476 078 мс, 4 083 поиска, 2 549 769 суммарных узлов
графов, 75 047 305 проверок пар. В промежуточном JFR — 18 779 ExecutionSample:

| Ближайший метод проекта в стеке | Доля сэмплов |
| --- | ---: |
| `PreparedSegmentIntersection.intersectsBoundary` | 34,30% |
| `LongByteTable.get` | 13,06% |
| `LongByteTable.put` | 3,41% |
| `BuildingWallNormals.candidates` | 12,80% |

Включающая доля `normalEgressTowards` — около 20%. Включающие доли пересекаются;
складывать их нельзя. Это профиль начальных фаз, не доли полного расчёта.

Промежуточный R3: `independent` 349 746 мс вместо 476 078 мс (−26,5%, около 1,36×).
Все четыре накопленных счётчика этой фазы совпали: 4 083 поиска, 2 549 769 узлов,
75 047 305 пар, 1 027 051 017 отсечённых углов. Это проверка только пройденной фазы,
не доказательство полной эквивалентности. В её JFR основная нагрузка после изменений
остаётся в boundary index/predicate; следующий эксперимент проверяет точное отсечение
внутренних прямоугольников, лежащих целиком по одну сторону линии отрезка. Он не входит
в неизменяемый snapshot R3 и пока не прошёл проверки/замер.

## Изменения и локальная проверка

1. `PreparedSegmentIntersection`: прежний STRtree после построения преобразуется
   в неизменяемые массивы границ и переходов к следующему поддереву. Поиск сохраняет
   порядок обхода, касания, отверстия и точные JTS orientation/intersection predicates.
   Убраны рекурсивный обход Boundable, списков и ленивых bounds в горячем пути.
2. `SegmentVisibilityTable`: соседние ID второго узла хранятся страницами по 64 байта.
   Не более 32 768 страниц, затем точный sparse fallback; на явно разреженных ключах
   выполняется переход полностью к hash-таблице. Существующий предел миллиона
   сегментов, точные ключи, направленность и разделение контекстов сохраняются.
3. `PreparedNormalEgressMemo`: повторно используется полная подготовка нормалей для
   одного точного окна препятствий. Snapshot копирует геометрию и атрибуты; любое изменение
   их содержимого, состава или порядка объектов сбрасывает кеш. Ключ включает raw XYZ,
   ДУ и направление. Target-sort остаётся прежним, набор допустимых стен не сокращается.
   Лимиты: 512 объектов / 100 000 координат окна, 256 ключей / 8 192 координаты ответов;
   превышение переводит запрос в исходный некешируемый путь. Для подклассов правил
   сохраняется первоначальная виртуальная диспетчеризация без обхода переопределений.

На снимке R1 прошли **109 тестов, 0 failures/errors/skips**, Maven 7,315 с.
Включены сравнения с JTS, глубокий MultiPolygon с отверстиями, направления road/tram,
изоляция динамических ограничений, порядок навигационных узлов, search priority,
случайные ключи и миллион записей таблицы. После этого адаптивный sparse fallback
доработан: R1 не подтверждает окончательное состояние.

На снимке R2 широкий backend выявил одну несовместимость: оптимизация обходила
переопределённый `normalEgressCandidates`, используемый bounded-retention regression.
После восстановления исходного вызова для подклассов правил снимок **R3** прошёл
**2 409 тестов, 0 failures/errors, 3 штатных scale skips**, Maven 52,266 с.
Исключены только три долгих класса: `OfficialDatasetRoutingTest`,
`OfficialCorridorDatasetTest`, `OfficialCorridorControlRecoveryTest`.
Это включает окончательный adaptive table, exact geometry index, normal memo,
инженерные негативные случаи, exported-result guards и Linux CP-SAT native/model tests.
Отчёты: `.tooling/optimization-20260929/evidence/r3-fast-reports/`.
Образ: `heatroute-benchmark:candidate-r3`, digest
`sha256:a7f7861b180b2796ca87e11f585ae00b81a7fa1a08e9ef46620e3d9095595d77`.
Frontend: `pnpm test` — 36 Vitest + 37 Node, `pnpm lint`, `pnpm typecheck` — PASS.

Изолированный эксперимент: 6 млн чтений 500 тыс. ключей после прогрева.
Плотные последовательные пары: старая таблица 150–155 мс, новая 18–21 мс (7,3–8,4×).
Равномерно случайные 64-битные ключи: старая 140–153 мс, новая 155–171 мс;
на таком разреженном рисунке остаётся небольшой проигрыш. До адаптивного fallback
проигрыш был примерно двукратным. Эти числа **не являются ускорением всего алгоритма**.
Первая полная пара R3 совпала точно; микроускорения не переносились в отчёт как общее ускорение.

### Следующий эксперимент R4 (ещё не подтверждён полным расчётом)

Внутренний STRtree-узел отсекается, если два support-угла его bounding box строго
по одну сторону линии запроса по JTS Orientation. Касания и нулевые знаки идут прежним
путём. Shortcut применяется лишь к поддеревьям от 64 entries; координаты вне безопасного
численного диапазона (в том числе mixed-scale/subnormal) также сохраняют прежний обход.
Это условие выбора вычислительного пути, а не новое инженерное ограничение.

В изолированном probe 100 000 фиксированных запросов × 3 повтора:

| Форма | R3 после прогрева | R4 с порогом | Совпадение числа пересечений |
| --- | ---: | ---: | --- |
| Малое buffered-препятствие | 21–25 мс | 20–30 мс | да |
| Полигон с отверстием | 24–28 мс | 24–27 мс | да |
| Разреженная сетка полигонов | 234–243 мс | 111–117 мс | да |

Без порога маленькое препятствие замедлялось; этот вариант не оставлен. Микрозамер
не является доказательством общей скорости и не заменяет differential JTS-тесты.
Снимок R4 проверен отдельно: **2 411 Java tests / 0 failures / 0 errors / 3 scale skips**,
Maven 42,665 с. Добавленные differential-тесты включают почти касания, длинные диагонали,
отверстия, вогнутые формы, предельные и смешанно-масштабные координаты. Полный конкурсный
R4 и сравнение его скорости/результатов пока не выполнены.
Образ `heatroute-benchmark:candidate-r4`, digest
`sha256:281fd1264258e2e3204a7b837877dbf39abe400abc9f792e593723e62dae8cf6`;
отчёты `.tooling/optimization-20260929/evidence/r4-fast-reports/`.

Дополнительно R4: 5 dataset/control regression tests PASS (concave official OKS egress,
corridor control recovery, legal straight terminal inputs), Maven около 80 с;
отчёты `evidence/r4-dataset-control-reports/`. Полный corridor oracle пока не пройден.
Микроэксперимент с предварительным `envelope.covers(start)` перед point locator не
показал выигрыша и удалён; в рабочем коде восстановлен проверенный snapshot R4.

Отдельный синтетический normal-egress probe: 144 здания, 17 исходных точек,
1 000 меняющихся целей. На поздних прогретых итерациях исходная подготовка 576–1 115 мс,
memo 26–81 мс, контрольные суммы ID/XY совпадают. Это **не** конкурсный benchmark:
плотность reuse в реальных оконных запросах может отличаться.

Baseline выполняется одновременно с короткими сборками/проверками на других CPU;
это источник шума первого wall-clock сравнения. Последовательная повторная пара без
вспомогательной нагрузки нужна перед окончательным выводом об ускорении.

## Следующий кандидат R6: пороговое расстояние до utility

Профиль хвоста R3 указывал 11,08% nearest samples в `utilitySegmentAllowed`. Его первый
full-distance нужен только для решения `distance >= cutoff`; точный минимум затем
не используется. Эксперимент с `DistanceOp(..., Math.nextDown(cutoff)).distance()`
сохранил 105 000 решений, включая exact/nextDown/nextUp границы. JTS прекращает поиск
только при найденном расстоянии строго ниже cutoff; равенство требует полного поиска.
На синтетической buffered-wave поздние раунды: full 150–154 мс, threshold 140–145 мс.
На простой линии и длинной ломаной устойчивый существенный выигрыш не установлен.
Это не общий benchmark; кандидат ещё не принят по полному расчёту.

Более агрессивный вариант `!DistanceOp.isWithinDistance(..., nextDown(cutoff))` отвергнут:
зафиксирован false accept при full distance `96.71828982837421` и cutoff
`96.71828982837422` из-за envelope-rounding. Evidence: `evidence/distance-within-micro.txt`.
Рабочий R6 использует только обычный DistanceOp с ранней остановкой; нечисловые,
бесконечные и неположительные пороги сохраняют старый full-distance путь. Все проверки
special, врезок, углов и последующих частей трассы остаются прежними. R5 benchmark
продолжает исполнять неизменяемый снимок, не включающий эти более поздние изменения.

R6 также имеет узкий two-segment shortcut: для двух 2-point LineString вызывается та
же `Distance.segmentToSegment`, которую использует полный JTS DistanceOp, без временных
списков/массивов. Координаты за пределами ±2^400, NaN/Infinity и нечисловой результат
идут общим путём; данные не кешируются. Повторный probe подтвердил 105 000 решений;
на простой линии большинство раундов direct занимали 0,37–0,52 мс против full 1,84–6,45 мс,
но был шумный direct-раунд 4,34 мс, поэтому коэффициент общего ускорения не выводится.
Evidence: `evidence/distance-direct-micro.txt`. Итоговый regression snapshot R6 прошёл
**2422 tests / 0 failures / 0 errors / 3 skips**, Maven 41,969 с. Шесть новых тестов
проверяют ULP-границы, multi-geometries, degenerate/empty/mutable sources и численные
fallbacks, а прежние utility/camera/turn/axis/export проверки остались в общем наборе.
Образ `heatroute-benchmark:candidate-r6`, digest
`sha256:c9baf5ca79722e2d14f682cbaeb8d753a6876612f5846c83919234bce19cac15`;
отчёты `evidence/r6-fast-reports/`. Полный R6 PASS за **1313,941 с** (−2,80% к R5),
все 2505 значений совпали точно. Контейнер работал 23:07:51.424–23:29:51.195 UTC
28 сентября, exit 0, без OOM. Фазы: independent 328,632 с; shared 454,242 с;
group_spines 103,328 с; finalized_portfolio 420,423 с; plan 1306,627 с.
Итоговые счётчики прежние: 10918 поисков, 6643264 узла, 191991053 проверки пар,
2946023594 отсечения углов. Evidence: `candidate-r6-reports/`, `candidate-r6-full.log`,
`candidate-r6-comparison.txt`, probe/demo/JFR. Запуск шёл после R5, не параллельно ему.
Короткие микроэксперименты и сборки шли параллельно ранним полным замерам;
повторная финальная пара без вспомогательной нагрузки по-прежнему нужна.

## Последующие кандидаты R7/R8

Промежуточный JFR R5, только samples после 22:59 UTC: 10160 samples, nearest
`baseConstraints` 23,36%, `utilitySegmentAllowed` 18,82%, `sameGeometry` 15,99%,
`sameSequence` 3,43%. Это срез финализации, а не доли полного wall-clock.

R7 объединяет стандартное XY/raw сравнение геометрии в один проход. Метаданные,
порядок/число колец и компонентов, все raw XY/Z/M и layout остаются в ключе допуска.
Нестандартные geometry/sequence/coordinate implementations, NaN/Infinity XY сохраняют
исходную JTS-проверку, включая identity semantics. Независимый test-only pre-change oracle
проверяет эквивалентность. Fast R7: **2426 tests / 0 failures / 0 errors / 3 skips**,
40,966 с; image `candidate-r7`, digest
`sha256:0c009117daae4d2a20f85e0dad41fc8b3d76bc12912591df3801c9ef2137f8e9`.
В последовательном probe 144000 сравнений копий реального файла: поздние R6 раунды
72–74 мс, R7 55–58 мс. Полный отдельный R7 пока не запускался.

R8 fast gate **PASS: 2428 tests / 0 failures / 0 errors / 3 skips**, Maven 53,728 с;
default preparation budget повышен 512→1024 entries и
100000→400000 reserved coordinates, LRU и строгая инвалидация остаются прежними.
Probe 88 реальных ограничений × 8 ДУ: старый budget готовил заново 704 объекта на каждом
цикле; 1024/400000 — 704 при прогреве, затем 0, удерживая 271136 reserved coordinates.
Повторная подготовка в этом probe сокращается с 0,7–1,2 с до 7–35 мс; это не общий routing
benchmark. Retained heap после GC для 704 entries около 5,6 МБ; показатель приблизительный,
не включает будущую ленивую инициализацию всех индексов и не является верхним лимитом RSS.
Evidence: `geometry-equality-r6/r7.txt`, `constraint-cache-*-probe.txt`, `r7-fast-reports/`.
R8 отчёты: `r8-fast-reports/`; image `candidate-r8`, digest
`sha256:238c7907a3d6371465280631bae305d5ed51e36216f467336f94ee9495462cbf`.
Новый generic working-set test превышает оба старых лимита и подтверждает reuse всех 768
constraints без повторной подготовки; явный малый budget по-прежнему вытесняет записи.
Runtime `heatroute-api:perf-r8` собран (digest
`sha256:d0b60f4ef2ac30aac98975c6e98c536f3b4e0990f128ce9e55f3f9781316506c`); API evidence ниже.
Пять дополнительных dataset/control tests PASS (49,753 с): concave official OKS egress,
corridor control recovery и legal straight terminal callbacks; отчёты
`r8-dataset-control-reports/`. Полный R8 **PASS за 1166,131 с** (−42,64% к baseline,
−11,25% к R6), все 2505 значений результата совпали точно. Контейнер
23:34:30.860–23:54:02.653 UTC 28 сентября, exit 0, без OOM. Прежние лимиты 2 CPU/4 GiB,
тот же JFR; во время R8 не запускались сборки, микрозамеры и другие тяжёлые проверки.
Фазы independent 322,226 с; shared 460,468 с; group_spines 91,996 с;
finalized_portfolio 284,060 с; plan 1158,752 с. Финализация быстрее R6 на 32,43%.
Поиски 10918, узлы 6643264 и angle_pruned 2946023594 совпали с baseline/R6;
evaluated_pairs уменьшились с 191991053 до 191929684 (reuse кеша, без изменения результата).
Evidence: `candidate-r8-reports/`, `candidate-r8-full.log`, `candidate-r8-comparison.txt`,
probe/demo/JFR. Полный corridor oracle PASS за31,267 с: default finish=true, 8 кандидатов,
включая depth/economics, chamber/turn/final geometry и strict exporter. Запущен только после
exit 0 и точного сравнения R8. Evidence: `r8-corridor-reports/`, `r8-corridor-full.log`,
`r8-corridor.json`. Его время не сравнивается с baseline: одновременно читался завершённый
JFR. Последующие production-изменения R9 описаны отдельно и этим запуском не проверены.

## Изолированный runtime R5

Production Dockerfile собран на неизменяемом source snapshot R5. Отдельный Compose project
`heatroute-perf-r5` поднялся на `127.0.0.1:18000`, не меняя основной API/web/БД. Образ запускается
как uid 10001, Temurin 11.0.28+6 на Ubuntu 22.04. Readiness: PostGIS 3.5 и CP-SAT 9.15.6755 `ok`;
это включает реальный known-optimum native solve. Bundled official import: valid, 144 features,
633402 bytes, zero errors, SHA-256 `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`.
Evidence `evidence/r5-runtime-readiness.json`, `r5-runtime-import.json`. Настроенный OpenAPI
`/api/v1/openapi` вернул HTTP 200 / OpenAPI 3.0.1 (`r5-runtime-openapi.json`); ошибочная первая
проверка `/api/openapi.json` вернула ожидаемый для отсутствующего пути 404 и не считается gate.
После проверки только временный проект остановлен, его volumes сохранены. Обычный route job
и экспорт ещё не запущены; эти результаты не относятся к более позднему R6.

## Завершённая API-проверка R8

В `.tooling/optimization-20260929/` подготовлены `runtime-r8.override.yaml` и
`runtime-r8-evidence.ps1`. Override использует уже собранный R8 image, heap 256–2560 MiB,
2 active processors и сохраняет container-local Java/JNA temp paths из production image.
Compose запущен отдельным project `heatroute-perf-r8` на `127.0.0.1:18000`;
основной пользовательский API/web не заменён. Runtime healthy; CP-SAT/PostGIS readiness,
OpenAPI и bundled import PASS. Единственный depth=true/stable run создан
28 сентября в23:58:35.401524 UTC: `7a673880-b5e3-401e-b02c-ef254aa5543b`,
job `71e93cf5-e525-4938-bfc2-789a2ef53b23`. API run/job completed, attempt1;
completed_at 29 сентября 00:27:17.279571 UTC. Server elapsed **1721,878047 с**
(28 мин 41,878 с, включая очередь). Оба варианта подключают17/17; cheapest2192,535 м,
balanced2242,303 м. Экспорты HTTP200: FeatureCollection на60 и74 features соответственно.
Сохранены raw run/job JSON, timing, оба GeoJSON и runtime log под `evidence/r8-runtime-*`.
После сохранения свидетельств временный Compose остановлен; volumes не удалены.

Скрипт разделяет `Start`, `Observe` и `Export`. До POST run создаётся исключающий повторную
отправку marker; timeout наблюдения не создаёт новый расчёт. HTTP JSON сохраняется без
пересериализации чисел; polling идёт по сохранённым run/job UUID. Server elapsed определяется
по persisted created/completed timestamps, включает очередь и не подменяется временем опроса.
`Export` требует completed run/job и сохраняет каждый вариант отдельно. Проверка envelope
GeoJSON не объявляется независимым геометрическим допуском: полный in-memory gate и
сравнение доменного результата остаются отдельными свидетельствами.

Start/Observe/Export выполнены. Два дефекта локального evidence-скрипта исправлены без
изменения приложения: DateTime от ConvertFrom-Json теперь обрабатывается типизированно,
а byte[] тела application/geo+json декодируется UTF-8 перед сохранением/разбором.
Первичный файл с числовыми значениями байтов сохранён отдельно как diagnostic evidence;
его ошибка envelope не была ошибкой HTTP export. После исправления оба экспорта PASS.

### Почему API и in-memory timing не являются прямой парой

`OfficialFeatureLoader` импортирует метрическую геометрию через PostGIS
`ST_Transform(..., 32637)`, а `OfficialFeatureRepository` читает WKB и сортирует по
`feature_id`. `OfficialDatasetRoutingTest.loadOfficialFeatures` использует Proj4J и
исходный порядок GeoJSON. Это разные подготовки входных чисел/порядка; равенство файла
и EPSG само по себе не означает побитовое равенство metric geometries. JVM runtime
11.0.28+6 также отличается от build/test JVM11.0.30+7.

У API R8 уже в independent отличаются counters: evaluated_pairs71391539 против75047305
в in-memory R8; angle_pruned1027799922 против1027051017, при одинаковых4083 searches.
Это наблюдение, а не установленная причина всех расхождений; дополнительно во время API
шли ограниченные R9 build/fast/probe. Не трактуем будущую разницу API-vs-test результата
как регрессию кеша и не переносим42,64% на production HTTP. Для production-shaped speed/
route comparison нужна baseline/final API пара с тем же PostGIS, JVM, parameters и лимитами.
Cross-path comparator нашёл только8 различий числового представления (4E+1→40,
4.4E+7→44000000, 4.9E+7→49000000, 1E+7→10000000). Геометрия, порядок, подключения
и остальные поля совпали. Строгий scale-sensitive comparator закономерно FAIL;
он не изменялся для сокрытия отличий. Evidence: `r8-runtime-inmemory-comparison.txt`.

Проверена версия исполняемого кода: все36 классов восьми изменённых routing families из
запущенного JAR побитово совпали с candidate-r8 benchmark image. Устаревший runtime для
этих классов исключён (`r8-runtime-bytecode-comparison.txt`). Попытка diagnostic attach
из JDK sidecar завершилась AttachNotSupportedException через10,5 с; сам API run остался
running/attempt1 и не перезапускался. Следующие runtime-профили следует включать при старте
JVM; failed attach не является результатом расчёта или blocker для остальных проверок.

## Следующая гипотеза, без изменения R8

Read-only проверка `OfficialObstacleRouter.shortestPath` и `OfficialRouteDeflectionRules`
выделила повторную нормализацию одного и того же вектора в переборе переходов. Возможное
точное ускорение: готовить входное направление один раз на state, исходные — лениво в
ограниченном row-cache одного поиска. Сохраняются ascending `next`, rounded millimetre
differences, visibility/special-turn gates и порядок hypot → divide → asin → atan2 → tolerance.
Нельзя заменять деление умножением на reciprocal, вводить FMA или угловую аппроксимацию.
NaN/Infinity/zero/overflow/subnormal и границы углов требуют differential oracle против
текущего predicate. Это пока гипотеза: production не менялся, микрозамер нового
подхода не получен и ускорение не заявляется. Анализ выполнил существующий
`routing_hotspots` (gpt-5.6-terra, medium), без тестов и сборок.

Свежий R8 JFR покрывает1167 с и содержит40300 ExecutionSample: ближайшие project frames —
BoundaryIndex.intersects18,30%, meetsUtilityClearance13,00%, BuildingWallNormals.candidates10,04%,
compareStandardSequence7,22%. Отдельные57719 NativeMethodSample нельзя смешивать с CPU wall-time:
94,03% относятся к FileInputStream.readBytes (ожидание ввода test runner), а не вычислению;
3143 samples приходятся на allowsTurn. Поэтому отсутствие trig в ExecutionSample само по себе
не доказывает дешевизну predicate. Для следующего шага выбраны два независимых read-only
исследования более крупных measured hotspots: boundary index и wall normals. Evidence:
`candidate-r8-profile.txt`, `candidate-r8-native-profile.txt`; микропрофиль не меняет маршрут.

## R9: общий исходный этап RAW/PADDED, полный differential PASS

`OfficialRouteGeometryRules` выделяет прежний withNavigationMargin-map в отдельный helper,
не меняя порядок, формулу и terminal-leg admission. `PreparedNormalEgressMemo` использует
общий исходный nearest-legal stage только при полном standard-rules/catalog/crossing guard.
Custom implementations сохраняют прежнюю подготовку и отдельные mode keys.

Стандартная запись хранит immutable RAW и optional immutable PADDED списки под одним base key
(точные XYZ-биты точки, ДУ, traversal). Оба списка учитываются в прежнем координатном бюджете;
upgrade вычитает размер старой записи перед LRU eviction. Если пара oversized, RAW сохраняется,
а PADDED возвращается без кеширования. Инвалидация окна, defensive list containers, отмена и
разделение обязательной длины/поискового запаса остаются обязательными.

Добавлен `PreparedNormalEgressSharedStageTest`: 5 focused tests с несколькими сценариями —
convex/concave/hole/multipolygon, оба порядка RAW/PADDED и направления, raw-bit exact oracle,
reuse identity, budgets/eviction, mutation/order, XYZ/ДУ/traversal isolation, cancellation и
custom hooks. Основной агент усилил identity-invalidation и nonempty assertions до запуска.
Исходники зафиксированы в candidate-r9, digest
`sha256:ddb06b4879526699c1824b1352e8884c32fbcf5fc1c5c53c60fc949ee536b7a4`.
Fast gate PASS: **2433 tests / 0 failures / 0 errors / 3 existing skips**, Maven44,520 с;
новые5 tests PASS. Evidence: `r9-fast-reports/`, `r9-fast.log`, `r9-build.log`.

Следующий dataset/control gate PASS:6 tests, 0 failures/errors/skips. Он включает полный
`OfficialCorridorDatasetTest` с default8 finish-кандидатами, depth и strict export,
concave official OKS egress и control recovery. Corridor class4 tests за26,328 с;
весь Maven gate1:11. Evidence: `r9-control-reports/`, `r9-controls.log`, `r9-corridor.json`.
После его завершения29 сентября00:37:32.570 UTC запущен полный R9 в контейнере
`heatroute-benchmark-candidate-r9` (2 CPU/4 GiB, прежние JVM/JFR parameters).
Полный R9 PASS за **1147,438 с** (19 мин7,438 с), exit0, без OOM;
контейнер завершён00:56:45.291 UTC. Точный comparator:2505 значений совпали с baseline,
cheapest17/17 2192,535 м /296013322,58 ₽; balanced17/17 2242,303 м /304973207,18 ₽.
Фазы independent313864 мс, shared452978 мс, group_spines92292 мс,
finalized_portfolio281091 мс, plan1140226 мс. Итоговые counters совпали с R8:
searches10918, nodes6643264, evaluated_pairs191929684, angle_pruned2946023594.
По одному запуску −43,56% к исходному baseline и −1,60% к R8; последний небольшой
выигрыш без повторной контрольной пары не объявляется устойчивым. Во время R9 не было
параллельных сборок/тестов/микрозамеров; шла только лёгкая работа с исходниками и логами.
Evidence: `candidate-r9-full.log`, `candidate-r9-reports/`, `candidate-r9-comparison.txt`,
`candidate-r9-state.json`, probe/demo/JFR. Strict final validation/export входят в full test.

Actual-fixture probe проверил272 RAW/PADDED пары (17 точек ×8ДУ ×2traversal), четыре прохода
с чередованием первого режима. Сериализованные IDs/порядок/raw XYZ-биты R8/R9 совпали точно:
SHA-256 `6ae09233550d9dbedcf018c18051a5e25f8efe12ec97594b97ce409afd024835`.
R8 проходы6204/4522/2362/2356мс; R9 —4132/2629/2507/2151мс. Из-за JIT и параллельного
API общий выигрыш по этому короткому probe не заявляется; полный differential приведён выше.
Реальный потребитель обоих режимов — `OfficialRouteValidator.validateSpatialConstraints`:
сначала mandatory RAW, затем validationNormalEgress PADDED для той же точки/ДУ/REVERSED
в одной ValidationSession. RoutingEnvironment обычно использует только PADDED.
Сборка/fast/probe R9 шли параллельно интеграционному API R8: его HTTP timing не является чистой
контрольной парой. Изменения локальных исходников не влияют на уже запущенный R8 image.

## R10: подготовка входного направления, полный differential PASS

Во время полного R9 замера в рабочем дереве подготовлен следующий узкий эксперимент.
`OfficialObstacleRouter.shortestPath` вычисляет нормализацию входного луча и его вклад
в rounding tolerance один раз на state, а не на каждый next. Исходящий луч проверяется
каждый раз. Порядок обхода, visibility/minimum-segment/special-turn gates, миллиметровые
разности, деление, atan2 и точная сумма asin сохранены. Нет аппроксимации угла или
общего кеша направлений; invalid/zero/nonfinite лучи по-прежнему отвергаются.

Добавлен независимый pre-change oracle `PreparedIncomingDirectionTest`:7 tests,
включая фактические tolerance-adjusted границы, ±ULP, оба знака, rounded UTM differences,
seeded scales, subnormal/overflow, reuse и отдельный junction mode. Тестовый файл подготовил
`segment_index` (gpt-5.6-terra, high); координатор усилил реальные миллиметровые разности
и границы с допуском до запуска. Production изменил только координатор.
Снимок candidate-r10 создан из R9 с заменой ровно двух production-файлов и добавлением
одного test-файла. `r10-after-r9.ps1` ожидает exit0 полного R9 и точный результат от
его существующего observer, затем однократно запускает build→fast→synthetic microprobe.
Повторного R9, автоматических retries и параллельной тяжёлой нагрузки нет.
После exit0 и exact-match R9 runner последовательно выполнил build/fast/micro.
R10 image digest `sha256:879a871f381c2d2db7aea7a856a505e701a04d5593a9b3dc68a0bf5b2dd72b7f`.
Fast PASS: **2440 tests /0 failures /0 errors /3 existing skips**, Maven40,507 с;
новые7 tests PASS. Evidence: `r10-build.log`, `r10-fast.log`, `r10-fast-reports/`.
Synthetic direction probe проверил32768 разных пар; итоговые counts всех проходов совпали.
Шесть чередующихся legacy/prepared замеров: legacy285,716–296,164 мс,
prepared232,309–248,588 мс (примерно15–20% быстрее внутри этого kernel).
Это не full-route speed claim. Evidence: `r10-incoming-probe.txt` и исходник probe.
Dataset/control gate PASS:6 tests/0 failures/errors/skips, Maven1:09. Он включает полный
default8 corridor/depth/export и concave/recovery сценарии. Evidence: `r10-control-reports/`,
`r10-controls.log`, `r10-corridor.json`. После этого29 сентября01:00:15.836 UTC запущен
полный R10: контейнер `heatroute-benchmark-candidate-r10`,2 CPU/4 GiB, прежние heap/JFR.
Полный R10 PASS за **1105,241 с** (18 мин25,241 с); завершён01:18:46.717 UTC,
exit0, без OOM. Все2505 значений совпали с baseline: оба17/17, геометрия/порядок/ДУ/
глубина/экономика прежние. −45,63% к baseline, −3,68% к R9 по одному запуску;
статистическая устойчивость дополнительного выигрыша ещё не проверена.
Фазы independent306033 мс, shared424317 мс, group_spines85702 мс,
finalized_portfolio281799 мс, plan1097853 мс. Итоговые counters совпали с R8/R9:
10918 searches,6643264 nodes,191929684 evaluated_pairs,2946023594 angle_pruned.
Финализация практически не ускорилась; выигрыш пришёл из поисковых фаз, как ожидалось.
Evidence: `candidate-r10-reports/`, `candidate-r10-full.log`, `candidate-r10-comparison.txt`,
`candidate-r10-state.json`, probe/demo/JFR. Во время полного R10 не было параллельных
сборок/тестов/microprobes; работа с исходниками/evidence была лёгкой и read-only для runtime.

## Отдельный эксперимент layout индекса, вне production

В `evidence/boundary-layout-src/PreparedSegmentIntersection.java` подготовлена локальная
альтернатива: четыре раздельных массива min/max заменены одним interleaved массивом
minX/maxX/minY/maxY. DFS-порядок, skipAfterSubtree, orientation/finite guards, locator,
fallback и правила касания не меняются. Это только изменение расположения bounds в памяти.
Исходный production-файл и immutable R10 snapshot не изменялись.

`BoundaryLayoutProbe.java` переиспользует прежние synthetic buffered/concave/hole/grid shapes
и добавляет buffered polygon footprints официального fixture. Каждый boolean ответ сохраняется
в бинарный файл для поэлементного сравнения, отдельно от timings. Shell runner использует baseline
и packed classpath overlay, не заменяя классы API. После R10 и baseline-runtime build выполнена
последовательная пара в1 CPU/1 GiB, без параллельной тяжёлой нагрузки. **Все1280000 ответов
совпали побайтно**,92 shapes (4 synthetic +88 official buffered polygon footprints).
Evidence: `boundary-layout-baseline.txt`, `boundary-layout-packed.txt`, оба `*-results.bin`,
`boundary-layout-comparison.txt`, `boundary-layout-timing-summary.json`.

По сумме медиан rounds3–6 всех shapes:726,015→634,802 мс (−12,56%), но почти весь
выигрыш даёт первый synthetic shape с сильным JIT-переходом у packed. На88 official shapes:
426,151→421,7345 мс (−1,04%), быстрее только31/88, в отдельных случаях заметное замедление.
Малые длительности и JIT не доказывают полезного выигрыша на production queries.
Поэтому layout **не перенесён в production**; этот trial не объявляется новым ускорением
и не запускает полный routing regression. Файлы эксперимента сохранены для воспроизводимости.

## Согласованная API-пара и следующие проверки

Подготовлен, но ещё не выполнен `matched-api-plan.md`: baseline/final на одном persisted
PostGIS-импорте, одинаковых runtime/JVM/ресурсах, fresh API JVM и default depth=false.
Дедупликация импорта подтверждена source read; каждый POST run создаёт новый run/job UUID.
Evidence-script параметризован по prefix/depth/expected import ID; default R8 guard сохранён.
Это подготовка протокола, не результат API-пары. Существующее depth=true evidence не смешивается
с будущим default2D timing. Производственные исходники в этом шаге не менялись.
`runtime-baseline-after-r10.ps1` ожидает terminal exit0 и exact-match R10, затем соберёт
baseline052dedb API image из исходного snapshot по тому же production Dockerfile.
После успешного R10 baseline runtime собран: digest
`sha256:214c556aebd999f530fd8c57b20ebf3e870e05161a62c10052b39a2420510923`.
Evidence: `baseline-runtime-build.log`, `baseline-runtime-image.txt`. Для final выбран R10;
его production image собран из проверенного snapshot:
`sha256:4053f2ed87c1bab9aa769088be9224fff3a676150eb7ed2de34b3285b9c109ab`.
В отдельном Compose project `heatroute-perf-pair` запущен baseline depth=false/stable:
run `e5af5016-8356-4c03-81ec-c44759bde15b`, job `d6cb3483-03fb-48aa-8840-228b7c73b073`,
created `2026-09-29T01:26:06.285737Z`, completed `2026-09-29T02:08:41.051999Z`.
Baseline completed/attempt1 за **2554,766262 с (42 мин34,766 с)**, без ошибки.
Оба17/17: cheapest2192,535 м/296013322,58 ₽, balanced2242,303 м/304973207,18 ₽.
Строгие HTTP exports: оба200,60/62 features. Raw run/job, timing, GeoJSON и runtime log
сохранены под `evidence/api-pair-baseline-2d-*`.
Фазы baseline по runtime log, мс: independent480310/shared673462/group_spines223543/
finalized_portfolio1176998; planner total2554314. Финализация занимает около46% этого
planner timing. Счётчики: searches10918/nodes6643264/pairs185255616/angle_pruned2947137529.
Это наблюдение именно API depth=false, не замена счётчиков in-memory/depth=true.
API имеет2 CPU/4 GiB, heap256–2560 MiB; фактический image digest проверен.
Сборки, тесты и probes во время тихой API-пары не запускаются. Основной стек не меняется.

После успешных exports остановлен и заменён только pair API; БД/import сохранены.
R10 фактический digest и лимиты проверены, JVM started `2026-09-29T02:10:05.721272458Z`.
ExpectedImportId guard подтвердил тот же import `a829c660-8394-4e2d-832d-23a027a3d018` до POST.
Финальный run `8e5fa18e-5fd8-4d78-b84c-a3d4ea3c9927`, job
`25b54b2e-0e78-4e16-9390-f2d63803145f`, created `2026-09-29T02:10:52.587703Z`,
completed `2026-09-29T02:38:30.790383Z`, depth=false/stable/min0.7/max10.
Completed/attempt1 за **1658,202680 с (27 мин38,203 с)**, без ошибки; strict exports оба200,
60/62 features. Неизменённый exact comparator подтвердил **2092 одинаковых значения**,
включая геометрию/ДУ/экономику; оба17/17 и те же2192,535/2242,303 м.
Matched API reduction **35,0937616%**, ratio **1,54068395×**; это отдельный результат от
in-memory45,63%. Повторяемость пары не проверена; посторонние службы хоста не выключались.
Evidence: `api-pair-comparison.txt`, `api-pair-speed-summary.json`, `api-pair-final-2d-*`.
Фазы R10, мс: independent326973/shared452247/group_spines170131/finalized_portfolio708694;
planner total1658047. Searches10918/nodes6643264/angle_pruned2947137529 совпали;
pairs185194247 (на61369 меньше baseline за счёт ранее проверенного constraint working-set cache).

После завершения/exports/exact-match выполнен `r11-after-api-pair.ps1`: fresh API state guards
прошли, snapshot создан, build/fast/probes PASS. Тихая API-пара завершена до начала этой
нагрузки. Затем временные API/DB остановлены, volumes/evidence сохранены; основной стек не менялся.
Исходники R11 не входили в измеренный runtime R10.

### R11: build/fast/micro/controls PASS; full завершён, сбор результата ожидает разрешения

R10 ExecutionSample profile содержит40326 samples; ближайший project frame
`compareStandardSequence` занимает8,59% samples (не доля wall-clock времени).
Готовится exact CoordinateArraySequence specialization: каждый Coordinate читается один раз,
XY берутся из полей, остальные ordinates через тот же `Coordinate.getOrdinate`, что и в JTS.
Exact-class guards, raw bits, finite XY fallback и общий путь остальных sequences сохранены.
Тесты/сборка/микрозамер R11 выполнены после завершения API-пары; полного R11 timing ещё нет.
Зафиксированный runtime R10 не включает эти новые исходники.

Подготовлены4 differential tests `PreparedArraySequenceEqualityTest` с замороженным R10 oracle:
layout, смешанные Coordinate subclasses, raw NaN/signed zero, odd dimension/measures,
Array/Packed и custom fallback включая исключения. Все4 PASS.
Локальный `r11-after-api-pair.ps1` перед heavy work требует completed/attempt1 обоих живых API jobs
и exact comparison; затем создаёт snapshot из R10 плюс только R11 production/test files.
Первый синтаксический разбор harness обнаружил перенос перед `-or`; он исправлен.
После API-пары скрипт успешно вошёл в выполнение: guard и snapshot stages пройдены.
Отдельного повторного syntax gate не было. Build/fast PASS:2444 total/0 failures/0 errors/3 skips,
Maven41,116 с. Image `sha256:96b7f3ffd78a3017bac0c94ff21ee95b1a855833a739da2647ba109d9209a572`.
Same144-feature equal-copy kernel,144000 comparisons/round: median rounds2–5
54,3685 →41,065 мс (−24,47%). Это только микрозамер сравнения геометрий, не полный маршрут.
Evidence: `r11-fast-reports/`, `r10-array-equality-probe.txt`, `r11-array-equality-probe.txt`.
Первый R11 controls gate PASS:6 tests/0 failures/0 errors/0 skips, Maven1:09,
включая полный corridor с default8 finish-кандидатами, depth/export и concave own egress.
После успеха запущен первый полный differential: `heatroute-benchmark-candidate-r11`,
ID `195866fd8a91c73686b563526f922ca46110e84c3eac682fa8db79266680510a`,
started `2026-09-29T02:48:29.098845378Z`,2 CPU/4 GiB, прежние heap/JFR.
Контейнер завершился `2026-09-29T03:07:04.423062638Z`, exit0/noOOM; это проверено
через исходный docker wait и inspect. Surefire timing/exact comparison ещё не собраны,
поэтому elapsed контейнера не подставляется вместо времени теста и equivalence не заявляется.
Наблюдатель `watch-r11-full.ps1` прошёл первый syntax check, но запуск через Windows
PowerShell 5 остановился на чтении UTF-8 пути без BOM ещё до выполнения команд.
Путь bind mount теперь выводится из `$PSScriptRoot`, без кириллицы в самом скрипте;
проверка finite совместима с обеими версиями PowerShell. R11 не перезапускался:
отдельный `docker wait` наблюдает тот же контейнер, его runtime logs продолжаются.
Исправленный наблюдатель не выполнен: auto-review классифицировал повтор как запрещённую
без отдельного разрешения повторную проверку. Запрошено согласие пользователя; сбор/comparison
не обходятся другим launcher. Текущий docker wait продолжает только наблюдение, не новый test.

### Следующий isolated probe: копии при сортировке нормалей; distance reuse отклонён

Только в ignored `evidence/wall-normal-distance-src/BuildingWallNormals.java` подготовлен trial:
сортировка читает private point.x/y без создания defensive Coordinate copies; внешний accessor
не меняется. Первоначальное предложение переиспользовать footprint distance отклонено до запуска:
даже exact Polygon может содержать custom LinearRing/CoordinateSequence; JTS distance читает
их через virtual getCoordinates/toCoordinateArray. Top-level class guard недостаточен.
В trial восстановлены оба distance вызова. Difference/intersection, порядок проверок, epsilon
и формулы оставлены прежними. Пути probe сохранены для истории, но distance reuse в коде уже нет.
Production BuildingWallNormals не изменён. Подготовлены `WallNormalDistanceProbe.java` и
`probe-wall-normal-distance.sh`: synthetic convex/concave/hole/multipart/tangent/gap/UTM,
official Polygon/MultiPolygon с interior/boundary points и clearance1.5/3.2/5.2/10 м,
ordered raw-bit exits, trace non-idempotent custom Polygon.distance и defensive-copy assertion.
Сначала записывается бинарный oracle, затем2 warmup/5 timed rounds; baseline и overlay
используют раздельные classpath directories. Оба запуска PASS:712 queries/2596 candidates,
все100222 bytes binary oracle совпали точно, включая custom distance trace.
Timed baseline1542/873/917/759/748 мс, overlay1411/1276/852/710/683 мс: прогрев ещё влияет,
устойчивый speed claim не делается. Production не изменён; trial не входит в R11.

Следующий кандидат после этих probes — bounded per-search reuse исходящих направлений.
Read-only tracing `shortestPath` подтвердил: outgoing vector зависит от `(current,next)`,
а повторные состояния `(previous,current)` используют тот же неизменяемый node set.
Предлагаемый prototype должен кешировать только нормализацию/rounding из `prepareDirection`,
оставляя atan2, сумму допусков, порядок gate/cancellation/tie-break прежними. Не использовать
dense N² или создавать объект на каждый hit; рассмотреть ограниченные64-entry primitive pages
с fallback на прежнее вычисление при исчерпании бюджета.
В ignored `evidence/outgoing-direction-src/` подготовлены trial Rules/Router из immutable R10:
добавлен primitive prepared-outgoing angle helper и вызов per-search cache на прежнем gate,
после blocked/known visibility/minimum segment checks. Сам cache и differential probe подготовлены
отдельно; default budget4096 pages, ленивое выделение, invalid states сохранены.
Cache и `PreparedOutgoingDirectionProbe.java` готовы: index стартует не более чем с16 slots
и растёт ограниченно; payload ceiling6553600 bytes без учёта array/index headers.
Probe содержит independent frozen raw/prepared oracle, ULP/NaN/Inf/zero/overflow/subnormal,
направленность, страницы/исчерпание и repeated/cold synthetic timings. Первый запуск остановился
на javac: два warmup field-access statements не являются допустимыми Java statements.
Исправлены на присваивания volatile sink; повторный запуск отложен до разрешения пользователя
по правилу AGENTS.md. Ошибка относится к ignored probe, не к production routing.
Production Rules/Router не изменены; trial не входит в R10 API или R11. Cache и router пока
не проверены исполнением, ускорение не установлено.

### R12: angle-cone probe, build/fast, controls и full PASS

В ignored `evidence/turn-cone-src/` подготовлен ещё один независимый trial, без outgoing
cache: только prepared predicate может пропустить atan2/исходящий asin для заведомо
внутренних областей [135°,180°], [atan(1/32),45°] (reject) и [atan(2),90°] (accept).
Нормализация, исходные guard-проверки и пограничный fallback неизменны. Это не ослабление
углового правила; доказательство рассматривает те же вычисленные cross/dot doubles.
У верхних90° нет запаса в градусах: контракт Java11 atan2 (2ulp, менее5e-16 радиан здесь)
покрывается прежним FP-допуском1e-12. Источник: [Java11 Math.atan2](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/Math.html#atan2(double,double)).
Proof и launcher: `evidence/turn-cone-proof.md`, `probe-turn-cone.sh`; отдельный differential
probe готов: `PreparedTurnConeProbe.java`, independent frozen R10 prepared oracle,
cone/tolerance ULP edges, exhaustive special components,2048 seeded raw-bit tuples,
millimetre vectors, extreme scales, six paired AB/BA rounds после warmup.
Первый isolated probe после окончания R11 PASS:27203 cases exact match. Шесть paired
ordinary rounds дали median104,3505 →67,3175 мс (−35,49% kernel only); boundary timing
субмиллисекундный и шумный. Это не измерение всей трассировки, повторяемость не установлена.
Evidence: `turn-cone-probe.txt`; контейнер `heatroute-benchmark-turn-cone-probe`, exit0.
После этого три shortcut перенесены в production prepared overload. Scalar/junction
predicates и final validator не менялись. В `PreparedIncomingDirectionTest` добавлены
три regression tests: cone ratios/ULP/extreme scales, special components и seeded raw/mm
vectors; прежние семь differential tests сохранены. Новый immutable candidate-r12 создан
из R11 плюс только Rules и этот test file. Первый build/fast PASS через
`r12-first-fast.ps1`:2447 tests/0 failures/0 errors/3 skips, Maven39,826 с;
все10 PreparedIncomingDirectionTest PASS. Image
`sha256:58db1a1894b1ee2146a012bd4220334549cb2dedf243c96c3c5c2cbfd7a38627`.
R11 observer/comparison и cache probe не запускаются этим скриптом.
Первый отдельный R12 controls gate PASS:6 tests/0 failures/0 errors/0 skips, Maven1:09.
Concave own egress, recovery и полный corridor с depth/strict export сохранены.
Evidence: `r12-controls.log`, `r12-control-reports/`, `r12-corridor.json`.
После controls запущен первый full R12 в прежних2 CPU/4 GiB/heap256–2560/JFR:
`heatroute-benchmark-candidate-r12`, ID
`991ccc20fdb6bb5203693aaf9a272568d777657647ba4a6eddd1927fe0245f6b`,
started `2026-09-29T03:14:18.654489851Z`, finished `2026-09-29T03:31:02.239539552Z`,
exit0/noOOM. Первый `watch-r12-full.ps1`/`compare-r12.sh` успешно собрал результат:
testcase997,725 с (testsuite997,883 с), all2505 values EXACT_RESULT_MATCH,
cheapest2192,535 м/296013322,58 ₽ и balanced2242,303 м/304973207,18 ₽, оба17/17.
−50,92% к baseline и−9,73% к R10; один замер, repeatability=false. Чистый plan990,524 с;
фазы independent269,487/shared372,787/group_spines75,196/finalized_portfolio273,052 с.
Final counters10918 searches/6643264 nodes/191929684 pairs/2946023594 angle prunes
совпали с R10. Evidence: `candidate-r12-full.log`, reports/, comparison.txt,
speed-summary.json, probe/demo JSON и JFR под общим prefix candidate-r12.
R11 collector/comparator остаётся нетронутым; R12 не выдаётся за его повторную проверку.
Первый отдельный разбор R12 JFR существующим `ProfileSummary.java` остановился с
Java heap OOM в JFR ConstantMap при heap768 MiB/container1 GiB; контейнер
`heatroute-benchmark-r12-profile-summary`, exit1. Расчёт R12 уже завершился успешно,
этот отказ относится только к анализатору файла. `candidate-r12-profile.txt` сохраняет
ошибку; повторного разбора нет, свежие проценты hotspots не заявляются.

### R13: guarded raw reject до hypot, probe/fast/controls/full PASS

Сохраняемое в репозитории [численное обоснование R12/R13](ROUTING_ANGLE_SHORTCUT_PROOF.md)
связывает guard, текущие допуски, независимый oracle и область доказательства.

Пока работает R12, в `evidence/raw-reject-src/` подготовлен независимый prototype.
В prepared overload до outgoing hypot он может только отклонять направления в двух
заведомо запрещённых cones. Raw accept отсутствует. Дополнительные guards:
max-ordinate исходящего луча в `[2^-256,2^256]`, max-component подготовленного входящего
в `[0.5,2]`, модуль dot не меньше `inScale*outScale/16`. Вне guard или reject-cones
исполняется полный неизменённый R12 путь. Это контролирует cancellation/underflow,
не изменяя приём пограничных90° или произвольных extreme inputs.
`raw-reject-proof.md` выводит консервативную ошибку сравнения с вычисленным normalized
predicate <1e-12 радиан при минимальном запасе до разрешённой области >0.022 радиан.
FrozenR12DeflectionRules — literal immutable R12 class только с переименованием класса
и конструктора; первый probe будет сравнивать именно с ним, не с более медленным R10.
Launcher `probe-raw-reject.sh` и `PreparedRawRejectProbe.java` готовы: прежние27203 cases,
q bounds±ULP, dot-floor constructions, mixed exponents/tiny components, high-scale
tolerance boundaries и ещё8192 raw-bit tuples. Guard-model counters не требуют reflection
или instrumentation trial. Timing:6 warmup/6 paired AB/BA rounds R12 против prototype,
incoming prepared заранее, checksums обязаны совпасть. Исполнения/ускорения/production-
изменения на момент подготовки не было; первый запуск выполнен после полного R12.
Первый probe PASS:35494 exact cases; guard eligible21201, floor19326,
reject-negative5195/reject-shallow4442. Ordinary median71,443→58,4905 мс (−18,13%
kernel only), один явный timing outlier; boundary subset не ускорился. Повторяемость
и end-to-end выигрыш не установлены. Evidence: `raw-reject-probe.txt`.
После probe guarded raw reject перенесён в production prepared overload; raw accept
по-прежнему отсутствует. Добавлены2 regression tests для binary guards/cancellation
и mixed-scale components, прежние10 сохранены. Первый immutable candidate-r13
build/fast PASS через `r13-first-fast.ps1`:2449 tests/0 failures/0 errors/3 skips,
Maven38,498 с; все12 focused direction tests PASS. Image
`sha256:ab3f50c7d11fc257a41c8ebaebf15a473bba141abf414c62eb5e003b4d6bd514`.
Первый R13 controls gate PASS:6 tests/0 failures/0 errors/0 skips, Maven1:10,
включая concave own egress/recovery/full corridor с depth/export. После завершения
контролей и остановки отдельного profile-reader запущен первый full R13:
`heatroute-benchmark-candidate-r13`, ID
`62e2dbce280249176e76f69d3316b98b0d0fde2c15d51230d3f59d65ef6f61bb`,
started `2026-09-29T03:39:02.600096989Z`,2 CPU/4 GiB, прежние heap/JFR.
Первый `watch-r13-full.ps1`/comparator завершились успешно. Контейнер finished
`2026-09-29T03:55:17.187037337Z`, exit0/noOOM; testcase968,891 с,
all2505 values exact match, cheapest2192,535 м/296013322,58 ₽ и
balanced2242,303 м/304973207,18 ₽, оба17/17. −52,34% к baseline,−2,89% к R12;
один замер, небольшой дополнительный выигрыш требует repeatability.
Plan961,887 с; independent262,970/shared362,712/group_spines72,603/
finalized_portfolio263,600 с. Final counters совпали с R12/R10:
10918 searches/6643264 nodes/191929684 pairs/2946023594 angle prunes.
Evidence: `candidate-r13-full.log`, reports/, comparison.txt, speed-summary.json,
probe/demo JSON и JFR под общим prefix candidate-r13. Heavy tasks во время full отсутствовали.

Read-only revisit оставшегося `BoundaryIndex.intersects` hotspot не дал обоснованного
крупного exact-кандидата: flat traversal и strict-sign subtree pruning уже стоят перед
robust orientation, fallback нужен для касаний/коллинеарности/extreme inputs. Mutable
last-start-point cache у shared Constraint не принят из-за concurrency/key-coherence
риска и несовпадения с sampled leaf. Это ограниченный отрицательный результат анализа,
не доказательство оптимальности и не повод менять robust predicates. Новых запусков нет.

Ещё один ограниченный read-only ownership анализ не нашёл existing fast path для
отмены O(vertices) snapshot equality. PreparedNormalEgressMemo владеет копиями,
но current features остаются внешними: OfficialRoutingEnvironment хранит ссылки,
featuresInWindow создаёт новые списки из source, PreparedRoutingConstraints сравнивает
свою копию с текущей геометрией. Input identity/hash не доказывают неизменность
JTS/JsonNode/порядка/custom поведения. Такой shortcut не добавлен; действующие
same-instance mutation regressions сохраняются. Новый immutable-window API не вводился.

### Подготовка первого R13 API/job/export замера

Во время full R13 подготовлены `r13-runtime-build.ps1`, `runtime-r13.override.yaml`,
`compare-api-r13.ps1` и `r13-api-plan.md`; оба новых PS scripts прошли первый syntax gate.
Runtime build требует завершённый R13 exit0/noOOM и exact comparison; до этого не идёт.
Live inventory подтвердил: прежний изолированный `heatroute-perf-pair` API/db остановлены,
volumes и persisted import сохранены. Планируется свежий R13 JVM/API job в том же
проекте,2 CPU/4 GiB,heap256–2560, default2D, без изменения main stack.
Новый prefix `api-r13-2d` и existing exclusive submission guard исключают случайный
повтор POST. Сравнение использует предыдущий baseline2554,766262 с, не новый baseline
run; chronology/repeatability ограничения будут указаны явно. Build/up/POST пока не было.
Первый `docker compose ... config --quiet` для R13 overlay PASS, без запуска сервисов.
Численное обоснование угловых shortcuts перенесено из временного evidence в
`docs/implementation/ROUTING_ANGLE_SHORTCUT_PROOF.md`; это не новое правило и не gate promotion.
После full R13/exact2505 первый `r13-runtime-build.ps1` PASS. Runtime image
`sha256:1192de7626bba5c4ddb792e6144d6d052cf35a9d2a7b6bf0382d87118f7c842a`.
Существующий pair API повторно проверен stopped перед заменой на R13; первый Compose up
PASS. Фактический контейнер: uid10001(heatroute), Temurin11.0.28+6, CPU2/RAM4GiB,
heap256–2560MiB/ActiveProcessorCount2; DB1CPU/1GiB healthy. JVM started
`2026-09-29T03:57:50.790082399Z`. Readiness CP-SAT/PostGIS, OpenAPI и same-import guard PASS.
Единственный новый run `74470b23-fec8-4b19-8f95-1fee79ca8a55`,
job `ae628568-c07f-4366-81ae-a91b6fc18af5`, created
`2026-09-29T03:59:15.735459Z`, import `a829c660-8394-4e2d-832d-23a027a3d018`,
source103/stable, depth=false,min0.7/max10. Live Observe подтвердил running/calculation,
attempt1, без error; read-only observer проверяет эти же ID каждые45с и не содержит POST.
Evidence prefixes: `api-r13-runtime-identity.json`, `api-r13-java.txt`, `api-r13-2d-*`.
Timing/export/exact API comparison ещё ожидаются; heavy work на время job остановлено.

**Итог R13 API:** completed2026-09-29T04:24:47.094296Z, attempt1,
1531,358837 с от persisted created (включая очередь). Первый comparator PASS:
all2092 result values точно равны API baseline; cheapest2192,535 м/296013322,58 ₽,
balanced2242,303 м/304973207,18 ₽, оба17/17. Строгие exports200,60/62 features.
Снижение времени к прежнему API baseline —40,05875%,1,6683×; к предыдущему R10
API —около7,65%. Повторяемость не подтверждена; baseline заново не запускался.
Фазы independent284913/shared389500/group_spines158813/finalized_portfolio697894 мс,
plan1531122 мс. Finalization остаётся основным этапом и почти не ускорилась к R10
(708694 мс), несмотря на выигрыш первых фаз. Источник её различия с in-memory
путём не установлен. Evidence: `api-r13-2d-runtime.log`, `api-r13-2d-*`,
`api-r13-comparison.txt`, `api-r13-speed-summary.json`. Изолированные API/db
остановлены после сбора evidence и первого R14 probe; volumes/main stack сохранены.

### Уточнение различий тестового и API-путей

Read-only разбор `OfficialDatasetRoutingTest`, `OfficialCalculationService`,
адаптера расчёта, `OfficialFeatureRepository`, `OfficialObstacleRouter` и
`OfficialRoutePlanner` отделил подтверждённые различия от гипотез о скорости.
Сохранённый ответ `api-r13-2d-import.json` содержит
`report.input_profile = baseline_input`: профиль совпадает с доменным тестом и не
объясняет разницу времени. В обоих случаях используется stable planner.
Доменный тест включает глубину и загружает GeoJSON/метрические геометрии в память;
API-замер отключает глубину и получает окна ограничений/существующих ОКС из PostGIS
через bbox-запросы и WKB. Полное побитовое равенство входных геометрий и состава
каждого окна этим анализом не доказано. Нельзя приписывать наблюдаемую разницу
финализации одному из этих факторов без сопоставимого измерения фаз. API R13
по-прежнему сравнивается только с API baseline при одинаковых параметрах.
Новых вычислительных проверок этот разбор не запускал.

### Следующие кандидаты

Во время ожидания R13 API подготовлен изолированный R14 wide-raw-cone trial:
при прежних q/m/dot-floor guards отрицательный отказ расширен до93,576°,
малый положительный отказ — до56,310°, а внутренний положительный конус от63,435°
получает ранний допуск. Границы действующей политики не меняются; численное
обоснование учитывает ошибки dot/cross и умножения на1,5, с запасом более3°.
Trial-класс и differential/timing probe находятся в ignored evidence. Первый probe
PASS:36997 exact cases против исходного класса неизменяемого R13 image, включая
35494 прежних случая. Ordinary kernel median60,1805→24,7045 мс (примерно−58,95%);
в boundary subset устойчивого выигрыша нет, один trial выброс4,801 мс. Все paired
accepted-count/checksum совпали. Это не full-route speed-up. После probe изменение
перенесено в production, добавлены3 focused regression tests (всего15), численное
обоснование обновлено. Первый R14 build/fast PASS:2452/0/0/3,15 focused PASS,
Maven39,554 с; первый controls6/0/0/0 PASS, Maven1:08. Immutable image
`sha256:2a64847c93cdbcc77211f638978d54c65aa7a7342a9e1d80f57b94764d87ff7d`.
Первый full контейнер `heatroute-benchmark-candidate-r14`,
ID `f9df1a03d819a9dc57207ae7b7f0062ad3b5dd28b85e9628edbe58227aeee9da`,
started `2026-09-29T04:34:00.33057534Z`,2CPU/4GiB,heap256–2560,Active2,JFRprofile.
Первый watcher ждёт именно этот контейнер, после exit соберёт reports и выполнит
первый exact comparator. Full результат/тайминг и R14 API ещё не получены;
heavy work на время full остановлено. Повторных запусков не было.

**Итог full R14:** finished2026-09-29T04:49:11.10234332Z, exit0/noOOM;
testcase905,058 с (15:05,06), suite905,232 с. First exact comparator PASS:
2505 значений, оба17/17 с прежней геометрией/длинами/стоимостью. Это−55,4788%
к baseline2032,870 с и−6,5883% к R13; единичный замер, не repeatability.
Plan897960 мс; phases238851/325216/66333/267559 мс. Final counters
10918/6643264/191929684/2946023594 совпали с R13. Финализация не ускорилась
относительно R13, выигрыш пришёл из предыдущих фаз. Evidence prefix
`candidate-r14-*`: full.log, reports/, comparison.txt, speed-summary.json,
state.json, probe/demo JSON и JFR. R14 API намеренно не запускался.

Первый разбор нового R14 JFR PASS в отдельном reader сheap2560MiB/container4GiB,
после завершения всех timed calculations; R12 JFR не перечитывался. Всего38932
`jdk.ExecutionSample`. Nearest project frame: BoundaryIndex.intersects18,05%,
meetsUtilityClearance13,45%, BuildingWallNormals.candidates10,24%,
compareStandardArraySequence6,06%, ownApproachAllowed5,83%, segmentAllowed5,08%.
Leaf FdLibm.Hypot8,02%, StrictMath.hypot4,86%. Это распределение samples,
не доли wall-clock и не профиль API. Evidence: `candidate-r14-profile.txt`;
reader container `heatroute-benchmark-r14-profile-summary`, exit0.
Metadata benchmark image показывает `JAVA_VERSION=jdk-11.0.30+7`, тогда как
фактический R13 API JVM —11.0.28+6. Это ещё одно различие domain/API путей,
а не доказанная причина разницы скорости; внутри каждого опубликованного
сравнения baseline/candidate использовался один соответствующий runtime.

Угловой индекс кандидатов отдельно отложен до измерения reuse: текущие логи
не показывают число раскрытий разных направлений одного current node. Подготовлен
`angular-index-diagnostic-plan.md` для отдельного ignored snapshot. Отсутствие этих
данных не доказывает невозможность индекса; ascending-ID порядок, консервативное
включение граничных векторов и ограничение памяти остаются обязательными.

### R15: пропуск неиспользуемых начальных окон в 2D (fast/controls/API PASS)

`OfficialRoutePlanner.finishGeometry` загружал `featuresForEdges(sizedEdges)`
до условного depth reroute; в режиме depth=false эта переменная не читалась,
а затем заменялась результатом `featuresForEdges(finalSizedEdges)`. Первую
загрузку перенесли внутрь depth-enabled branch. Обязательные egress/regularization,
финальная загрузка по всей готовой полилинии, sizing и независимая validation
оставлены без изменений. Это не кеш и не reuse геометрии между разными состояниями.

В `OfficialFinalFeatureWindowReuseTest` добавлены два случая depth=false:
ожидаются3 window source calls вместо4, и source-only park должен доходить до
последней реальной проверки. Существующий depth=true случай с4 calls и полными
depth profiles сохранён. Первый syntax gate нового `r15-first-gates.ps1` PASS;
после завершения R14 первый immutable R15 build/fast PASS:2454/0/0/3,
Maven37,481 с. Controls6/0/0/0 PASS, Maven1:06; все3 feature-window focused tests
PASS, включая оба новых 2D случая и прежний depth=true. Отдельного depth-enabled full
R15 не было; первый API default2D завершился, результат приведён ниже.
R15 first gates дождались exit0/noOOM/exact R14 и создали отдельный immutable
snapshot из R14 с двумя изменёнными файлами. Изолированный эффект удаления окон неизвестен.

Подготовленные R14 runtime scripts прошли первый syntax gate, но build/API
R14 не запускались. API замер R15 после его собственных gates включает и R14
angular cones, и R15 windows, поэтому его выигрыш нельзя приписать одному лишь
удалению лишней загрузки.

Первый R15 runtime build PASS, digest
`sha256:95f580febaa4a41428cd068cb666a1bb544326ceeb2fc8dc56e0d3e8f8b0225b`.
Перед Compose up подтверждены stopped только прежние pair API/db; config/up PASS.
Фактические uid10001, Temurin11.0.28+6,2CPU/4GiB,heap256–2560/Active2,
DB1CPU/1GiB healthy; API JVM started2026-09-29T04:59:19.806827663Z.
Readiness CP-SAT/PostGIS, OpenAPI и same-import/SHA guard PASS. Единственный run
`ed008b95-b0e7-4689-867f-09d308260097`, job
`698e5d64-3eda-4c40-8d1a-38d8ce3fcbb0`, created
`2026-09-29T05:00:36.83183Z`, source103/stable,depth=false,min0.7/max10,
import`a829c660-8394-4e2d-832d-23a027a3d018`. Completed
`2026-09-29T05:25:01.595314Z`, attempt1, no error; elapsed1464,763484 с.
Read-only watcher наблюдал те же ID и не содержал POST. Во время расчёта
не запускались другие builds/tests/profiles/probes; ближе к завершению открыт
дополнительный Vite UI для показа этого job по просьбе пользователя.
Evidence: `api-r15-runtime-identity.json`, `api-r15-java.txt`, `api-r15-2d-*`;
первый Export PASS (200,60/62features), comparator PASS exact2092.
`api-r15-speed-summary.json`:2554,766262→1464,763484 с,−42,66546%,1,74415×;
к R13 ещё−4,3488% в одном запуске. Это прежний baseline, не свежая парная
репликация. Фазы independent248649/shared346722/group_spines154902/
finalized_portfolio714159 мс, plan1464433 мс. Финализация не ускорилась
относительно R13 (697894 мс); польза удаления окон отдельно не доказана.
Main stack не изменён; pair API/db оставлены доступными для открытого
пользователю UI `http://127.0.0.1:5175/`, без повторного расчёта.

### R16 isolated wall-frame: первый differential gate не прошёл

Подготовлен только ignored prototype, production остаётся R15. Frame хранит
ограниченные primitive wall operands; все point-dependent JTS проверки прежние.
Первый compile PASS, differential остановлен TopologyException. Статическое
сравнение выявило опечатку скопированной capsuleIntervals: `ny * uy` вместо
исходного `ny * ux`. Исправлен только прототип. Повторный тест и timing не
запускались; пользователь отложил прототип. Эквивалентность и ускорение frame
не заявляются. Evidence:`r16-wall-frame-probe.txt`, `wall-frame-src/`.

- Baseline завершился с exit 0: **2 032,87 с** по Surefire (33 мин 52,87 с), один
  полный test PASS, включая strict export. Контейнер: 21:27:03.563–22:01:03.288 UTC.
  Чистый `plan` по phase log: 2 018,011 с; independent 476,078 с, shared 659,950 с,
  group_spines 126,964 с, finalized_portfolio 755,018 с. Сохранены `baseline-probe.json`,
  `baseline-demo.json`, `baseline-full.log`, `baseline-reports/`, `baseline-full.jfr`.
- Последовательный R3 стартовал в 22:01:03.911 UTC только после exit 0 baseline,
  с теми же JVM/CPU/memory/JFR. Полный test PASS: **1 686,424 с**; точный comparator
  подтвердил 2 505 значений, не игнорируя ни одно поле внутри `result`.
  Обе сети сохранили 17/17: cheapest 2192,535 м / 296 013 322,58 ₽;
  balanced 2242,303 м / 304 973 207,18 ₽. Это depth=true и не результат старого API depth=false.
- По фазам R3: independent 349,746 с; shared 484,699 с; group_spines 113,617 с;
  finalized_portfolio 723,619 с; plan 1 671,682 с. Во всех фазах совпали накопленные
  счётчики поисков, узлов, проверок пар и отсечённых углов. Основной остаток времени — финализация.
- R4 fast gate пройден; очередная R5-интеграция normal memo в `ValidationSession`
  сохраняет отдельно raw nearest normals и padded navigation normals, чтобы не ужесточить
  обязательную длину ввода. Сам результат проверки сети не кешируется. R5 fast gate:
  **2416 tests / 0 failures / 0 errors / 3 skips**, Maven 40,124 с. Отчёты
  `evidence/r5-fast-reports/`, образ `heatroute-benchmark:candidate-r5`, digest
  `sha256:ff9fd7673f9f427d6b7882fbfd1994f108b13647cb64d5ffa97d916ddf700500`.
  Полный R5 PASS: **1351,748 с**, все 2505 значений совпали с baseline. Контейнер
  22:43:43.456–23:06:21.899 UTC, exit 0, без OOM; Maven 22:36, чистый plan 1344,206 с.
  Фазы independent 330,391 с; shared 466,691 с; group_spines 106,506 с;
  finalized_portfolio 440,616 с. Итоговые счётчики совпали с baseline/R3: 10918 поисков,
  6643264 узла, 191991053 проверки пар, 2946023594 отсечения углов. Evidence:
  `candidate-r5-full.log`, `candidate-r5-reports/`, `candidate-r5-comparison.txt`, probe/demo/JFR.
  Выигрыш против R3: 19,85%. Сборки/микрозамеры/короткий runtime smoke дают шум этой пары.
- R6/R8/R9/R10 полный differential PASS. R4/R7 отдельно полно не запускались.
- R5 runtime/readiness/import подтверждены; R8 API/job/export PASS, не проверка R9.
- После успешной пары оценить повторяемость и отдельно измерить обычный API/job-путь.
- Обновить этот отчёт фактическими итогами. Не объявлять N/R-gates закрытыми по микрозамерам.
