# Performance74: интервалы пересечения дорог в поиске

25.09.2026, `codex/routing-63-geometry`, `global-tree-74`. Локальная оптимизация с сохранением
правил, не новая инженерная норма и не завершение G2/R. Основной алгоритм, без отдельного
экспериментального режима. Runtime61 и VPS не менялись.

## Измеренная причина

45-секундный JFR живого fresh roads72:1874main samples,43,8% включают извлечение пересечений,
35,1% — JTS `intersection`. Это включающие доли одного участка поиска, не доли всего job.
Полный overlay-граф создавался при проверке очередного двухточечного звена.

## Изменение

- `PreparedRoadCrossings` владеет ограниченной копией Polygon/MultiPolygon и индексом рёбер
  shell/holes. Точки фактического пересечения вычисляет JTS RobustLineIntersector; станции,
  midpoint и порядок фильтрации/объединения соответствуют исходному guard. Направление сохранено.
- Ускоряется только поиск road/tram. Полная геометрия, разметка секций, terminal-prefix,
  повороты и независимый строгий export по-прежнему используют исходный полный guard.
- Неоднозначная численная геометрия, совпадение с границей, unsupported типы/precision,
  packed-float и слишком большой полигон возвращаются к исходному JTS. Это не разрешение
  пересечения и не ослабление отступа/угла/защитных3м. Проверяется и исходный тип звена до
  его расширения, чтобы реконструкция не скрыла custom sequence или лишние вершины.
- Индекс создаётся лениво, потокобезопасно, внутри Constraint текущего расчёта; отказ
  подготовки запоминается, отмена не публикует частично готовый объект. Источники и запросы
  не удерживаются, `userData` удалён. Ответы поисков и готовые маршруты не кэшируются.
- Cap16384координат включает holes/closures/components. Резерв3N…4N явных координат — копия
  источника, концы рёбер и распаковка packed-double для locator — учитывается в обоих бюджетах до аллокации. Это не байтовый
  предел всех JTS-объектов и не общий предел памяти расчёта. Смена источника/ДУ не использует
  несовместимый Constraint. Поддерживаемый packed-double factory в JTS1.20.0 не имеет mutator.

## Проверки

- Два независимых агента: дифференциальные тесты и review. Найдены и исправлены реальные
  RED: почти совпавшая повёрнутая UTM-граница; packed-float источник + double запрос
  на2мкм от границы. В обоих случаях ускорение ошибочно разрешало crossing, прежний guard
  отвергал. Теперь применяется fallback; отрицательные assertions сохранены.
- 28 component `@Test` и6 resource/lifecycle `@Test`; итоговый isolated набор123PASS/0FAIL.
  Внутри тестов: таблица720near-parallel/rotation/direction случаев, shell/holes/MultiPolygon,
  касания/перекрытия, angle/clearance/extension границы, владение, отмена, конкурентность,
  включительный cap и точная граница обоих бюджетов. Reviewer отдельно проверил45000
  near-vertex запросов (0расхождений,1728fallback); это локальное evidence, не общий proof.
- После review локальный bytecode JTS показал дополнительное удержание распакованных
  координат в locator для packed-double. Резерв исправлен3N→4N (смешанные кольца учитываются
  по отдельности), новый RED20≠15→GREEN. Обычный CoordinateArraySequence остаётся3N.
- Чистый быстрый Maven конечных исходников в отдельном snapshot: **1110cases/1107PASS/0fail/0error/3scale skip**,
  110классов. Исключены только `OfficialDatasetRoutingTest`, `OfficialCorridorDatasetTest`,
  `OfficialCorridorControlRecoveryTest`. Это не full и не свежий результат74.
- Web36/scripts37/lint/typecheck PASS. Docker/pwsh отсутствуют: выполнены эквивалентные
  Java11/web команды. Live Compose, native PostGIS equality и большие scale gates не пройдены.

Evidence (ignored, корень `.tooling`):

- `road74-focused.yYvuPy/integrated-packed-budget-final.log`, `packed-float-red.log`, `packed-budget-red.log`;
- `road-crossings-test74.VqWYmo/` — тестовая работа/ранний rotated-boundary RED;
- `intake-20260925/source74-fast-final.log`, `source74-fast-final-reports/`, `source74-web.log`;
- `intake-20260925/source73-full-reports/`, `source73-result.json` — предыдущая fresh база.

## Замер компонента, не end-to-end

94реальных road/tram полигона нового сценария,7520направленных запросов; результаты старого
и нового guard совпали во всех случаях. Лог `road74-focused.yYvuPy/real-roads-microprobe-packed-budget-final.log`:
подготовка22,728мсCPU/3589696allocated bytes; последняя из5чередующихся прогретых серий
114,635→28,207мсCPU,194754400→38772680allocated bytes. Это примерно4,06×/5,02× **для этой
операции**, без утверждения о скорости полного алгоритма. Число fallback0 относится к исходным
запросам microprobe, не ко всем extended звеньям production. JIT/параллельные расчёты не изолированы.
Повтор проведён после последнего budget-fix. Предыдущие прогоны давали3,14…4,55×CPU, поэтому
это локальное воспроизводящее измерение с разбросом, а не гарантированный коэффициент ускорения.

## Живые проверки и следующий scope

Clean/full74 до packed-budget correction **завершён** (session16835), snapshot
`.tooling/source74-build.OwIzxd/apps/api`, лог `intake-20260925/source74-full.log`.
1113cases/1109PASS/1прежний compact failure/0errors/3scale skip. Отчёты сохранены в
`source74-pre-budget-full-reports/`; fixture556,274с (класс из2тестов560,574с).
Accepted `source74-result.json` получен после assertions;17/17/strict export3ролей PASS,
**все значения variants точно совпали с73**. `source74-diagnostic.json` отдельно.
Параллельный wall-time не доказывает ускорение, локальный microbenchmark его не заменяет.
Это не полный gate последней поправки памяти. Конечные исходники74 заморожены отдельно в
`.tooling/source74-final-build.mgNqTd/apps/api`; быстрый Maven `source74-fast-final.log`
session87838 завершён1110cases/1107PASS/3skip, отчёты сохранены.
**Полный конечный snapshot session37091 завершён**:1114cases/1110PASS/1прежний compact failure/
0errors/3scale skip; fixture532,720с (concave4,169с). `source74-final-full-reports/` сохранён,
accepted `source74-final-result.json` получен после assertions;17/17/strict export3ролей PASS.
Все значения variants точно совпали с73. Diagnostic отдельно, не подмена accepted результата.
Оба snapshot74 теперь свободны. Fresh roads74 не запускался: новый roads75 проверяет также
следующее correctness-исправление; это не измерение одной только оптимизации74.

Fresh roads72 **session19058/PID33818** всё ещё работает на основном `apps/api/target`,
который пока нельзя перезаписывать. Не перезапускать тихий живой процесс с нуля. Эти замеры
при параллельной нагрузке не подтверждают SLA. Fresh roads73 не выполнялся.

Full73 завершён:1080cases/1076PASS/1прежний compact failure/0errors/3scale skip; исходный
fixture529,767с (класс533,933с),17/17 и strict export3ролей PASS. Все значения variants совпали с72:
shortest/cheapest2068,786м/11новых камер/22поворота/283006479,92₽; balanced2192,523м/
14камер/25поворотов. Ручная схема Евгения1913,859м/11узловых маркеров — ориентир, не сертификат норм.

Открыты: final-ДУ retention допустимого ввода; переходы через разные логические рёбра;
G2 других коммуникаций; compact-control (<1860м/≤13камер нельзя ослаблять); качество и скорость
свежего roads+kindergarten; native/Compose/scale и приёмка всего пользовательского процесса.
Этот checkpoint не закрывает цель «как у Евгения и быстро».

Следующий correctness patch — [source75](PRIMARY_ROUTING_75.md): final-ДУ retention
воспроизведён и исправляется отдельно от этого performance checkpoint. Не смешивать версии.
