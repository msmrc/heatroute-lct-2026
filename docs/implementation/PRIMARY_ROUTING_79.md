# Source79: сохранение целиком проверенной геометрии

**Итог полного прогона:** session51226 завершён1226cases/1222PASS/1compactFAIL/3skip,
fixture595,410с; `result.variants` точно равны78, cheapest3bad angles сохраняются. Target79
ещё занят roads79session69123. Основной путь доводки не использовал изменённую политику:
дальнейшее исправление и trace — в [81](PRIMARY_ROUTING_81.md). Нижние статусы запуска исторические.

25.09.2026, `codex/routing-63-geometry`, `global-tree-79`; отдельный correctness-fix поверх
`2527e46` (направление terminal в78). **Не объявляет завершённым качество свежего portfolio.**

## Ошибка и изменение

После77 успешный repair начал заменять ранее допустимые ветви из-за синтетического буфера
соседних новых трасс. Поиск и независимый финальный валидатор отвечают на разные вопросы:
0,20м — поисковое avoidance-ограничение, а финальная проверка новых осей запрещает пересечения
вне общего узла. Старый `PRESERVE_VALID` смешивал эти проверки, поэтому менял уже допустимую
геометрию и мог добавлять плохие экспертные углы.

Теперь `PRESERVE_VALID` сначала проверяет **всю сеть** при текущих окончательных ДУ через
независимый валидатор и `featuresForEdges` по полной полилинии. Если сеть допустима, возвращает
те же рёбра, координаты и sections. Нет сохранённого между расчётами результата. Ни одно ребро
не изменяется между этой проверкой и возвратом, поэтому допуск не устаревает после частичного repair.

Если хотя бы одна проверка сети не пройдена, остаётся прежний путь доводки. `TOWARD_UPSTREAM`
не изменён; буферы соседей, shared-junction guards и все проверки **новых замен** сохранены.
Это не ослабление road/normal/официальных отступов и не исключение incident edges из поиска.
Сеть после последующих depth/sizing/regularization снова проходит обычную финальную проверку.

Fast path не используется для пустой сети, отсутствующего ДУ/явной полилинии или первого сегмента
≤0,01м. Последнее исключает известный legacy-пропуск геометрии в `OfficialRouteValidator.line()`;
сам этот отдельный долг здесь не исправлен. Неполная/частично недопустимая сеть не получает
поэлементного права обходить avoidance на основании устаревшей проверки.

## Проверки

- 6 новых постоянных regression/control tests: буфер соседа при общем узле, перестановка рёбер,
  входящий сосед, поворот90°+UTM, отсутствие ОКС и настоящее пересечение вдали от общей камеры.
  Исправленный синтетический набор на frozen78:13PASS/3RED; все3RED относятся к замене
  независимо допустимой ветви. Сам поисковый guard в этих тестах по-прежнему отказывает.
- Scoped59PASS, включая прежние road/tram retention, normal, final-ДУ и shared-junction cases.
- **Конечный clean/fast Maven:1222cases /1219PASS /0fail /0error /3scale skipped**,117классов.
  Исключены только три долгих original/corridor/compact класса. Source snapshot совпал с checkout.
- Web36 Vitest +37script tests, lint/typecheck PASS; отдельные логи перечислены ниже.
- Независимое статическое review19строк/6тестов: actionable findings нет. Review не запускал
  прогоны и не покрывает весь legacy validator; новые6тестов сами по себе не заменяют весь набор.

Первый scratch Maven1222-case запуск ошибочно не имел ссылок на общие datasets/contracts:
5fail/7errors были missing resources. После добавления тех же ссылок, что в78, сделан новый
**clean** прогон без исключения упавших тестов. Его итог —1219PASS, не первый scratch запуск.

### Контроль ранее удачного результата75

На конечных Maven classes79 (проверены code-source paths) повторена **только доводка** сохранённых
трёх вариантов75, в исходном и обратном порядке рёбер:6/6PASS, все рёбра сохранены по identity,
до/после независимая геометрия без нарушений. Shortest/cheapest остаются2068,786м/0плохих углов,
balanced2192,523м/0плохих углов. Это устраняет воспроизведённую порчу допустимого incumbent.

**Это не новый расчёт79, не кэш алгоритма, не benchmark скорости и не доказательство, что свежий
portfolio выберет ту же сеть.** Третий плохой угол77 и полный путь смены победителей ещё не разобраны.

## Долгие проверки и следующий шаг

- Clean/full79session51226: `.tooling/source79-build.RQBOQ4/apps/api/target` заморожен;
  `source79-full.log`, `source79-result.json` после assertions, `source79-diagnostic.json` до них.
- Full78session46700 **завершён**:1220cases/1216PASS/1compactFAIL/3skip, fixture599,957с.
  Все17подключений/strict exportPASS, но cheapest те же3плохих угла;balanced/shortest2194,257/
  2194,034м/14камер. Подробности в [78](PRIMARY_ROUTING_78.md); target78 больше не занят.
- Roads77session2599 ещё работает на `.tooling/source77-build.FeShm8/apps/api/target`.
- Запущен fresh roads79session69123, runner `.tooling/scenario79.Vhd4HA`, на том же frozen
  target79 после завершения компиляции full79. **Target79 не очищать и после окончания full79,
  пока roads79 не завершится.** Вход239features с SHA`acac7a6885f53faa360becde85bcfea6571eb01c60bcf106da7b2c378918125b`.
  Лог `source79-roads.log`, принятый bundle `source79-roads-result.json`, диагностика до
  assertions — `source79-roads-result.json.diagnostic.json`. Процесс подтверждён живым,
  результаты ещё отсутствуют. Это domain fixture, не HTTP/PostGIS/scale.
- Не перезаписывать эти targets, не дублировать тихий расчёт. Все логи в `.tooling/intake-20260925/`.
- У dataset/helper quality assertions нет cheapest engineering: проверить все три роли явно,
  даже если accepted JSON появился. Дождаться roads79, проверить качество/подключения/strict export,
  сопоставимые изображения и native HTTP→DB→export. До этого runtime61/VPS не обновлять.

На сохранении checkpoint осталось4%недельного лимита. Код/точные границы проверок сохраняются
в Git. `pwsh`/Docker отсутствуют: Compose не проверен. Compact/G2/native/scale/R и цель открыты.
Ускорение query-sort из77 пока не включено; новые изменения — только correctness.

### Уточнение performance-прототипа, без изменения production

Проверен query-local primitive collector, хранящий первые два ordinal в scalar-полях и
создающий массив только на третьем попадании. На том же STRtree/исходном порядке —1614точных
сравнений без расхождений, включая empty/touch/one-ULP и границы роста15/16/17/24/25.
На synthetic4096 envelopes медианы Java11:25hits1,697→1,206мкс;400hits34,282→17,383мкс;
2500hits264,347→116,073мкс. При1hit выделение104→56Б, при2,61hits116→98Б.
Но mostly-empty case0,21hits/query замедлился191→208нс; короткий probe под параллельной
нагрузкой не доказывает устойчивость этой разницы или end-to-end gain.

Это устранило лишнее выделение массива на small-hit пути предыдущего прототипа. Реальное
распределение query hits не измерено; в production79 нет этого изменения, кэша или ослабления
геометрии. Evidence: `.tooling/ordinal-scalar-probe.ZpZnNR/OrdinalQueryProbe.java`, `result.log`.
Перед включением нужны production differential tests (включая расширенный road envelope),
гейты и точное сравнение свежих результатов. Прототип не заменяет текущие quality-проверки.

## Evidence (ignored `.tooling`)

- `preservation79.Ed4k7F/red78-corrected.log`, `green79-final.log`;
- `preservation79.Ed4k7F/PreservedNetworkProbe.java`, `replay75.log`;
- `intake-20260925/source79-final-fast.log`, `source79-final-fast-reports/`;
- `intake-20260925/source79-web.log`, `source79-scripts.log`, `source79-full.log`.
