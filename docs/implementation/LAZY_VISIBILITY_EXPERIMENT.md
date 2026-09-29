# HeatRoute: ленивый локальный кеш видимости, 29 сентября 2026

## Изменение и границы

В `OfficialObstacleRouter.VisibilityCache` плотные треугольные массивы заменены на
`PagedVisibilityValues`. До 4096 значений остаётся обычный `byte[]`; большие таблицы выделяют
страницы по 4096 байт только при записи ненулевого значения. Неизвестные страницы читаются как
ноль без выделения памяти, последняя страница укорочена. Уже записанные ответы не вытесняются.
Индексирование пар, состояния 0/1/2, раздельность forward/reverse для road/tram и общий массив
для ненаправленных проверок сохранены. Таблица принадлежит одному однопоточному поиску.

Общий маршрутизатор используется HeatRoute; инженерные правила, бюджеты,
очерёдность обхода и финальная валидация не менялись. Предыдущая оптимизация segment-query
сохранена. Source104/105-правки формы сети не включены в изолированный эксперимент.
Отложенный wall-frame не возобновлялся. На момент замера изменения оставались локальными.
Позднее пользователь разрешил включение этой оптимизации и segment-query reuse в master;
последующий preflight reachability/repair был отменён и в публикацию не входит. Новые тесты
и замеры при публикации не запускались, VPS не развёртывался.

## Проверки и разрешённый повтор

Первая попытка 09:32:40 UTC скомпилировала production overlay, но остановилась при компиляции
`PagedVisibilityValuesTest`: overload `Byte2DArrayAssert` в текущей AssertJ не имеет
`hasSize`/`containsOnlyNulls`. JUnit и конкурсный execute не запускались. Исправлены только три
приведения массива к `Object[]`; пользователь отдельно разрешил повтор. Исходные снимок и
журнал не перезаписывались.

Повтор: **104 теста, 0 failures/errors/skips**. Это единый focused gate:

- PreparedSegmentIntersectionTest — 29; PreparedSegmentQueryTest — 6;
- OfficialRouteGeometryRulesTest — 8; PagedVisibilityValuesTest — 5;
- OfficialObstacleRouterSearchTest — 22; SearchPriorityTest — 12; FallbackTest — 6;
- OfficialVisibilityMemoDirectionTest — 4;
- HeatRoutePlannerTest — 2; BoundedRootDemandCatalogGeneratorTest — 5;
- FrozenNetworkEvaluatorTest — 5.

Новые случаи сравнивают хранение с обычным массивом на размерах 0/1/4095/4096/4097/8192/8193:
границы страниц, короткий хвост, перезапись 0/1/2, seeded-операции, invalid indices,
ленивое выделение и независимые экземпляры. Существующие тесты покрывают направления,
динамические ограничения и результат/порядок поиска. После успешного gate исходники не менялись.
Полный checkout, stable pipeline, web, API/Compose и масштабные случаи этим gate не проверены.

## Единственный конкурсный execute

После PASS выполнен один реальный `HeatRoutePlanner.execute`.
Сравнение с предыдущим segment-query запуском: тот же input SHA256
`cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`, 633402 байта,
144 объекта (56 core / 88 window), 17 demand points. Совпали топология, snapshot hash,
`OfficialRunParameters` (depth=false, 0.7–10 м) и `Settings.initial()` с seed=2026.
Бюджеты прежние: общий 90 с, каталог 30 с. Контейнер 2 CPU / 3 GiB, Java 11,
heap 128–2048 MiB, JFR profile. Это холодные прямые вызовы, не время API job.

| Показатель | Segment-query | Lazy visibility |
| --- | ---: | ---: |
| Execute wall, с | 32.859478364 | 29.712366036 |
| Execute process CPU, с | 62.09 | 53.46 |
| Каталог, мс | 31077 | 27946 |
| Directed options | 29 | 35 |
| Physical assets | 98 | 135 |
| Normal root demands | 12 | 14 |
| Normal-seed attempts | 77 | 43 |
| Route calls | 153 | 123 |
| Shared seed networks / paths | 0 / 0 | 3 / 8 |
| Проверенные пары / всего | 72 / 816 | 72 / 816 |

Наблюдаемое изменение wall: −3.147112328 с (−9.577%), process CPU: −8.63 с (−13.899%).
Каталог изменился и стал богаче, появилась общая сеть на стадии seeds; это ещё не допущенный
финальный результат. Оба исхода — `CATALOG_INCOMPLETE`, причина
`catalog_expansion_limit:INFEASIBLE_IN_CATALOG:proven_master_infeasible`, `result=null`.
В новом артефакте `budgetLimited=false`, осталась truncation `normal_seed_budget_reserved`,
а `shared_seed_deadline` исчезла. Все четыре generator-coverage flags всё ещё false;
refinement_runs=1, conflict_count=0, archive_size=0 в обоих запусках. `demands_covered=17`
не означает подключения 17 точек в готовой сети. Глобальная невозможность маршрута не доказана.

Это обнадёживающее одиночное наблюдение, не статистически установленное ускорение и не
эквивалентность полного маршрута: каталоги зависят от дедлайнов, итоговых сетей нет. При прежних
лимитах количество выполненной работы и переходы между фазами могут различаться. Фоновые
серверы не остановлены; их pre-run нагрузка сохранена, влияние host/JIT/JFR не исключено.

## Память: что можно и нельзя заключить

Анализируется тот же JFR, без дополнительного расчёта. В execute/main-thread объём событий
OutsideTLAB стал 69538320 → 6803704 байта (−90.22%). Крупная прежняя группа `byte[]` в
конструкторе VisibilityCache (61905928 байт в sampled events) больше не входит в крупнейшие
группы: большие матрицы заменены небольшими страницами. Однако накопленное резервирование
TLAB выросло 11130529656 → 11378601456 байт (+2.23%). Малые объекты попадают внутрь TLAB,
а состав выполненной работы различается. Поэтому **ни общее выделение памяти −90%, ни
снижение пиковой RAM не заявляются**.

## Evidence и следующий вопрос

Изолированная база: immutable R15 image
`sha256:93f7c195352d5aa9f3a922217b8d144f6b1d0d2ffd9a791beec795695dff720d` плюс снимок
ровно четырёх main-классов, двух тестов и неизменного planner probe.

- База сравнения: `.tooling/nextgen-segment-query-20260929/evidence/`.
- Первая ошибка: `.tooling/nextgen-lazy-visibility-20260929/evidence/` и `snapshot/`.
- Успешный повтор: там же `evidence-retry1/` и `snapshot-retry1/`, SHA ledger, JUnit XML,
  result JSON, JFR, allocations JSON, metadata и container state сохранены.
- Контейнер `heatroute-nextgen-lazy-visibility-r15-20260929-retry1`: exit 0, no OOM.
- HeatRoute, уровень «Проверка», граф/поколение недоступны (N/A), source fallback.
  Два помощника gpt-5.6-terra: тесты и изолированный runner/сравнение артефактов.

Следующий отдельный предмет работы — почему более богатый каталог с shared seeds всё ещё
не даёт допустимую итоговую сеть. Ускорение примитивов не закрывает этот функциональный пробел.
Ни один N/R gate, production readiness или полнота конкурсного решения не объявлены закрытыми.
