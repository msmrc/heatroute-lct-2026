# Correctness72: отступы road/tram и локальные special-секции

Дата25.09.2026. Ветка `codex/routing-63-geometry`. **Work in progress, не acceptance G2**.
Последняя полностью прогнанная база — [source71](PRIMARY_ROUTING_71.md); runtime остаётся61.
Fetch origin повторён:master по-прежнему `28c7059`, новых изменений Артёма нет.
Код `a410259` отправлен, remote SHA `a410259e580740ae8b2995fc169dacceb724b099` проверен.
Clean/full72 **без исключённых тестов** запущен отдельно (`source72-full.log`,session53894).
Target занят compiled72: до завершения не запускать Maven/не перезаписывать классы.
Accepted output `source72-result.json` появится только после fixture assertions; diagnostic отдельно.

## Основание и реальные RED

Техническое приложение §3.1/§4: ось пары требует R+W/2; вне конкретного разрешённого
пересечения действует горизонтальный отступ. Road/tram: R1,5м, угол входа≥45°, прямое
пересечение полигона+3м с двух сторон **вдоль трассы**. Текст §4 об overlap требует нового
участка на каждой границе общей части; максимальный коэффициент применяется только там.
Источники и нерешённые вопросы — [G2](G2_SPECIAL_CLEARANCE.md), реестр документов —
[EXPERT_ROUTING_2026_09_25.md](EXPERT_ROUTING_2026_09_25.md). Downloads не изменялись.

На compiled71 preparation-тесты дали16RED/7PASS, export10RED/6PASS. Основной контрпример:
приДУ100 ось1,700м от дороги принималась вместо1,755м. Другие genuine RED:
поворот внутри защитной части, обрезанный crossing, объединение двух отдельных пересечений,
MBR-угол вместо границы, весь overlap как один union. Во время реализации отдельно воспроизведены
ошибки entry-vs-exit angle, потери5мм overlap и `PreparedCorridor.path`, возвращавшего
незавершённый переход по одной только допустимости отдельных звеньев.

## Реализация

- Чистый `RoadCrossingClearance`: отдельные внутренние интервалы polygon/multipolygon;
  касание/движение по границе не дают crossing-exemption; прямота и полные3м; реальная
  граница входа; точное расстояние до объекта на оставшихся частях. Ключевой clearance
  не ослабляется допуском округления секций. Проверка угла входа направленная; обратный
  обход visibility-графа проверяет входящие рёбра. Готовые ответы между расчётами не хранятся.
- ДУ-зависимая пространственная подготовка road/tram в обоих отборах. Буфер используется
  для навигации/индекса, но не превращает весь road в запрет: точные crossing-проверки отдельные.
- Порталы вынесены наmax(3,axisClearance)+0,25м; добавлены точки обхода внешнего buffer.
  Поворот не прерывает protective straight. Отдельное звено может быть предварительно
  допустимым, но итоговый `lineAllowed` обязателен и у router без heading, и у corridor.path.
- Sections режутся по **всем** началам/концам интервалов; атрибуты/коэффициент overlap
  относятся только к общей части. Двухразовые пересечения одного объекта не объединяются
  через свободное пространство. Сохранён5мм overlap, не потерян сантиметровым допуском поиска.
- Export preflight повторно проверяет исходную и фактически выдаваемую полилинии,
  финальный ДУ, исходные road/tram и локальное покрытие section labels до первой feature
  любого выбранного варианта. Persisted valid/rank/смета не заменяют geometry guard.
- Для split-точек допускается только доказуемое округление к миллиметрам от уже строго
  допустимой оси:смещение≤sqrt(2)×0,5мм+1мкм; прямота хорды учитывает удвоенную погрешность
  двух концов. Осевой отступ, угол и защитные длины не ослаблены. Проверены78наклонных
  комбинаций и отдельно нарушение отступа на0,5мм внутри разрешённой погрешности координаты.

Characterization-тесты не ослаблены: старые LineString road заменены разрешёнными контрактом
Polygon; invalid DU теперь обязан отклоняться и у road. Тест union переписан по прямому
требованию §4, не для совпадения с реализацией. Бюджеты/стабильный порядок/границы сохранены.

## Проверки текущего checkpoint

- Focused Maven Java11/Xmx1g/CPU2:263tests/0fail/0error/0skip,17классов.
  `source72-focused-repeat.log`; первый запуск262tests дал2старых несовместимых road-LineString
  fixture, исправлены на официальный polygon-контракт и повторены.
- 18официальныхДУ,±1мм/точнаяграница,search+standalone/session,127/128индекс,
  eviction/geometry/type/DU mutation,perpendicular/45°,3мзащита,боковойход,разныеcrossings.
- Web36/scripts37/lint/typecheck PASS (`source72-web.log`).
- Broad на промежуточном72:1044cases/1037PASS/2fail/2error/3skip. Два несовместимых
  road-LineString fixture заменены polygon с сохранением checks; два oblique-section
  отказа действительно вызваны округлением коллинеарной вершины, исправлены с witness исходной оси.
- Независимое review нашло3реальных дефекта нового кода: envelope приближённого buffer
  пропускал1,737983м при требуемых1,755м для rotated road/128constraints; depth retry
  вызывал NPE; гипотетическое продление звена вводило угол чужого непересечённого компонента.
  Все3 воспроизведены отдельным тестом RED0/3→GREEN3/3; вместе с новымиgeometry/prep41PASS.
  Исправлены conservative source+axis bounds, отделение forbidden depth-rule, отбор фактических входов.
- Финальный быстрый Maven на исправленном дереве: **1049cases/1046PASS/0fail/0error/3scale skip**,
  106классов. Исключены только3долгих класса:OfficialDatasetRoutingTest,
  OfficialCorridorControlRecoveryTest,OfficialCorridorDatasetTest. `source72-fast-final.log`.
  Это не full/fresh gate; следующий запуск — clean без исключений.
- Export-agent:14positive+24negative/38PASS,42existingPASS; основной Maven повторил новыеcases.
  `.tooling/g2-export72-rounded.Haiohl/`; контрпримеры доfix сохранены отдельно.
- Изображение **fresh71**, не72: `.tooling/intake-20260925/source71-cheapest-comparison/side-by-side.png`,
  просмотрено;2068,786м/11новых камер/17of17 vs reference1913,859м/11узловых маркеров.
  Reference не сертифицирован по новым проверкам. Оба изображения — одинаковые рамка/масштаб.

## Диагностическая перепроверка старого roads69

Только read-only primitive recheck, **не fresh72, не принятый результат**. В прежних
shortest/cheapest roads69 найдено8рёбер с нарушениями среди94road-ограничений:7непрямых
special/защитныхчастей и1недостаточный боковой отступ (ось1,467297м приДУ100требуемых1,755м).
Это объясняет, почему прежний17/17/strict export69 нельзя использовать как доказательствоG2.
`source72-old-roads-audit.log`,bounded ignored helper `RoadResultAudit.java`; вход не изменялся.
Нужна новая генерация с правилами72, а не перерисовка/принятие прежнего JSON.

## Что ещё обязательно проверить

- [ ] Прямые collinear продолжения special через границу нескольких логических рёбер:
  текущий строгий per-edge guard может консервативно отклонить такой маршрут; не делать
  full-footprint root exemption ради обхода этого отказа.
- [x] Millimetre rounding произвольного (не45/90°) crossing в фактически экспортируемых секциях.
- [x] Независимое review:3подтверждённых findings исправлены с genuine RED→GREEN.
- [ ] Полная Java suite; compact≤13камер/<1860м остаётся отдельным RED.
- [ ] Fresh original+roads/kindergarten, all3roles, geometry/DU/depth/economics/export/time.
- [ ] PostGIS/in-memory window equivalence, native HTTP, Compose (инструмент отсутствует), scale.
- [ ] Остальные G2типы utilities/existing-network DU, depth и спорные G3требования.

Source72 не является «идеальным алгоритмом» или готовностью к сдаче. Ни один R/G-gate
не закрывается по этому focused подмножеству. Runtime/VPS/master не обновляются по одним unit-тестам.
