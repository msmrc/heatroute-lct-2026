# Доводка70: ограниченная пространственная подготовка валидатора

Дата:25.09.2026. Ветка `codex/routing-63-geometry`, `global-tree-70`.
Тип: оптимизация с сохранением геометрических правил и контрактов.
Продолжает [измерения69](PRIMARY_ROUTING_69.md); стратегии построения дерева не меняет.
Runtime61/master/VPS сохранены. Полного fresh70 и release gate пока нет.

## Причина и изменение

В69 бюджет100000координат не удерживал полные наборы препятствий для7–8ДУ: изменение ДУ
заново создавало88буферов. `PreparedValidationConstraints` разделяет общие точные копии
источников, компактные границы успешных буферов и тяжёлые геометрии. После вытеснения
тяжёлого буфера далёкое препятствие не буферизуется повторно. Близкое — восстанавливается.

Первое появление пары источник/отступ всегда проходит настоящий JTS buffer: нельзя скрыть
его отказ spatial skip. Отбор использует весь envelope полилинии, фактический buffer envelope
и полный осевой отступ с outward rounding. Последний нужен для точного контроля собственного
ввода, где epsilon-shrink не действует. ДУ проверяется и у далёких объектов. Нормали и
mandatory egress видят все features. Дубли/порядок сохранены; изменение геометрии инвалидирует
все ДУ typed ID. Маршруты и ответы между runs не сохраняются.

Лимиты:512источников,2048metadata-записей,100000резервируемых координат суммарно: shared
source snapshots,2координаты на bounds,буферы и резерв segment index. Это не лимит байтов
JTS/временных объектов/ссылок, возвращённых потребителю. Attrs/userData не удерживаются.
Нестандартные rules/catalog/crossing, Geometry/Factory/precision, нечисловой XY, пустые ID
и неподдерживаемый ввод используют прежний полный путь. Eligibility не переносит исключения
раньше старого валидатора. Standalone/export независим, subclass validator hook сохранён.

## Проверки checkpoint

- Isolated Java11/javac:101PASS/0FAIL (`source70-focused.log`), включая14новых тестов
  пространственной подготовки и12тестов сессии, attachment, ресурсы/окна/владение,
  старую подготовку, relocation68 и selector. Frozen `target`69 не перезаписывался.
- Rotating8DU/180coord-бюджет: старая подготовка16повторных buffers на оборот, новая8;
  issues совпадают. Изменённый маршрут заново проверяется и отклоняется при пересечении.
- Полный осевой отступ±1мм, касание/собственный prefix, обход за envelope концов,
  специальные пересечения, дубли/порядок/мутации, metadata/source/buffer eviction,
  budget/ownership, custom/precision/ошибочные входы проверены.
- Genuine RED eligibility: malformed attrs выбрасывались даже при `edges=[]`, в отличие
  от исходного валидатора. Fail-soft fallback исправил порядок; с непустым маршрутом
  сохранены класс/сообщение исходного исключения (`source70-eager-eligibility-red.log`).
- Validation-only replay принятого69:3роли×3повтора, точное равенство issues и0ошибок.
  После первых двух ролей счётчик forbidden buffers стабилен655, резерв43183координаты;
  следующие7проверок новых буферов не строят (`source70-validation-replay.log`).
  Это **не fresh route70** и не независимый benchmark скорости.

Независимое статическое review не нашло подтверждённых дефектов в scope ownership/bounds/
invalidation/exception order/budget; это не замер heap и не повтор тестов. Web36/scripts37,
lint/typecheck PASS (`source70-web.log`).

CountingPolygon-тесты теперь явно проверяют fallback нестандартной геометрии: изменены
счётчики подготовки, но не требования к маршрутам/ошибкам. Обычная геометрия и экономия
подготовки проверяются новым набором. Full Java70/fresh70/live/Compose/scale ещё не
выполнены. Пороги качества не менялись; source69 по-прежнему последний full/fresh checkpoint.

## Roads+kindergarten: профиль69, не результат70

Fresh69 на239features завершён:1054,672с,17/17,все3роли/geometry/ДУ/depth/economics/strict
export PASS. Balanced2267,129м/12камер/29поворотов/314371729,38₽; shortest/cheapest2260,959м/
11камер/33поворота/310528751,55₽. Все3роли без текущих expert issues. Это fixture, не HTTP job.
Accepted `source69-roads-result.json`, отдельный before_assertions diagnostic не заменяет его.
Java11/Xmx1g/ActiveProcessorCount2; JFR/короткие focused-пробы пересекались, не isolated benchmark.
30с JFR:1604main samples,90,3% включают
`improveWholeTree`,86,3% `segmentAllowed`,27,7% `ConstraintIndex.query`. Из444сэмплов query
220 на STRtree query,172 на сортировку ordinal, остальные преимущественно на сбор результата.
Это включающие доли, не складываются. Evidence `source69-roads-search.jfr` и samples JSON,
stack-depth128. Фазы independent390,905с/shared526,395с — ещё не итог полного расчёта.

Сценарий нагружает поиск видимости, не только validation. Следующее ускорение обязано
сохранить порядок препятствий/узлов и tie-breaks; нельзя молча заменить sorted query
неупорядоченным STRtree visitor. Пока это диагноз, не выполненный патч.
Гипотеза коротких совместимых вводов80→60м описана в69: ещё не реализована и не выдана за
улучшение фактического дерева.

## Следующий gate

Roads69 принят и закончен; target можно перестроить. Далее clean/full/fresh70 и сравнение всех
вариантов с69:coverage/ДУ/depth/economics/strict
export. Измерить фазы; не объявлять speedup по счётчику buffer. Compact-control≤13камер/
<1860м остаётся красным. G2,roads,native,Compose/scale,R-этапы и общая цель открыты.
