# HeatRoute аудит соответствия официальному ТЗ и roadmap

**Статус документа:** рабочая база для ПМа и разработчиков
**Дата аудита:** 15 сентября 2026 года
**Последнее обновление:** 18 сентября 2026 года
**Главный вывод:** видеовстреча организаторов сузила активный supplied-dataset scope. Java-контур,
2D routing и эксплуатационная база сохраняются. Q&A-правила normal egress, connect-vs-penalty,
bend cost, overlapping special coefficients, per-ray tie-in и profile-aware rank/export реализованы
и проверены локальным supplied-file циклом. Spatial-window calculation и повтор полного Java 11
gate после этих изменений остаются открытыми.
Реконструкция и R8 остаются расширенными режимами.
Полная запись решений и противоречий находится в `ORGANIZER_VIDEO_CLARIFICATIONS.md`.

> **Active-scope override.** Разделы этого roadmap, где реконструкция названа обязательным P0 или
> отсутствие baseline блокирует score/export supplied dataset, отражают прежнюю письменную
> трактовку. Для ближайшей разработки их заменяет раздел уточнений из видеовстречи. Историческую
> реализацию не удалять: она остаётся strict-profile до письменного финального подтверждения.

## 1. Для чего нужен этот документ

Документ фиксирует единое понимание команды:

- что уже реализовано и может быть сохранено;
- что реализовано в другой предметной модели и поэтому не закрывает конкурсные требования;
- какие обязательные функции отсутствуют;
- в каком порядке вести разработку;
- какими проверками заканчивать каждый этап;
- какие решения и риски должен контролировать ПМ.

Источники требований:

1. `2. ДИТ.pdf` — основное техническое задание конкурса.
2. `Техническое приложение.docx` — обязательные входные и выходные контракты, таблицы диаметров, стоимости, ограничений и формулы.
3. `ORGANIZER_VIDEO_CLARIFICATIONS.md` — более поздняя рабочая трактовка supplied dataset и список
   вопросов, требующих письменного ответа.

PDF/DOCX имеют приоритет для точных таблиц и контрактов. Более поздняя видеовстреча определяет
активный supplied-dataset scope; прямые противоречия выносятся на письменное подтверждение.

## 2. Коротко для ПМа

### 2.1. Что произошло

Первоначальная разработка велась по расширенному внутреннему ТЗ и не совпадала с официальной
постановкой. 15 сентября production path был полностью переключён на Java: Java-модуль занял
`apps/api`, Compose/CI/web используют только его, прежний backend удалён из рабочего дерева.

Официальное ТЗ задаёт другую обязательную постановку:

- Java 11 и Spring Boot 2.6.3;
- один совмещённый GeoJSON с фиксированными типами объектов и полями;
- обработка всех перспективных ОКС за один запуск;
- автоматический выбор врезок;
- построение общей разветвлённой сети;
- суммирование расходов, подбор условных диаметров и проверка предельных длин;
- расчёт существующей сети и реконструкции только для расширенного strict-profile с полными полями;
- точные правила пространственных ограничений, стоимости и ранжирования;
- строгий выходной GeoJSON;
- загрузка до 3 ГБ, выгрузка до 500 МБ и до 50 пользователей.

Платформенный блокер устранён. Выявленные видеовстречей supplied-profile разрывы реализованы в
текущем рабочем дереве, но прежние R9 gates были выполнены до этих изменений и не подтверждают их.
Для 2–3 ГБ calculation flow материализует только core-сеть и точки подключения, а ограничения и
существующие ОКС получает через PostGIS spatial windows в EPSG:32637. До принятия изменения нужна
отдельная проверка эквивалентности и памяти для плотного окна.

### 2.2. Что можно сохранить

- React/TypeScript, дизайн-систему и UX официального импорта/диагностики.
- PostgreSQL/PostGIS и двойное хранение WGS84/EPSG:32637.
- Java durable-job lease/cancel/recovery протокол.
- Принципы независимой проверки результата после маршрутизации.
- Набор геометрических тестовых приёмов; файловые synthetic fixtures удалены, узкие отрицательные
  случаи теперь формируются непосредственно в unit tests.
- Docker-окружение и same-origin web proxy.

### 2.3. Что нельзя считать выполненным

- Старые milestone M0-M6 относительно официального ТЗ.
- Сам факт Java cutover как закрытие предметной части ТЗ.
- Построение одной трассы как обработку всех перспективных ОКС.
- Общий каталог стоимости как реализацию официальных ставок и формул.
- Generic forbidden zones и crossing portals как реализацию точной таблицы ограничений.
- Проверку переданного вертикального профиля как автоматическую трассировку по глубине.
- Pandapipes-гидравлику как закрытие конкурсного расчёта. Полная гидравлика в обязательную часть ТЗ не входит.
- Внутренний preview centerline/corridor как официальный выходной GeoJSON: официальный strict
  seven-type adapter существует отдельно и доступен только для complete-вариантов.

### 2.4. Оценка готовности

Это ориентир для планирования, а не процент выполнения кода:

| Область | Оценка текущей готовности |
|---|---:|
| Продуктовый каркас и Java UI | 85-90 % |
| Хранение, импорт и durable jobs | 90-95 % |
| Обязательная конкурсная 2D-логика | 85-90 % |
| Официальные входные и выходные контракты | вход 90 %, выход 90 % на contract-complete input |
| Финальная готовность к сдаче | 90-95 % внутри команды; остаток зависит от данных/решений организатора |

## 3. Коротко для разработчика

Текущий Java-контур строит три obstacle-aware стратегии, независимо проверяет полилинии и считает
bottom-up расход/ДУ. До нового P0 он всё ещё автоматически реконструирует existing network и
считает экономику complete только при наличии reconstruction baseline; для supplied dataset эту
зависимость требуется удалить. Измеренная R9-приёмка подтверждает инфраструктуру, но не новые
Q&A-правила.

Минимальный официальный pipeline должен выглядеть так:

1. Потоково прочитать единый GeoJSON и провалидировать контракт.
2. Преобразовать WGS 84 в EPSG:32637 для всех метрических операций.
3. Построить существующую геометрию и допустимые кандидаты врезок в камеры/линейные участки.
4. Для каждой demand point построить обязательный выход из собственного OKS по нормали.
5. Построить допустимые independent/shared/diverse маршруты к кандидатам.
6. Учесть углы 45°/90°, коэффициент 1,5 нестандартного поворота и max `K_special` перекрытий.
7. Преобразовать маршруты в деревья; создать камеры во всех разветвлениях и врезках.
8. Снизу вверх просуммировать `flow_tph`, назначить ДУ и проверить непрерывную длину.
9. Для каждого demand сравнить marginal connection cost с официальным penalty.
10. Посчитать каждую новую ветку в existing chamber отдельной врезкой.
11. Рассчитать стоимость/score без обязательной реконструкции и выбрать до трёх вариантов.
12. Независимо проверить инварианты и потоково выгрузить официальный GeoJSON.
13. Только в strict-profile с полным baseline дополнительно рассчитать реконструкцию; optional R8
    запускается отдельно и не подменяет 2D-success.

## 4. Матрица расхождений

| Блок | Что есть сейчас | Что требуется | Статус | Приоритет |
|---|---|---|---|---|
| Backend stack | Java 11, Spring Boot 2.6.3, springdoc 1.7.0 | Тот же стек | Закрыто | P0 |
| Развёртывание | Java API + PostGIS + web + Caddy; Compose 3.8 | Ubuntu Server 22, docker-compose 1.29.2 | Clean Ubuntu 22 + Compose 1.29.2 CI прошёл; VPS не обновлялся | P0 |
| Вход | Потоковый GeoJSON, appendix и official contest dataset profiles, PostGIS | Официальный контракт, до 3 ГБ | Подтверждённый конкурсный файл поддержан; exact 3 GiB streaming boundary passed on Java 11 | P0 |
| Расчётная CRS | WGS84 + EPSG:32637 при импорте | Фиксированная EPSG:32637 для метров | Закрыто для импорта | P0 |
| Объём запуска | Все 17 ОКС и 34 ОКС в 2× gate обрабатываются одним immutable run | Все `oks_future` за один запуск | Реализовано и измерено | P0 |
| Врезки | R3 candidates используются R4 planner; разные стратегии выбирают разные подключения | Автоматический поиск и выбор | Функционально реализовано | P0 |
| Совместное подключение | Independent/shared/diverse деревья; shared использует денежный seed search, присоединение к построенным рёбрам и whole-tree local search с section-aware split/contract | Общие участки, разделение потоков, отдельные подключения | Реализован `cost-tree-5`; focused/full-dataset проверка ожидается | P0 |
| Камеры | Явные route nodes и независимый validator | Разветвления только в камерах, максимум четыре примыкающих участка | Реализовано и покрыто тестами | P0 |
| Геометрия | JTS visibility search, adaptive STRtree, buffered constraints, shortcut normalization и final validation | Прямые рациональные участки, отсутствие зигзагов и пересечений вне узлов | Supplied-file: все варианты валидны после запрета транзита через OKS и повторной проверки с финальным DU; оптимизация runtime остаётся открытой | P0 |
| Расходы | Bottom-up sizing подключён к принятым деревьям R4 | Сумма `flow_tph` подключённых через участок ОКС | Реализовано для новой сети | P0 |
| Диаметры | Официальный Java-каталог назначает DU каждому рассчитанному и реконструируемому участку | Точная таблица из 18 ДУ | Реализовано | P0 |
| Предельная длина | Bottom-up sizing автоматически повышает DU с непрерывным same-DU tracking | Сброс только при смене ДУ | Реализовано и покрыто всеми границами каталога | P0 |
| Существующая сеть | Upstream reconstruction реализована | Для supplied profile реконструкция не обязательна и не блокирует результат | Реализовано локально; verification pending | P0 |
| Ограничения | Динамические OKS buffers и запретные типы участвуют в search и final validation | Точная опубликованная 2D-таблица | Полная exact/boundary/negative matrix; OKS footprint hard-blocked, кроме terminal normal-egress собственного объекта | P0 |
| Специальные проходы | Union span и composite type поддержаны | Один overlap span с максимальным `K_special` | Реализовано локально; verification pending | P0 |
| Стоимость | Segment-wise bend ×1.5; tie-in считается по каждому root ray | Bend ×1.5; 5 млн за каждый новый луч | Supplied-file verified; shared score 48.672753143 ниже independent 64.318630863 | P0 |
| Ранжирование | Supplied rank не требует reconstruction baseline; strict требует | Supplied profile ранжируется без reconstruction baseline | Supplied-file verified: full-coverage shared 17/17 выбран rank 1 | P0 |
| Неподключённые ОКС | Direct exclusive spur сравнивается с penalty | `no_route` участвует в оптимизации против marginal connection cost | Реализовано локально; verification pending | P0 |
| Выход | Supplied adapter допускает отсутствие reconstruction | Валидный supplied result скачивается без обязательной реконструкции | Реализовано локально; reduced whitelist требует подтверждения | P0 |
| Глубина | Default run 2D-only; `depth_enabled=true` включает сохранённый R8 | Отдельный второй этап, не блокирующий 2D | Реализовано локально; verification pending | P1/бонус |
| Большие файлы | Streaming parser/writer, keyset paging и PostGIS route windows | Вход 3 ГБ и выход 500 МБ без whole-file heap | Dense-window/equivalence gate нового calculation path | P0 |
| Нагрузка | Bounded workers, heartbeat, contract+SHA dedup и воспроизводимый probe | До 50 пользователей | 50 concurrent API sessions measured; граница тяжёлых jobs явно документирована | P0 |
| Документация | Submission brief, demo, algorithm, acceptance, evidence и JSON Schema | Конкурсное описание алгоритма, no-route, выхода, глубины и границ | Закрыто и синхронизировано | P0 |

## 5. Обязательный официальный контракт

### 5.1. Входные типы

| `object_type` | Геометрия | Основное назначение |
|---|---|---|
| `source` | Point | Источник теплоснабжения |
| `heat_network` | LineString | Существующий участок сети |
| `heat_chamber` | Point | Существующая тепловая камера |
| `oks_future` | Polygon или MultiPolygon | Перспективный ОКС |
| `oks_connection_point` | Point | Заданная точка подключения ОКС |
| `oks_existing` | Polygon или MultiPolygon | Существующий ОКС и запретная область |
| `restriction` | По типу ограничения | Пространственное ограничение |

Обязательные технические поля включают `id`, `object_type`, `diameter`, `flow_tph`, `heat_load`, `oks_id`, `restriction_type`, `upstream_object_id` в соответствии с типом объекта.

### 5.2. Выходные типы

Один GeoJSON FeatureCollection должен содержать:

- `heat_network` — новые участки;
- `tie_in` — точки врезки;
- `heat_network_reconstruction` — реконструируемые части существующей сети;
- `heat_chamber` — новые камеры;
- `heat_chamber_reconstruction` — реконструируемые камеры;
- `technical_node` — точки смены параметров;
- `variant_summary` — ровно одну непространственную сводную запись на вариант.

Каждый тип получает только собственные обязательные поля. Поля других типов со значением `null` добавлять нельзя.

## 6. Точные расчётные правила, которые надо перенести без интерпретации

### 6.1. Диаметры

Техническое приложение содержит 18 строк для ДУ 50-1400 мм. Для каждой строки заданы:

- пропускная способность в т/ч;
- предельная непрерывная длина;
- стоимость нового строительства за метр;
- стоимость реконструкции за метр;
- габариты пары труб для дополнительной задачи по глубине.

Эти значения должны находиться в версионируемом справочнике и иметь golden tests. Нельзя заменять их synthetic-каталогом или приблизительной формулой.

### 6.2. Камеры и врезки

- Если точка врезки не далее 10 м от существующей камеры и после подключения к ней примыкает не более четырёх участков, используется эта камера.
- Иначе в точке врезки строится новая камера.
- Разветвления выполняются только в камерах.
- Стоимость камеры определяется максимальным примыкающим ДУ: 3, 5, 8 или 12 млн рублей по диапазону.
- Каждая независимая врезка стоит 5 млн рублей.
- Реконструкция существующей камеры учитывается один раз и только для камеры, используемой как точка врезки.

### 6.3. Неподключённые ОКС

Штраф по каждому неподключённому ОКС:

`100 000 000 + 500 000 * flow_tph`.

Остальная рассчитанная сеть должна сохраняться и выгружаться.

### 6.4. Ранжирование

`S = 0,7 * (C / 25 000 000) + 0,3 * (L / 100)`.

`C` включает новые участки, новые камеры, врезки, реконструкцию участков и камер, а также штрафы. `L` включает новые и реконструируемые линейные участки. Чем меньше `S`, тем выше вариант.

## 7. Целевая архитектура

### 7.1. Принятая архитектура

- React/TypeScript frontend и same-origin Nginx proxy.
- Для промежуточной проверки R4 frontend отображает интерактивную векторную GIS-карту MapLibre GL:
  расчётный граф переводится из EPSG:32637 на CARTO Positron, а официальный контекст запрашивается из PostGIS
  в WGS84 по ограниченному bbox. Метрическая схема EPSG:32637 сохранена вторым режимом. Это
  demo/evidence слой, а не замена обязательной визуализации официального семитипного GeoJSON после
  R7; внешняя векторная подложка является best-effort визуальным контекстом, а не частью вычислительного контура.
  В рабочем режиме карта занимает всю доступную область, инспектор и сводка результатов отображаются
  плавающими панелями, а навигация сворачивается без изменения расчётного состояния.
- Единственный API и вычислительное ядро на Java 11 / Spring Boot 2.6.3.
- springdoc-openapi-ui 1.7.0, PostgreSQL/PostGIS, JDBC, Liquibase, JTS и Proj4J.
- Git history хранит старую реализацию только для археологии; runtime и активный source tree её не содержат.
- Большие GeoJSON обрабатывать потоково: parser -> validation -> staging storage -> spatial indexes. Не собирать весь FeatureCollection в памяти.
- Расчёты выполнять асинхронно через PostgreSQL job rows, atomic claim, lease, retry и cancellation;
  перед нагрузочным тестом worker выносится в отдельный JVM-процесс без смены протокола.

### 7.2. Отклонённый вариант

Возврат второго backend-контура отклонён: он создаёт drift контрактов и не закрывает ни одного
обязательного R4–R9 gate.

### 7.3. Статус этапов на 16 сентября

| Этап | Статус | Остаток до gate |
|---|---|---|
| R0 | Закрыт | — |
| R1 | Закрыт для bounded in-process worker contour | Отдельный process — только если потребуют R9 measurements |
| R2 | Функционально закрыт, включая contract+SHA replay/dedup, 3 GiB boundary и full 2× topology gate | Organizer-approved maximum profile |
| R3 | Закрыт: topology, candidates, split и persisted selected tie-in targets | — |
| R4 | Функционально реализован: obstacle-aware routing, coverage-first fallback/ranking, три стратегии, GIS, adaptive STRtree и независимая validation | Полный Java 11 gate: 136 тестов, supplied fixture preferred = 17/17; live Compose smoke обязателен после пересборки |
| R5 | Функционально закрыт на contract-complete fixtures | В поставленном файле отсутствуют baseline/direction поля реконструкции |
| R6 | Published 2D rules + search/final-validator + полная boundary matrix; `railway` mapped to `tram_tracks` | Q&A-P0 economics/egress gaps |
| R7 | Exact economics/rank и streamed GeoJSON реализованы; supplied export HTTP 200, 487 features | Повтор полного Java 11 gate после profile-aware export change |
| R8 | Функционально закрыт по опубликованным правилам: immutable depth parameters, solver/validator, separate XY detour, piecewise cost, technical nodes, XYZ и UI; `railway` uses tram parameters | Disputed general depth rules |
| R9 | Ubuntu 22/Compose 1.29.2, restart recovery, 3 GiB input, 500 MiB output, 50-user API и full 2× topology gates автоматизированы | Organizer-approved maximum profile и production-like host evidence |

## 8. Полный roadmap

Оценки ниже даны в человеко-днях для одного разработчика, не являются обязательством и уточняются после spike на официальном наборе. Часть UI, тестов и документации можно вести параллельно.

### R0 Перебазирование проекта на официальное ТЗ

**Оценка:** 1-2 дня.
**Цель:** прекратить разработку по устаревшим контрактам.

Задачи:

- поместить неизменяемые копии официального ТЗ и приложения в репозиторий либо зафиксировать контролируемое хранилище и SHA-256;
- создать единую compliance matrix;
- пометить прежние M0-M6 как historical/internal;
- завести официальный backlog с P0/P1;
- принять ADR по Java migration;
- определить правило изменения официальных справочников;
- зафиксировать, что pandapipes, MVT, Shapefile и GeoParquet являются дополнительными функциями, а не заменой P0.

Готово, когда:

- каждое требование имеет ID, владельца, статус и проверку;
- команда больше не использует старый статус complete как критерий сдачи;
- принято решение по backend stack.

### R1 Java 11 и Spring Boot foundation

**Оценка:** 4-7 дней.
**Зависимость:** R0.

Задачи:

- создать Java 11 module и Spring Boot 2.6.3 application;
- добавить health/readiness, error contract и Swagger UI;
- подключить PostgreSQL/PostGIS и миграции;
- описать приложение и БД в Compose, совместимом с docker-compose 1.29.2;
- создать асинхронную модель job со статусами queued/running/completed/failed/cancelled;
- связать существующий frontend с новым API через стабильный compatibility layer;
- настроить CI на Java tests, frontend tests и Compose smoke.

Готово, когда:

- чистый checkout собирается и поднимается на Ubuntu Server 22;
- Java version, Spring Boot version и Swagger UI подтверждаются автоматически;
- frontend получает health и создаёт тестовый job;
- ни один обязательный endpoint не зависит от FastAPI.

### R2 Официальный вход и нормализация данных

**Оценка:** 4-6 дней.
**Зависимость:** R1.

Задачи:

- реализовать потоковый приём одного GeoJSON FeatureCollection;
- валидировать семь `object_type` и обязательные поля по типу;
- проверять уникальность ID и ссылки `oks_id`/`upstream_object_id`;
- сохранять raw hash и отчёт импорта;
- преобразовывать геометрию EPSG:4326 -> EPSG:32637;
- строить spatial indexes;
- отклонять невалидную геометрию без скрытого исправления;
- выдавать понятный список ошибок и неподдержанных типов;
- подготовить минимальный и расширенный official-like fixture.

Готово, когда:

- корректный единый GeoJSON импортируется без ручного mapping;
- ошибки контракта локализуются до feature ID и поля;
- длины и буферы считаются в EPSG:32637;
- повтор импорта одинаковых байтов даёт воспроизводимый результат;
- импорт не держит весь файл в оперативной памяти.

### R3 Существующая сеть и кандидаты врезок

**Оценка:** 5-8 дней.
**Зависимость:** R2.

Задачи:

- построить ориентированную к источнику модель существующей сети;
- проверить, что каждая цепочка приводит к `source`;
- диагностировать циклы, обрывы, неизвестные ссылки и неоднозначные пересечения;
- сформировать кандидаты врезки в камерах и внутренних точках линейных участков;
- учитывать расстояние 10 м до камеры и число примыкающих участков;
- корректно делить линейный участок в точке врезки для расчёта реконструкции;
- ранжировать и ограничивать число кандидатов без фиксации координат конкретного набора.

Готово, когда:

- для каждого ОКС автоматически находятся допустимые кандидаты;
- врезка внутрь линии создаёт две геометрические части;
- upstream traversal детерминирован;
- циклы и ссылки в никуда блокируют расчёт с диагностикой.

### R4 Multi-OKS routing и формирование новой сети

**Оценка:** 12-20 дней.
**Зависимости:** R2, R3.

Это самый сложный и рискованный этап.

Задачи:

- адаптировать A*/Dijkstra к официальным ограничениям;
- построить кандидаты маршрутов для всех точек подключения;
- реализовать эвристику совместного подключения: кластеризация, общий ствол, локальные улучшения или Steiner-like approximation;
- сравнивать раздельные и совместные подключения;
- строить до трёх содержательно разных вариантов;
- исключить циклы и несколько путей от врезки до одного ОКС;
- исключить пересечения новых участков вне общего узла;
- размещать разветвления только в новых или существующих камерах;
- обеспечивать максимум один upstream и три downstream участка у камеры;
- упрощать геометрию с полной повторной проверкой;
- сохранять частичный результат при no-route для отдельных ОКС.

Готово, когда:

- один запуск обрабатывает все `oks_future`;
- fixture с близкими ОКС создаёт общий участок, когда он выгоднее;
- fixture с удалёнными ОКС выбирает раздельное подключение;
- дерево не имеет циклов, запрещённых пересечений и необоснованных зигзагов;
- альтернативы различаются точкой врезки, группировкой, маршрутом или способом прохода, а не небольшим смещением линии.

### R5 Расходы, диаметры, предельные длины и реконструкция

**Оценка:** 7-12 дней.
**Зависимость:** R4.

Задачи:

- считать расходы снизу вверх от ОКС к врезкам;
- назначать каждому участку минимальный ДУ по официальной таблице;
- создавать technical node в точке смены ДУ;
- проверять предельную непрерывную длину одного ДУ;
- не сбрасывать длину на камере при неизменном ДУ;
- распространять добавленный расход от каждой врезки к источнику;
- суммировать расходы нескольких подключений на общей существующей части;
- определять требуемый ДУ существующих частей;
- создавать `heat_network_reconstruction` только там, где требуемый ДУ больше существующего;
- определять реконструкцию используемых камер.

Готово, когда:

- сумма downstream расходов равна расходу upstream участка;
- граничные значения каждой строки таблицы ДУ покрыты тестами;
- partial tie-in реконструирует только часть линии к источнику;
- несколько врезок корректно суммируются на общей цепочке;
- предельная длина не может быть обойдена вставкой камеры без смены ДУ.

### R6 Пространственные ограничения и специальные проходы

**Оценка:** 6-10 дней.
**Зависимости:** R2, R4.

Задачи:

- реализовать динамический buffer существующего ОКС: 5, 7 или 9 м в зависимости от ДУ новой сети;
- реализовать запреты для park, social_area, prohibited_site и water;
- реализовать road и tram_tracks с углом не менее 45 градусов, специальным участком и выходом на 3 м за границы;
- реализовать gas_pipeline, power_cable и независимое пересечение heat_network со специальным участком по 2 м с каждой стороны;
- разделять линию на `base` и `special` участки;
- применять только разрешение конкретного правила, не отключая другие ограничения;
- независимо перепроверять готовую геометрию.

Готово, когда:

- каждая строка таблицы 5.1 имеет положительный, граничный и отрицательный тест;
- неверный угол дороги/трамвая блокируется;
- границы special segment воспроизводимы;
- разрешённый special crossing не позволяет пересечь посторонний запрещённый объект.

**Статус:** обязательная 2D-матрица закрыта: точные значения каждой опубликованной строки и
positive/boundary/negative поведение проверяются для четырёх запретов, трёх диапазонов отступа от
ОКС, road/tram и трёх utility crossings. Ровно допустимый отступ считается валидным; проникновение
на 0,01 м блокируется. `railway` в конкурсном наборе является алиасом `tram_tracks`, а вертикальные
глубины относятся к optional R8.

### R7 Стоимость, ранжирование и официальный экспорт

**Оценка:** 4-7 дней.
**Зависимости:** R5, R6.

**Текущий статус:** seven-type 2D adapter, exact-field/type/reference validator и download endpoint
реализованы для полностью рассчитанных вариантов. Один файл содержит все ranked alternatives,
а IDs узлов/реконструкции scoped по варианту. Поставленный организатором baseline-профиль
экспортируется без выдуманных полей реконструкции; расширенный strict-profile по-прежнему требует
полный baseline/direction contract. Экспорт проходит
feature-by-feature preflight и инкрементально пишется `JsonGenerator`; all-seven-type fixture
включает реконструкцию камеры. Для complete/ranked варианта карта запрашивает отфильтрованный
`variant_id` через тот же валидированный adapter; внутренний preview остаётся только для заведомо
неполного supplied dataset. Golden-test воспроизводит нормативные ставки и формулы на размерах
примера 10.8; условные значения самого примера расходятся с таблицей на 19/33 рубля, что отдельно
зафиксировано в alignment audit.

Задачи:

- загрузить официальные ставки нового строительства и реконструкции;
- реализовать стоимость обычных и специальных участков;
- реализовать стоимость камер и врезок;
- реализовать штраф за неподключённые ОКС;
- сформировать component totals и `calculated_cost`;
- рассчитать `new_network_length`, `reconstruction_length`, `length` и `score`;
- отсортировать до трёх вариантов;
- реализовать семь официальных выходных типов;
- убрать поля, не относящиеся к конкретному типу;
- выгружать GeoJSON потоково;
- добавить JSON Schema или эквивалентный contract test для результата.

Готово, когда:

- пример из технического приложения воспроизводится арифметически;
- итог равен сумме компонентов до согласованного округления;
- rank полностью определяется официальным score;
- выход проходит строгую валидацию полей, типов, ссылок и геометрии;
- frontend отображает именно официальный результат, а не внутреннюю centerline/corridor модель.

### R8 Дополнительная задача по глубине

**Оценка:** 8-15 дней.
**Зависимости:** R4-R7.
**Статус:** функционально закрыт по опубликованным правилам глубины.

Задачи:

- запускать отдельную перетрассировку, а не проверять готовый 2D-маршрут;
- принять условную поверхность земли Z = 0;
- искать глубину с шагом 0,5 м от минимальной до заданной максимальной;
- учитывать обычную глубину 3,0 м и минимум 0,7 м;
- учитывать габариты пары труб по ДУ;
- выбирать проход выше или ниже коммуникации по минимальной стоимости;
- выдерживать зазоры 0,2/0,5 м по типу пересечения;
- формировать участок постоянной глубины длиной 4 м вокруг линейной коммуникации;
- ограничить уклон значением 0,10 м/м;
- применять коэффициент глубины и средний коэффициент на наклонном участке;
- создавать technical nodes при изменении глубины и пересечении отметки 3,0 м;
- выдавать Z-координаты, глубины, пересечения, расстояния, стоимость и конфликты.

Готово, когда:

- более дешёвый допустимый вариант сверху/снизу выбирается автоматически;
- профиль содержит спуск, постоянную глубину и подъём;
- уклон и зазоры подтверждаются независимым validator;
- невозможный диапазон глубин даёт partial result и список ручной проработки;
- GeoJSON содержит корректные Z-координаты.

Текущий evidence:

- `OfficialDepthOptimizer` выполняет детерминированный поиск по глубинам 0,7 + n×0,5 м и сохраняет
  обычную отметку 3,0 м как допустимый уровень;
- пересечения рядом с камерой могут начинаться на выбранной допустимой глубине, а соседние
  пересечения одной глубины объединяются в непрерывный профиль без искусственного возврата на
  3,0 м; на supplied dataset все участки всех трёх вариантов проходят depth validator;
- `OfficialDepthCrossingExtractor` получает пикет пересечения из фактической JTS-геометрии, а не
  из текстового тега секции;
- `OfficialDepthProfileValidator` независимо проверяет диапазон, уклон, плато, проход и зазор;
- границы глубины валидируются и сохраняются в immutable run до постановки job в очередь;
- невозможный вертикальный проход запускает отдельный XY detour с повторным sizing и validator;
- профиль участвует в расчёте стоимости, сериализуется в run result и отображается в режиме
  «Профиль»;
- строгий GeoJSON дробится по переломам профиля, содержит technical nodes, `depth_start`,
  `depth_end` и точный Z оси пары труб;
- подробности и проверяемые границы зафиксированы в `R8_VERTICAL_EVIDENCE.md`.

### R9 Производительность, приёмка и комплект сдачи

**Оценка:** 5-10 дней после доступности представительного набора.
**Зависимости:** R1-R8.

Задачи:

- проверить вход до 3 ГБ и выход до 500 МБ;
- контролировать пиковую память в пределах машины с 16 ГБ RAM;
- провести профиль нагрузки до 50 пользователей;
- настроить временное дисковое хранилище и очистку;
- провести clean deployment на Ubuntu Server 22;
- проверить docker-compose 1.29.2;
- выполнить end-to-end запуск на отдельном наборе той же структуры без изменения алгоритма;
- подготовить конкурсную выгрузку;
- подготовить демонстрационный сценарий по десяти пунктам ТЗ;
- подготовить описание алгоритма, no-route, выхода, глубины и границ применимости;
- зафиксировать реальные команды, версии, результаты и известные ограничения.

Готово, когда:

- сервис из чистого комплекта поднимается одной документированной последовательностью команд;
- один запуск обрабатывает все перспективные ОКС;
- результат выгружается без ручного редактирования маршрутов;
- демонстрация показывает врезки, камеры, расходы, ДУ, длины, реконструкцию, стоимость, ограничения и ранжирование;
- новый official-like dataset проходит без изменения кода и ручной подготовки маршрутов.

## 9. Порядок выполнения и зависимости

Критический путь:

`R0 -> R1 -> R2 -> R3 -> R4 -> R5 -> R7 -> R9`

Параллельная работа после R2:

- ограничения R6 можно разрабатывать параллельно с частью R3/R4;
- frontend adapter и визуализацию официальных объектов можно начинать после стабилизации выходного draft contract;
- глубину R8 начинать только после устойчивой обязательной 2D-модели;
- документацию и demo script обновлять после каждого gate, а не писать целиком в конце.

Грубая последовательная оценка: 48-82 человеко-дня без R8 и 56-97 человеко-дней с дополнительной задачей по глубине. Она будет пересмотрена после R1-R3 и первого запуска на official-like fixture.

## 10. Разделение ответственности

### ПМ

- держит официальное ТЗ единственным source of truth;
- получает подтверждение по обязательности Java/Spring Boot;
- не принимает milestone по скриншоту UI или количеству endpoint;
- следит, чтобы P0 не вытеснялся бонусными функциями;
- ведёт реестр требований, рисков и решений;
- обеспечивает раннее получение конкурсного набора или максимально близкого fixture;
- планирует отдельное время на производительность, clean deployment и комплект сдачи;
- не использует старые M0-M6 как процент готовности к конкурсу.

### Разработчик

- реализует официальный контракт до расширений;
- не фиксирует координаты, ID и конфигурацию конкурсного набора в коде;
- разделяет route construction и независимую validation;
- покрывает каждую таблицу и формулу golden tests;
- хранит геометрию WGS 84 на границе API и считает метры в EPSG:32637;
- не загружает 3-гигабайтный файл целиком в память;
- возвращает partial result вместо потери всего запуска при no-route одного ОКС;
- фиксирует версию алгоритма и справочников в каждом результате;
- измеряет производительность до заявления о готовности.

## 11. Definition of Done официального P0

P0 считается завершённым только если одновременно выполнено следующее:

- используется согласованный обязательный стек;
- принимается единый официальный GeoJSON;
- все перспективные ОКС обрабатываются одним запуском;
- врезки и совместное/раздельное подключение выбираются автоматически;
- новая сеть является корректным деревом или набором деревьев;
- расходы, ДУ и предельные длины рассчитаны по официальным таблицам;
- supplied profile получает complete cost/rank/export без реконструкции; strict-profile сохраняет
  реконструкцию при наличии всех baseline/direction полей;
- каждая demand point выходит из собственного OKS по нормали;
- connect-vs-penalty, bend ×1.5, overlap max `K_special` и per-ray tie-in учтены;
- все пространственные ограничения применены точно;
- стоимость и rank рассчитаны по официальным формулам;
- формируются до трёх содержательно разных вариантов;
- no-route сохраняет частичный результат и список ОКС;
- официальный GeoJSON проходит строгую contract validation;
- нет ручной правки рассчитанных маршрутов перед демонстрацией;
- подтверждены ограничения 3 ГБ, 500 МБ, 16 ГБ RAM и 50 пользователей;
- clean deployment проходит на Ubuntu Server 22;
- подготовлены документация, конкурсная выгрузка и демонстрационный сценарий.

## 12. Что проверено на момент аудита

15–16 сентября 2026 года проверено:

- 111 локальных backend tests; pinned Java 11 CI, integration job и отдельный Ubuntu 22 full
  2× topology run зелёные;
- web lint/typecheck/production build/audit, 16 Vitest tests и 4 теста локального replay API;
- официальный fixture: 144 объекта, 17 demand points, 204 tie-in candidates и три валидных
  obstacle-aware варианта;
- strict seven-type output, independent validator, incremental download и official-output map;
- local browser smoke; VPS намеренно не обновляется без отдельной команды пользователя.

Старые Python/M-stage evidence остаются только историей и не подтверждают официальный P0.

## 13. Немедленный следующий шаг

Не продолжать MVT, косметический UI или дополнительные форматы. Локальный цикл базового файла
import → run → export пройден: 137.1 секунды, все варианты валидны, экспорт HTTP 200. Следующий шаг —
один полный Java 11 gate на итоговом состоянии и проверка эквивалентности/памяти spatial-window
calculation. Кооперативная отмена CPU-bound planner уже подтверждена отдельным локальным сценарием.

Непрерывная длина, трактовка 0,7 м и необязательное присутствие разрешённых выходных типов приняты
как продуктовые решения и больше не блокируют обязательный 2D-контур. Открыты только отдельные
параметры дополнительного глубинного расчёта.

Условия возврата обязательной реконструкции зафиксированы отдельно в
`docs/implementation/RECONSTRUCTION_DEFERRED.md`.

### Local quality follow-up, 21 September

`global-tree-8` addresses the visible excess of sequential graft chambers and the circular own-OKS
approach. It also restores partial/alternative drafts by attaching failed separate rays to the
existing forest. The amended-contract regression expectations are now aligned and the final Java
11 gate passed: 143 tests, 0 failures, 3 opt-in scale tests skipped; the official dataset test took
152.707 s. Web Vitest also passed 18/18 with the established Russian labels restored. This remains
an implementation checkpoint rather than a closed R-stage: route quality and the 152.7 s official
runtime still require improvement, and lint/typecheck/live Compose were not part of this gate.
Текущий checkpoint не публиковался на VPS; развёртывание выполняется только отдельной командой.

`global-tree-9` is the current local quality checkpoint. It keeps the nearest own-OKS exit first
and retries a nearby target-facing side only when that route is physically blocked, ranks
equal-coverage drafts by the complete official score, validates a selected graft after bottom-up
diameter sizing, and introduces a non-tariff constructability preference for straight and
right-angle geometry. Corrected-dataset run `6e0b759c-24ed-42a0-a2ac-04d0021b6c16` produced a valid
17/17 tree at 2,061.412 m, 305,521,285.79 RUB and score 14.738832002 with zero validation issues.
The 239.861 s runtime still needs optimization, and the full automated gate remains deferred; no
R-stage is closed by this checkpoint alone.

`global-tree-10` is the current local geometry checkpoint. Straight, 45-degree and 90-degree bends
are preferred without inventing a tariff, and only legal elbows with at most five percent local
length growth are snapped. Existing heat chambers are reused at a selected existing-network tie-in
only within the inclusive 10 m boundary and while the resulting incident-section count remains at
most four; a farther tie-in creates a new chamber on the existing network. Corrected-dataset run
`b969b8ed-6892-47a1-a3c6-639ef09df780` produced a valid 17/17 tree at 2,072.949 m,
283,934,192.11 RUB and score 14.169004379, with 12 chambers, 26 edges and zero validation issues.
All 42 internal bends match the straight/45/90 set, but detour ratios 2.30 and 1.77 remain and the
373.076 s runtime is a regression. Therefore the next algorithmic gate is topology-level detour
replacement with a strict search budget; this checkpoint does not close route-quality or runtime
work. Full automated tests, lint and typecheck remain deferred by explicit request.

`global-tree-12` supersedes that local checkpoint. It can select a new chamber farther along an
existing-network segment when the nearest projection is captured by the inclusive 10 m existing-
chamber reuse rule, prefers a perpendicular final chamber approach relative to the local network
tangent, and repairs an excessive own-OKS egress only when the target-facing side is legal and
shorter. Corrected-dataset run `0c84f5b4-1e40-4493-bc66-7e43e76d957c` is valid and connects 17/17
at 1,940.620 m, 267,382,225.35 RUB and score 13.308562310 with 11 chambers, 25 edges and zero
validation issues. The remaining worst detour ratio is 1.89, and 486.077 s is not acceptable as a
runtime target; caching and strict-budget topology repair remain open. The 90-degree approach is a
constructability preference, not an invented official tariff or an unsupported statutory claim.

An optional local road-enrichment experiment now spatially windows OSM road polygons around the
actual network and demand objects. The first unchanged-router run proved that validation alone is
insufficient: it returned no complete variant. Perpendicular road-crossing portals then produced
a valid 17/17 tree at 2,071.836 m, 305,170,558.59 RUB and score 14.760283641. This is longer,
costlier and slower than the no-road checkpoint, so it remains an opt-in experiment and is not
part of the official-input baseline unless road objects are actually supplied in the dataset.

`global-tree-13` removes road enrichment from the default path and applies the next topology-
geometry gate instead: a new branch chamber may receive a four-metre perpendicular final approach
to its supporting trunk when that route is legal and no more than five percent longer locally.
The corrected-dataset run completed valid at 17/17 OKS, but exact comparison found 0 changed
geometries out of 25 edges and identical length, cost and score. The gate is therefore retained as
a safe constructability preference but is not counted as a quality improvement for this dataset.
The next gate must move junctions or reconnect complete subtrees rather than append a local suffix.
This does not add a tariff or make 90 degrees a hard official constraint.

A subsequent bounded single-terminal junction-relocation experiment was rejected. Moving graft
chambers by four/eight metres along existing trunks produced valid 17/17 trees but worsened the
best accepted checkpoint to 1,951.141-1,952.772 m, 272.44-273.80 million RUB and score
13.481677852-13.524805740; the guarded run also took about 660 seconds. The default remains
`global-tree-13`. A future topology pass must rebuild and compare a complete branch group against
the already optimized control tree rather than optimize individual terminal attachments against a
raw draft.

A later fourth-ray constructability experiment confirms the same boundary. Rejecting an awkward
fourth ray reduced the corrected-dataset route to 1,864.356 m and kept 17/17 valid, but increased
cost to 288,191,073.55 RUB, generated chambers to 13 and score to 13.662418059. The local
`global-tree-19` run is available only for visual comparison; it does not supersede the accepted
`global-tree-13` checkpoint. The next topology gate must retain the economical control candidate
and compare any constructible group replacement only after complete sizing and economics.

Точная постановка и разделение задач на завтра находятся в `TOMORROW_HANDOFF.md`.
Повторная сверка технологий, внутреннего ТЗ, официальных документов и реального файла находится в
`OFFICIAL_ALIGNMENT_AUDIT.md`.

Передача разработки Артёму зафиксирована в `TOMORROW_HANDOFF.md`: там находятся точный локальный
запуск без Docker, подтверждённые метрики официального расчёта и запреты на ложные P0-claims.
# Amendment checkpoint 2026-09-21 (local, verification pending)

The updated organizer DOCX is now the active contract over older roadmap prose: five input object
types, forbidden railway, no existing-asset reconstruction, arbitrary 0–90 degree turns without a
bend tariff, four output object types, and construction-only score/cost fields. The local
`global-tree-7` delta implements these contract changes and widens whole-tree optimization, but the
gate remains **verification pending** because automated checks were explicitly deferred.

The next whole-tree experiment (`global-tree-20`–`global-tree-23`) contracts two short-linked
degree-three generated chambers into one degree-four intersection candidate and rebuilds their
four outer branches together. It also fixes route avoidance at an existing topology node: old
edges incident to that exact start node are not treated as obstacles to the replacement trunk,
while final geometry and topology validation still check the completed draft. Corrected-dataset
run `f7b5dc2b-b582-44dc-9e3a-377535585988` remained valid at 17/17 but unchanged at 1,864.356 m,
288,191,073.55 RUB, score 13.662418059 and 13 generated chambers because no merged draft improved
the complete score. This proves the requested vertical common trunk must be introduced as an
earlier competing topology seed, before terminal grafting, rather than recovered by late local
contraction. The accepted economical checkpoint remains `global-tree-13`.

`global-tree-24` tested that earlier stage by fully completing four alternative pair-based trunk
seeds before choosing a tree. The corrected dataset still selected the identical 1,864.356 m,
288,191,073.55 RUB, score 13.662418059 geometry, while runtime regressed to 1,315.076 seconds.
The next candidate must therefore be a genuine multi-terminal intersection-centred trunk seed;
retaining more orderings of the existing pair-and-graft construction is not sufficient.

`global-tree-26` restores three distinct valid objectives without publishing invalid drafts.
Branches intentionally ending in the same replacement chamber may share that endpoint during
routing, while final validation still rejects crossings or overlaps elsewhere. The corrected
dataset now yields balance 1,791.882 m / 276.074 million RUB, shortest 1,789.810 m / 273.209
million RUB, and cheapest 1,789.987 m / 272.839 million RUB; all connect 17/17 with zero errors.
The shortest and cheapest trees each contain two merged chamber nodes. Runtime is still
1,143.256 seconds, so the next gate is reuse of one evaluated merge-candidate set across both
objectives, followed by profiling of the base visibility searches and repeated sizing.

`global-tree-28` adds a post-assembly relocation gate for single-ray new tie-in chambers. The
completed adjacent trunk is projected onto the same existing-network feature; a candidate is
retained only outside the mandatory 10 m existing-chamber reuse zone, with perpendicular entry
to the existing network, a 45/90/135/180-degree final chamber ray, full geometry validity and
strict final-economic improvement. On the corrected dataset, segment `126` moves its tie-in and
reduces the root edge from 48.886 m to 18.090 m. All three valid 17/17 variants improve by
30.796 m and 4,620,077.51 RUB; the cheapest result is 1,759.191 m, 268,218,637.65 RUB and score
12.787694854. Runtime remains high at about 831 seconds, so visibility-search and duplicate
merge-candidate evaluation remain the next performance gates.

`global-tree-29` makes the published objectives materially different. One evaluated set of valid
merged-chamber topologies supplies both the length and construction-cost selections, and the
cheapest-only pass may reconnect a new segment tie-in to a bounded nearby existing chamber when
the final sized network is strictly cheaper, even if it is longer. Corrected-dataset run
`0e3853ec-8af9-41e5-8a5a-e9adfe323e09` produced three valid 17/17 alternatives: balance
1,761.086 m / 271.454 million RUB, shortest 1,759.014 m / 268.589 million RUB, and cheapest
1,765.990 m / 264.239 million RUB. The cheapest topology reuses chamber `106`, removes one new
tie-in chamber and is 4.351 million RUB cheaper than the shortest while being 6.976 m longer.
Runtime improved to 710.374 seconds but remains above the approximate ten-minute iteration target;
the shared-tree visibility phase, rather than objective selection or chamber reuse, is now the
dominant performance gate.

`global-tree-33` implements the bounded three-objective selection described in
`ROUTING_VARIANT_PORTFOLIO.md` and applies the expert 90–135-degree / 2 m bend constraints to the
finished engineering and shortest geometries. The cheapest route remains governed by the official
TZ only. The engineering cost/length corridor is 5% with a single relaxation to 10%. This is an
active local routing iteration, not a completed acceptance gate: automated verification remains
deferred, and the corrected dataset cannot validate the requested 1.5 m roadway offset because it
contains no roadway geometry.

The corrected-dataset verification run `d6a8b187-a14f-4638-a39f-5212a6473166` confirms the
portfolio produces materially different drafts, but only the cheapest representative currently
survives final publication. The engineering and shortest drafts each retain eight expert-angle
violations after legal local repair and are therefore filtered rather than mislabeled as compliant.
The next implementation gate is rebuilding those affected subtrees inside the 5%/10% corridor.

`global-tree-34` restores all three objective-specific variants whenever they pass the official
route validator. The two additional expert geometry rules no longer silently remove an otherwise
official-valid engineering or shortest result: their remaining violations are returned in the
separate `engineering_issues` diagnostic list and shown as non-blocking UI warnings. This keeps
the alternatives available for visual and economic comparison without presenting the expert
rules as satisfied. The cheapest variant continues to follow only the official-TZ geometry rules.
The results drawer now reports the selected variant's actual length, connected OKS count and cost,
so a missing sibling strategy can no longer produce misleading `0 m / 0 OKS` metrics.
Corrected-dataset run `dcfcfe45-3c2a-4ad1-a8a7-b58b5dffba4d` published all three valid 17/17
alternatives: engineering 1,704.185 m / 260.010 million RUB, shortest-with-engineering-processing
1,698.415 m / 264.144 million RUB, and cheapest official-TZ 1,689.315 m / 258.273 million RUB.
Engineering and shortest each expose one aggregate warning covering eight out-of-range bends;
both have zero sub-2 m bend-pair warnings. Runtime was 781.996 seconds, so bounded subtree
rebuilding and visibility-search performance remain open rather than accepted gates.

`global-tree-38` always applies the bounded engineering repair to the selected engineering and
shortest drafts and prevents directional OKS egress from crossing the full building to reach a
distant opposite wall. The corrected-dataset angle count falls from eight to five while all three
official-valid alternatives remain connected 17/17. A wider rotated-dogleg search was explicitly
rejected: it still left four invalid angles and increased the focused official-dataset runtime to
1,348.5 seconds. The remaining angle findings are therefore still published as diagnostics and
are not an accepted completion claim. The next gate is a bounded topology/egress rebuild of the
four affected branches, followed by visibility-search caching; neither zero expert warnings nor
the ten-minute iteration target is currently verified.

`global-tree-39` restores opt-in road-crossing navigation for road-enriched inputs. A shallow
direct crossing that fails the official angle rule receives two bounded portals outside the road
polygon, perpendicular to its dominant axis, so the visibility graph can construct and validate a
legal alternative. The ordinary corrected dataset is unchanged; OSM roads remain experimental
input rather than an inferred part of the official contract. Live run
`d9cd8766-1ce8-4ad7-a973-931fcb1cbca8` is valid at 17/17, but the cheapest road-enriched route is
2,008.104 m / 307.270 million RUB and the run takes 1,441 seconds. The polygon visibility approach
is therefore retained only for visual diagnosis; it does not close the geometry or performance
gate. The next road-aware candidate must use a compact street-axis corridor graph instead of
injecting every road polygon into repeated visibility searches.

`global-tree-40` makes expert geometry the primary engineering-portfolio objective without using
roads. Candidate ordering first minimizes violations of the 90–135-degree bend range and two-metre
bend spacing, then prefers canonical 90/135-degree internal bends and regular
45/90/135/180-degree chamber rays; cost and length remain inside the existing 5%/10% corridor.
The evaluator now sees degree-two turns and multi-edge branch geometry across route-edge
boundaries. Corrected-dataset run `d944ea70-aeb3-4503-bf07-36f663c61298` remains official-valid
at 17/17 for all three strategies, but repeats the prior metrics and leaves five angle warnings.
This is a diagnostic improvement, not gate completion: a geometry-first rank cannot select a
candidate the current bounded search never generated. Runtime is also 1,361 seconds. The next
implementation gate must rebuild the four affected branch/egress groups under the same economic
corridor and stop non-improving global repairs early.
