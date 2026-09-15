# HeatRoute — предварительная трассировка тепловых сетей

**Дата:** 08.09.2026. **Фокус:** backend, геоданные, расширяемость после выдачи ресурсов, полноценный web на готовой UI-библиотеке.

Реализация начата 07.09.2026. На синтетическом наборе локально закрыты M0–M5: инфраструктура,
server-side sessions/access control, версионируемый Raw → Staging → Canonical импорт,
ограничения, routing/cost/jobs vertical slice, полноценный web workspace, exports, audit и
эксплуатационный rehearsal. Статус и проверенные команды ведутся в
[progress](docs/implementation/progress.md); границы модели перечислены ниже явно.

## Разработка на Windows

Новые runtime, cache и browser-артефакты должны оставаться на диске `E:`. Скрипт ниже выставляет безопасные пути и является основным Windows entrypoint:

```powershell
pwsh -File scripts/dev.ps1 bootstrap
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 lint
pwsh -File scripts/dev.ps1 typecheck
pwsh -File scripts/dev.ps1 up
pwsh -File scripts/dev.ps1 seed-demo
pwsh -File scripts/dev.ps1 worker-smoke
pwsh -File scripts/dev.ps1 test-integration
pwsh -File scripts/dev.ps1 test-e2e
pwsh -File scripts/dev.ps1 benchmark-demo
pwsh -File scripts/dev.ps1 security-audit
```

После `up` API доступен на `http://localhost:8000`, OpenAPI — на `/docs`, web — на
`http://localhost:5173`. Web собирается Vite и обслуживается непривилегированным Nginx;
браузер обращается к API через same-origin `/api/v1`. PostGIS опубликован на `localhost:55432`,
Redis — на `localhost:56379`, чтобы не конфликтовать с локальными установками на стандартных
портах. `health/live` проверяет только процесс, `health/ready` реально обращается к PostGIS и
Redis; агрегированные метрики доступны администратору на `/api/v1/metrics`, audit trail — на
`/api/v1/audit-events`. `.env.example` содержит только development defaults; production с demo
mode или этим session secret не запускается. В локальном Compose отключена durability PostgreSQL
ради приемлемой скорости Docker Desktop — этот профиль нельзя использовать для production-БД.

В web доступен синтетический вертикальный срез: кнопка «Рассчитать» создаёт сохранённый run,
worker строит маршрут, сервер независимо проверяет коридор и карта показывает полученный GeoJSON.
Backend также поддерживает immutable scenario/cost snapshots, waypoints, objectives,
альтернативы, quantities/cost, cache, retry/cancel и SSE/polling. Инженерное расширение умеет
отдельно запускать гидравлику в worker и проверять заданный вертикальный профиль. Без явных
физических входов эти проверки не выполняются, а маршрут остаётся предварительным результатом.

Импорт принимает GeoJSON, GeoPackage, CSV, GeoParquet и ZIP Shapefile, сохраняет raw
hash/provenance, требует явное mapping и
CRS-подтверждение, формирует quarantine-aware отчёт и атомарно публикует immutable canonical
snapshot. API также отдаёт слои поставки, feature provenance, coverage findings и diff версий.
Полная матрица доказательств: [M1 evidence](docs/implementation/m1-evidence.md).

В development Compose включён явный demo bypass для удобства UI. При реальном логине backend
использует серверную сессию в HttpOnly cookie и `X-CSRF-Token`; viewer имеет только чтение.
Production запрещает запуск с demo mode и development session secret.

GNU Make на Windows не требуется. `Makefile` предоставляет те же цели для Linux/CI. Интеграционные тесты запускаются только при живых PostGIS, Redis и worker.

## Быстрый старт

```powershell
pwsh -File scripts/dev.ps1 bootstrap
pwsh -File scripts/dev.ps1 up
pwsh -File scripts/dev.ps1 seed-demo
```

Последняя команда печатает готовый `workspace_url`. Откройте его относительно
`http://localhost:5173`. Повторный `seed-demo` безопасен: используется idempotency key и
возвращается тот же результат. Полная локальная проверка:

```powershell
pwsh -File scripts/dev.ps1 lint
pwsh -File scripts/dev.ps1 typecheck
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 test-integration
pwsh -File scripts/dev.ps1 test-e2e
pwsh -File scripts/dev.ps1 worker-smoke
pwsh -File scripts/dev.ps1 benchmark-demo
pwsh -File scripts/dev.ps1 security-audit
pwsh -File scripts/dev.ps1 export-openapi
```

GNU Make на Linux/CI предоставляет одноимённые цели. Для приёмочного запуска без сети заранее
собранных локальных образов используется `compose.offline.yaml`; его сеть намеренно имеет
`internal: true`, поэтому smoke выполняется из контейнера:

```bash
docker compose -f compose.yaml -f compose.offline.yaml up -d --no-build --pull never
docker compose -f compose.yaml -f compose.offline.yaml exec -T api python -m heatroute seed-demo --wait 30
```

`AGENTS.md` намеренно короткий; подробные требования лежат отдельно. Такой способ постоянных инструкций поддерживается Codex [S19 в источниках](docs/SOURCES.md).

## Документы

| Файл | Содержание |
|---|---|
| [TECH_SPEC.md](docs/TECH_SPEC.md) | цели, архитектура, backend, импорт, версии, CRS, topology, rules, routing, стоимость, jobs, безопасность, эксплуатация |
| [DATA_CONTRACTS.md](docs/DATA_CONTRACTS.md) | типы объектов, поля, единицы, statuses, версии, endpoints, интерфейсы расширения |
| [UI_SPEC.md](docs/UI_SPEC.md) | готовый GIS workspace на shadcn/ui, мастер импорта, карта, сравнение, состояния и темы |
| [ACCEPTANCE.md](docs/ACCEPTANCE.md) | детальные положительные/отрицательные тесты и доказательства работоспособности |
| [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | M0–M5 для законченного P0, расширения P1/P2 |
| [ORGANIZER_DATA_PLAYBOOK.md](docs/ORGANIZER_DATA_PLAYBOOK.md) | запрос данных, инвентаризация, mapping, проверка готовности, replay и инженерная адаптация |
| [SOURCES.md](docs/SOURCES.md) | первичная документация библиотек и границы предположений |

## Работает

- GeoJSON, GeoPackage, CSV, GeoParquet и ZIP Shapefile через Raw → Staging → Canonical с
  hash/provenance, явным mapping,
  CRS-подтверждением, quarantine, coverage и immutable versions.
- Версионируемые сценарии, правила и стоимость; A*/Dijkstra, forbidden zones, waypoints,
  alternatives, quantities/cost, retries, cancellation, SSE/polling и immutable result passport.
- Полный web-сценарий без ручной подстановки UUID: проекты, импорт, качество, карта, инспектор,
  история и GeoJSON/JSON/CSV/HTML exports.
- Server-side sessions, roles/CSRF/workspace isolation, безопасный upload pipeline, audit events,
  structured request logs, readiness и базовые агрегированные метрики.
- Воспроизводимый synthetic demo и restart/offline rehearsal на чистых volumes.
- PostGIS MVT для опубликованных крупных слоёв с workspace auth/versioned ETag и прямой MapLibre
  отрисовкой; validated-shortcut postprocessing с сохранением обязательных точек.
- Гидравлика воды через pandapipes с давлениями, скоростями, потерями, mass balance и thresholds;
  вертикальные уклоны/зазоры с явной системой высот; каталог реальных способов прокладки.

## Требует данных

- Реальные слои теплосетей, зданий, дорог, ограничений и coverage с подтверждённым CRS.
- Согласованные организатором mapping profiles, нормы/rule profiles и региональные каталоги цен.
- Отметки, глубины, параметры труб и гидравлические входы для инженерных проверок.

## Не реализовано

- Автоматическое построение инженерной модели из реальной поставки, тепловой/энергетический
  расчёт, подбор насосов/диаметров и нормативное заключение. Они требуют согласованных исходных
  данных и методики; `energy_balance` пока явно `not_performed`.
- Production SSO/внешний IdP, автоматические backup/restore и retention, Prometheus/OpenTelemetry
  интеграция и нагрузочные профили для реальных объёмов — это P1 и обязательная подготовка перед
  хранением значимых production-данных.

## Что заложено принципиально

Расчёты доступны без браузера. Исходные файлы не проникают в доменную модель. Точки подключения, ширина коридора, ошибки данных, неизвестные глубины/мощности и реальные способы пересечения учитываются явно. Новая версия исходных сведений не перезаписывает старые результаты.

Основной алгоритм — проверяемый графовый поиск, а не энтропия пентамино. Библиотека локальных примитивов сохранена как полезное расширение. Гидравлика и вертикальные проверки подключаются отдельно только при наличии модели и входов.

## Примеры

[examples/README.md](examples/README.md) объясняет canonical synthetic dataset, mappings, demo rules/prices, scenario и HTTP request. Готовые трассы не подложены: их должен рассчитать реализованный backend.

```bash
python scripts/validate_spec_examples.py
```

Скрипт использует только стандартную библиотеку Python 3.10+. [Отчёт проверки](checks/validation_report.txt) относится только к этому пакету примеров, не к будущему приложению.

## Статусы объёма

P0 — законченная работающая базовая система, а не мокап. P1 — расширение и эксплуатационные улучшения. P2 — зависимые от реальных данных инженерные модели и интеграции. Не выдавать P2 за реализованное на основании интерфейсного placeholder.

Пользовательские скриншоты и содержащиеся на них персональные контакты в архив не включены. Демо-геометрия, нормы, разрешения, мощности и цены полностью вымышлены и не предназначены для реального проектирования.
