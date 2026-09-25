# Source81: сохранение допустимой сети в основном режиме доводки

25.09.2026, `global-tree-81`, отдельное correctness-исправление поверх performance80.
Runtime61/VPS не менялись. Свежий результат81 ещё не получен; цель не закрыта.

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

Это bounded replay конкретного этапа, **не новый plan, не runtime и не доказанный выигрыш
portfolio81**. Не заменять им свежий расчёт. В81 нет нового независимого subagent review;
проверки выполнены основным агентом и штатными Java/web gates.

## Процессы / продолжение

- Full81session87519: `.tooling/source81-build.Ndryzg/apps/api/target` заморожен;
  `source81-full.log`, ожидаемые `source81-result.json` и `source81-diagnostic.json`.
- Full80session59635 завершён:1231cases/1227PASS/1compactFAIL/0errors/3skip;613,702с.
  Exact variants80==79 подтверждено, включая3bad angles cheapest. Target80 свободен,
  reports сохранены. Это equivalence, не quality/скорость; подробности в80.
- Roads79session69123 использует `.tooling/source79-build.RQBOQ4/apps/api/target`;
  **full79session51226 завершён, но target79 всё ещё занят roads79**.
- Roads77session2599 использует `.tooling/source77-build.FeShm8/apps/api/target`.
- Не дублировать живые процессы и не очищать targets. Все логи — `.tooling/intake-20260925/`.

Дальше: свежая all-role engineering проверка81, не только balanced/shortest;17/17, камеры,
геометрия/ДУ/глубина/стоимость/strict export. Затем roads81 при необходимости, настоящий
native import→job→export и пользовательское сравнение. Compose/pwsh отсутствуют; compact/G2/
scale/R и общая цель не закрыты. На сохранении checkpoint осталосьоколо1%лимита, поэтому
все текущие исходники и точное продолжение сохраняются в Git.

## Evidence (ignored `.tooling`)

- `preservation79.Ed4k7F/PolicyNetworkProbe.java`, `policy75.log` — TOWARD79 меняет хорошие ветви;
- `policy81-focused.omdYAh/red80-final.log`, `green81.log`, `PolicyNetworkProbe.java`, `policy75.log`;
- `intake-20260925/source81-fast.log`, `source81-fast-reports/`, `source81-web.log`;
- `intake-20260925/source79-full-reports/`, `source79-result.json`;
- `intake-20260925/source79-cheapest-diagnostic-comparison/side-by-side.png` — просмотренное
  сравнение завершённого79 с Евгением, **не изображение81 и не принятый quality result**.
