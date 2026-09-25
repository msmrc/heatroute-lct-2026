# Source81: сохранение допустимой сети в основном режиме доводки

25.09.2026, `global-tree-81`, отдельное correctness-исправление поверх performance80.
Runtime61/VPS не менялись. Свежий результат81 получен: регрессия поворотов77–80 устранена
на исходном датасете. Это не приёмка дорог, всех подходов к камерам или общей цели.

## Почему79 не изменил итоговый маршрут

Full79 завершён: **1226 cases /1222 PASS /1 прежний compact failure /0 errors /3 scale skipped**.
Original fixture595,410с, concave4,583с. `result.variants` **точно равны78**, проверено deep equality.
Все17подключений/geometry/sizing/depth/economics/strict export3ролей прошли, но cheapest оставил
3плохих угла; balanced/shortest2194,257/2194,034м, cheapest2073,965м. Это не quality acceptance.

Call-site audit показал: основная финализация использует `TOWARD_UPSTREAM`. `PRESERVE_VALID`
передаётся лишь как дополнительная альтернатива при согласованном переносе камер. Исправление79
работало, но не покрывало основной путь. Поэтому гипотеза только о partially-invalid incumbent
из80 недостаточна — сначала требовалось проверить фактически используемую политику.

Bounded повтор TOWARD на сохранённом75 подтвердил для ветвей15/2: действующий
`hasMandatoryEgress`=true, полный terminal guard без avoidance=true, с avoidance=false.
Доводка меняла192,877→175,985м и20,472→19,055м, expert badAngles0→2. Это не необходимость
сменить направление ввода: отказывал исключительно поисковый контекст соседних трасс.

## Изменение

Whole-network preservation теперь разрешён и для `TOWARD_UPSTREAM`, **если все вводы уже
удовлетворяют тому же действующему предикату обязательного ввода, что проверял старый путь**.
После этого обязательна независимая проверка всей сети при текущих ДУ и с препятствиями
по полным полилиниям. Только тогда возвращаются те же рёбра без изменений.

Если хотя бы один ввод не подходит выбранной политике или сеть недопустима, остаётся прежний
repair. Альтернатива другой допустимой оси не удалена. Сам `hasMandatoryEgress`, его допуски,
правила ближайшей стены, поиск replacements и буферы не изменялись. Политики не объединены
безусловно, новые маршруты не кешируются. Независимый валидатор проверяет настоящие направления.

Ограничения79 для отсутствующего ДУ/явной геометрии и первого сегмента≤0,01м сохранены.
Это ещё не поэлементное preservation внутри недопустимой сети; такой случай требует отдельного
анализа. Полный путь третьего плохого угла исходного portfolio ещё не прослежен.

## Проверки

- 3 новых постоянных теста: основной режим с уже подходящим вводом, поворот90°+UTM и контроль
  сохранения альтернативы другой оси. На frozen80:19PASS/2RED; оба RED — ненужная замена ветви.
- Scoped62PASS, включая прежние final-ДУ/road/tram/normal/shared-junction tests.
- **Clean/fast Maven1230 cases /1227 PASS /0 failures /0 errors /3 scale skipped**,117классов.
  Исключены только original/corridor/compact классы; snapshot source точно совпал с checkout.
- Web36 Vitest +37script tests, lint/typecheck PASS.
- На конечных Maven classes81 повтор TOWARD-доводки saved75 больше не меняет ни одного ребра:
  balanced2192,523м, shortest/cheapest2068,786м;0bad angles/0close pairs, независимый валидатор
  проходит. До81 тот же probe менял две ветви. Code-source paths проверены.

Это bounded replay конкретного этапа, **не новый plan и не runtime**. Не заменять им свежий
расчёт, результаты которого приведены ниже. В81 нет нового независимого subagent review;
проверки выполнены основным агентом и штатными Java/web gates.

## Свежий full81 завершён

**1234 cases /1230 PASS /1 прежний compact failure /0 errors /3 scale skipped**,120классов.
Падает только `OfficialCorridorControlRecoveryTest`: ещё не восстановлен черновик<1860м.
Original fixture462,736с, concave5,845с. У80 было613,702с, но параллельная нагрузка различалась:
это одиночное наблюдение, **не доказанное ускорение/SLA**. Межзапросного reuse маршрута нет.

| Роль81 | Подключения | Длина, м | Новые узловые камеры | Повороты | Стоимость, ₽ |
| --- | ---: | ---: | ---: | ---: | ---: |
| balanced |17/17|2194,257|14|26|303008556,70|
| shortest |17/17|2068,786|11|22|283006479,92|
| cheapest /preferred |17/17|2068,786|11|22|283006479,92|

Во всех ролях пусты validation/sizing/engineering issues,0invalid bend angles/0close bend pairs,
полны глубина/экономика, строгий export проходит. У каждой сети один существующий корень,
два луча,0новых камер врезки. Отдельный Node-check подтвердил достижимость/отсутствие циклов,
одного родителя, концы и фактические длины рёбер, сохранение расходов/листья demand/вместимость узлов.
Это проверка fixture, **не свежий HTTP/PostGIS run** и не независимая повторная оценка препятствий.

Deep equality: shortest/cheapest81 **точно совпадают с75** по всем полям варианта;
balanced81 точно равен79/80. Это восстановление ранее достигнутого качества, а не новый рекорд.
Cheapest относительно80 короче на5,179м, дешевле на368309,26₽; shortest на125,248м/19980384,44₽.

### Что ещё не решено в геометрии

Просмотрено сравнение81 с Евгением в одинаковом масштабе, без сглаживания. SHA оригинального
`Downloads/1.geojson` и все extracted geometry сверены; файл не изменялся.11новых камер против
11маркеров у Евгения, но2068,786м против1913,859м: +154,927м/+8,10%;22поворота против15.
Эталон — геометрический ориентир, не результат нормативной приёмки.

Advisory `routing-geometry-quality.mjs` выявил **две почти параллельные пары выходов камер**
у shortest/cheapest: `corridor:tie:chamber:106:177` (0,0033°) и `...:560` (0,0243°).
У balanced таких пар нет. Это отдельный дефект формы, который не покрывает
`EngineeringRouteEvaluator.isCompliant()` (там повороты и расстояния между ними) и не запрещает
официальный deflection validator для узла степени4. Нельзя называть результат «все углы идеальны»
или превращать advisory-порог в норму СП. Нужны согласованные подходы/положение камеры без
потери вводов, повторная проверка полной сети и фактических отступов. Эти ID — диагностика,
не разрешение hard-code координаты/исключения в алгоритме.

В постоянном `OfficialDatasetRoutingTest` добавлена проверка engineering issues и вычисленной
compliance **всех** ролей, включая cheapest. Отдельный свежий повтор усиленного теста завершён:
**2/2 PASS**, original494,770с/concave7,637с; strict export включён. `result.variants` точно равны
первому full81, без исключения полей. Снимок production и изменённого test побайтно совпал
с checkout. Это не утверждение, что первоначальный full81 уже содержал новую assertion.
Повторные web36/scripts37/lint/typecheck PASS; изменение этого checkpoint — только test/docs.

## Расширенный roads/kindergarten: старые прогоны завершены с отказом

- Roads77:2990,305с,16/17 во всех ролях; balanced пропускает9, shortest/cheapest10;
  bad angles23/27/30, close pairs3/1/1. Строгий экспорт не достигнут после failure.
- Roads79:3529,887с, только balanced/cheapest,15/17 (не подключены10/14); shortest отсутствует.
  Длины4027,975/3833,619м, bad angles31/32, close pairs2/2. Это ухудшение против77, не принятие.
- В79 черновик portfolio-1 ещё имел16подключений, но final validation отклонил пересечение
  `shared:edge:15` с `shared:priority:edge:14:graft:9:upstream` вне общего узла и нарушение
  своего ОКС88 у `shared:priority:edge:10`. Независимый допуск не отключать ради числа16.
  Фазы79:independent1418,606с/shared1346,396с/group_spines172,594с/finalized592,200с;
  это локальные наблюдения, не benchmark. Из6577поисков528млн оценённых пар.
- Оба результата только diagnostic, accepted bundle отсутствует. Нельзя приписывать отказ
  одной конкретной функции без изолированного воспроизведения. Все corridor seeds в79 логе
  не собираются; ранее описанная проблема направления/сборки trunk остаётся подозреваемым.

## Процессы / продолжение

- Full81session87519 завершён; `source81-full-reports/`, `source81-result.json`,
  `source81-diagnostic.json` сохранены. **Target81 остаётся занят новым roads81**.
- Roads81session30394, runner `.tooling/scenario81.nROI5w`, frozen
  `.tooling/source81-build.Ndryzg/apps/api/target`; `source81-roads.log`, ожидаемые
  `source81-roads-result.json`/`.diagnostic.json`. SHA входа acac7a…125b,239features/17demands.
  Helper теперь проверяет engineering всех ролей и логирует фактические code-source origins.
- Повтор усиленного dataset test session78303 завершён exit0; target
  `.tooling/source81-quality.iLac2n/apps/api/target` свободен. `source81-quality.log`,
  `source81-quality-result.json`/`source81-quality-diagnostic.json` и reports сохранены.
- Full80session59635 завершён:1231cases/1227PASS/1compactFAIL/0errors/3skip;613,702с.
  Exact variants80==79 подтверждено, включая3bad angles cheapest. Target80 свободен,
  reports сохранены. Это equivalence, не quality/скорость; подробности в80.
- Roads79session69123 и roads77session2599 завершены exit1; targets79/77 теперь свободны.
- Не дублировать живые процессы и не очищать targets. Все логи — `.tooling/intake-20260925/`.

Дальше: дождаться roads81; исправить направленную сборку коридора/полный special через технические
вершины по контрпримерам ниже; устранить почти совпадающие выходы камер общим правилом.
Затем настоящий native import→job→export. Compose/pwsh отсутствуют; compact/G2/scale/R
и общая цель не закрыты. Не подменять live-приложение картинкой от fixture.

**Следующий дефект уже воспроизведён:** [направление и полный допуск trunk](CORRIDOR_TRUNK_ADMISSION.md).
3синтетики/52characterization assertions, независимо повторены родителем. Допустимый130м
теряется в grid/канонизации tree; техническое дробление запрещает целый допустимый special.
Исправление ещё не внесено.30-секундный JFR roads81 сохранён отдельно, не benchmark.

## Evidence (ignored `.tooling`)

- `preservation79.Ed4k7F/PolicyNetworkProbe.java`, `policy75.log` — TOWARD79 меняет хорошие ветви;
- `policy81-focused.omdYAh/red80-final.log`, `green81.log`, `PolicyNetworkProbe.java`, `policy75.log`;
- `intake-20260925/source81-fast.log`, `source81-fast-reports/`, `source81-web.log`;
- `intake-20260925/source81-full.log`, `source81-full-reports/`, `source81-result.json`;
- `intake-20260925/source81-quality.log`, `source81-quality-reports/`, `source81-quality-result.json`, `source81-quality-web.log`;
- `intake-20260925/source81-geometry-quality.json`, `source81-cheapest-comparison/side-by-side.png`;
- `intake-20260925/source77-roads-result.json.diagnostic.json`, `source79-roads-result.json.diagnostic.json`;
- `intake-20260925/source79-full-reports/`, `source79-result.json`;
- `intake-20260925/source79-cheapest-diagnostic-comparison/side-by-side.png` — просмотренное
  сравнение завершённого79 с Евгением, **не изображение81 и не принятый quality result**.
