# Source77: общий узел не блокирует восстановление ввода

25.09.2026, `codex/routing-63-geometry`, `global-tree-77`. Это исправление корректности
финального ДУ, не изменение инженерных норм и не заявленное ускорение. Runtime61 и VPS
не обновлены. Полная цель, compact-control, G2 и приёмка R остаются открытыми.

## Воспроизведение и исправление

`ensureMandatoryEgress` передавал уже принятые рёбра как линии без идентичности узлов.
Их поисковый буфер 0,20 м блокировал собственную общую камеру. Проверка сохранения пути
отказывала, восстановительный поиск отклонял конечную точку до раскрытия графа и оставлял
исходное недопустимое ребро. Независимый валидатор продолжал запрещать его экспорт.

На небольшом DU500-примере без соседней ветви недопустимые 81 м исправлялись в 31,408 м;
с законной соседней ветвью в той же камере оставались 81 м и `FORBIDDEN_CLEARANCE_VIOLATION`.
Четыре сценария нового planner-теста падали на source75; восемь контрольных проходили.

Теперь `RouteAvoidance` сохраняет контекст ранее принятых рёбер, а `JoinedRouteContact`
разрешает только подтверждённый начальный контакт:

- У обоих рёбер ровно один общий ID узла; их фактические концы точно совпадают с этим узлом.
  Совпадения координат разных узлов недостаточно. Допуск координат не расширен.
- Пересечение самих линий — только этот узел. Наложение, дополнительное касание и пересечение
  того же соседа вдали запрещены; остальная часть соседней ветви сохраняет полный буфер.
- Контакт с буфером допускается лишь на начальном прямом участке. Повторный вход в буфер
  после поворота запрещён. Технические коллинеарные вершины не превращаются в препятствие.
- Общий узел должен быть концом всей собранной трассы, а не только временной части ввода.
  Это проверяется до разделения terminal/outside в обоих wrapper-методах.
- Поиск с таким контекстом не использует старый per-run route-cache: его ключ не содержит
  идентичность узлов. Между свежими расчётами результаты не переиспользуются; новый кэш не добавлен.
  Обычный поиск без общего узла сохраняет прежний API и hooks.

Поворот на UTM выявил дополнительную численную проблему: `covers` вычисленной overlay-точки
давал ложный отказ при расхождении порядка 1e-10 м. Исправлена структура проверки исходного
прямого участка, а не численный допуск. Проверки всего road/tram-пересечения, собственных
и чужих ОКС, финальной геометрии и арифметики не отключались.

## Проверенные gates

- 12 постоянных `OfficialFinalDiameterSharedJunctionTest`: фактический planner repair/retention,
  оба порядка ветвей, обращённое ребро, разные ДУ, повороты/переносы, независимый валидатор.
- 17 постоянных `JoinedRouteContactTest`: точная идентичность, overlap/reentry/interior negatives,
  collinear/UTM cases, оба wrapper, неизменность геометрии и cancellation.
  Два wrapper-теста отдельно подтверждены RED на промежуточном77 и GREEN на конечном.
- Конечный focused-набор: 111 PASS. Независимое ревью: 65/65 adversarial checks,
  включая 48 округлённых поворотов/переносов и оба wrapper-контрпримера; открытых замечаний
  в этом scope не осталось. Это не приёмка всей системы.
- **Конечный clean/fast Maven: 1166 cases / 1163 PASS / 0 failures / 0 errors / 3 scale skipped**,
  114 классов. Исключены только `OfficialDatasetRoutingTest`, `OfficialCorridorDatasetTest`,
  `OfficialCorridorControlRecoveryTest`. Snapshot исходников совпал с checkout.
- Web: 36 Vitest + 37 script tests, lint и typecheck PASS.

Ограниченный повтор `ensureMandatoryEgress` на принятом75 сохранил balanced, а у двух ветвей
shortest/cheapest изменил длины 192,877→175,985 м и 20,472→19,055 м; geometry issues пусты.
Это **не свежий расчёт**, не повтор sizing/depth/economics и не доказанный выигрыш финального
portfolio. Нельзя выдавать эти длины или время отдельного этапа за качество/скорость всей системы.

## Полный прогон завершён: найдена регрессия качества

Full77session79101 завершён: **1170 cases / 1166 PASS / 1 прежний compact failure / 0 errors /
3 scale skipped**, 117 классов. Исходный fixture — 526,533 с, concave — 4,277 с.
`source77-result.json` записан после assertions и strict export трёх ролей, но это **не допуск
качества всех вариантов**: текущий fixture проверяет engineering только balanced/shortest.

| Роль77 | Подключения | Длина, м | Новые камеры | Стоимость, ₽ | Expert issues |
| --- | ---: | ---: | ---: | ---: | --- |
| balanced | 17/17 | 2192,523 | 14 | 302839881,84 | 0 |
| shortest | 17/17 | 2192,300 | 14 | 302818189,50 | 0 |
| cheapest | 17/17 | 2073,965 | 11 | 283374789,18 | 3 плохих угла в одном issue |

Geometry/sizing issues пусты, depth/economics/strict export проходят. Но cheapest ухудшился
относительно75 (2068,786 м / 11 камер / 0 expert), shortest тоже стал длиннее. Локальный
shared-junction repair исправен по regression-тестам, а последующая доводка/выбор portfolio
требует разбора. **Не обновлять runtime по этому результату и не заявлять улучшение качества.**
Отдельный bounded trace должен выяснить, почему retention меняет ранее допустимые ветви.

Диагностическое сравнение без сглаживания: `source77-cheapest-diagnostic-comparison/side-by-side.png`;
оно подписано версией77 и не означает приёмку. Эталон Евгения — геометрический ориентир,
не независимо подтверждённый нормативный результат.

## Длительные проверки и сохранённые процессы

1. Clean/full77 без исключений: **session79101 завершён**, отдельный snapshot
   `.tooling/source77-build.FeShm8/apps/api`, лог `intake-20260925/source77-full.log`.
   Accepted output — `source77-result.json`, before-assertions — `source77-diagnostic.json`.
   Full reports сохранены в `source77-full-reports/`. Target теперь использует новый roads77.
2. Fresh roads+kindergarten75: **session70338 завершён exit1**, `.tooling/scenario75.VxnM8P`,
   `.tooling/source75-build.9a8BDz/apps/api/target`. Лог `source75-roads.log`; accepted
   `source75-roads-result.json` **не создан**; diagnostic с суффиксом `.diagnostic.json`.
   2838,622 с, все роли16/17; variants побайтно-структурно совпали с roads72:22/26/30 плохих
   углов и3/1/1 близких пар поворотов. Строгий экспорт не выполнялся после отказа assertion.
   Snapshot75target свободен. Это domain fixture, не HTTP/PostGIS/scale; скорость на параллельной
   нагрузке и с30-секундным JFR не является изолированным SLA-замером.
3. Fresh roads77 **session2599**, runner `.tooling/scenario77.iPcahu`, запущен отдельно
   на замороженном snapshot77 после full77; лог `source77-roads.log`,
   выход `source77-roads-result.json` и отдельный before-assertions diagnostic. Любой результат
   helper дополнительно проверить на engineering cheapest: его пропуск выявлен полным77.

Full75 уже завершён: 1129 cases / 1125 PASS / 1 прежний compact failure / 0 errors / 3 skip.
Fresh исходного набора 526,467 с, 17/17, strict export трёх ролей PASS, exact variants74:
shortest/cheapest 2068,786 м / 11 камер / 22 поворота. Компактный порог `<1860 м / ≤13 камер`
не выполнен и не ослаблен. Roads72 завершился отказом: все роли 16/17, принятого результата нет.

`pwsh` и Docker отсутствуют: выполнены прямые Java11/web-команды, Compose smoke не проверен.
Не проверены новый native HTTP→DB→export, нагрузочные gates и новый пользовательский runtime.
Исторические локальные проверки/VPS deployment не заменяют проверку77.

## Следующий отдельный correctness-scope

Terminal builder ищет demand→root, затем сохраняет root→demand. Воспроизведён вход в дорогу
под 90° в поиске и под 40° после обращения, а также лишний объезд в зеркальном случае.
Направление должно учитываться и при выборе нормали ОКС, и в поиске, и при проверке всей
собранной линии. Не подменять правило угла входа требованием обоих углов. Это ещё **не исправлено77**
и не доказанная причина всех пропущенных вводов/лишних поворотов roads72.

После свежих расчётов нужны разбор недостающих подключений, компактности и профилирование
на одинаковых входах; затем live flow и сравнение с разметкой Евгения. Нельзя подгонять
координаты/ID входного набора или ослаблять нормативный валидатор ради похожей картинки.

## Evidence (ignored `.tooling`)

- `shared-junction77-tests-dVdGK4/final-red.log` — 8 PASS / 4 RED на75;
- `junction77-rotation-diagnosis-ODoGs4/trace.log` — UTM overlay precision;
- `junction77-contact-tests-ldBqAd/final-green.log` — конечные 111 focused PASS;
- `junction77-review-hA6qx1/REVIEW.md`, `final-adversarial.log` — независимое ревью;
- `junction77-final.JYU5nY/original75-retention-details.log` — ограниченный replay;
- `intake-20260925/source77-fast-final.log`, `source77-fast-final-reports/` — конечный быстрый gate;
- `intake-20260925/source77-web.log` — web gates.
- `intake-20260925/source77-full.log`, `source77-full-reports/` — полный gate и открытая регрессия;
- `perf-audit77.qp8I1X/AUDIT.md` —30с late-roads75 JFR:444/951 samples в boundary intersection,
  283/951 в constraint query, из них138 в сортировке. Гипотеза primitive-ordinal collector
  дала1048exact comparisons без расхождений и ускорение синтетических больших queries;
  **production patch не делался**, распределение реальных hits и end-to-end выигрыш неизвестны.

Промежуточные отчёты `source77-pre-wrapper-fast-reports/` не заменяют конечные 17 guard-тестов.
