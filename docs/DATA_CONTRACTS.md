# Контракты данных и API

Версия 1.0. Дополнение к `TECH_SPEC.md`. Поля в snake_case, UUID — строки, время — ISO 8601 UTC. Приведённые JSON в `examples/` — валидируемые иллюстрации контрактов, не заменяющие финальную OpenAPI, которую нужно получить из реализованных DTO.

## 1. Общие правила

Для всех сущностей: `id`, `workspace_id` при хранении, `created_at`, `created_by`, version/revision, где применимо. Проверять принадлежность вложенных ссылок одному workspace/project. UUID не является механизмом авторизации.

Входящие request DTO запрещают неизвестные поля, кроме специально выделенного `extensions`/`raw_properties`. Domain-critical поля типизированы и валидируются. Нельзя складывать все данные в один бесконтрольный JSONB.

В БД общая таблица `features` содержит geometry, kind, version и provenance; специфичные поля узлов, рёбер, кандидатов и покрытий находятся в типизированных child tables или проверяемых структурах с индексируемыми ключевыми полями. Все derived данные имеют ссылку на версии источников.

Числа — конечные значения. Запретить NaN/Infinity, отрицательную длину/нагрузку/диаметр там, где они не имеют смысла, пустую геометрию и чрезмерную вложенность. Точность не повышать форматированием: дополнительные десятичные знаки не означают точные исходные сведения.

Измерения, требующие provenance, представляются так:

```json
{
  "value": 0.15,
  "unit": "m",
  "evidence_status": "provided",
  "source_ref": "raw-artifact-id:layer-name:record-id:field-name",
  "observed_at": "2026-09-07T00:00:00Z",
  "note": null
}
```

Для `unknown` value=null. `conflicting` требует списка conflict references, а не произвольно выбранного первого значения. Derived/assumed value сопровождается формулой/assumption reference. Эксплуатационные значения demo помечаются `source_type=synthetic` вне зависимости от evidence_status.

## 2. Project

| Поле | Тип/правило |
|---|---|
| id | UUID |
| name | 1–120 символов |
| description | nullable text |
| working_crs | authority/code либо WKT/PROJJSON; подтверждённая метрическая CRS |
| crs_confirmation | кто/когда/как подтвердил, transformation metadata |
| default_planning_date | дата |
| active_dataset_version_ids | список опубликованных версий |
| default_rule_profile_version_id | immutable reference |
| default_cost_catalog_version_id | nullable immutable reference |
| bbox_wgs84 | bbox в порядке west,south,east,north |
| source_mode | synthetic, mixed, provided |
| current_revision | integer для optimistic concurrency |

Изменение active references не модифицирует ранее созданные scenario revisions и runs. Обновление через If-Match/current_revision, конфликт — 409/412 по единому выбранному контракту.

## 3. Dataset и DatasetVersion

Dataset описывает источник и назначение, DatasetVersion — конкретную поставку и её обработку.

Обязательные поля версии: id, dataset_id, status, source_type, source_name, source_observed_at, raw_artifact_ids, raw_hashes, adapter_name/version, mapping_profile_id/version, source_crs, working_crs, transform_definition/hash, import_report_id, published_at, coverage_refs, license_note, contains_sensitive_infrastructure.

Статусы:

`uploaded → inspected → mapping_required | ready_to_validate → validating → needs_review | ready_to_publish → publishing → published`.

Параллельно возможны `failed`, `rejected`, `cancelled`. Нельзя запустить routing на unpublished version. Повторный publish уже опубликованной версии идемпотентен.

ImportReport:
- counts total/read/accepted/quarantined/rejected; все числа согласованы;
- per-layer geometry types, extent, fields, missing distribution;
- errors/warnings с source row и field refs;
- applied repairs и before/after summary;
- CRS diagnosis и unresolved transformations;
- topology diagnostics;
- coverage limitations;
- publish blockers.

Публикация с quarantined features требует подтверждения и явной coverage/limitations policy. Удалённые из-за ошибки здания не превращаются в «свободный участок».

## 4. CanonicalFeature

В API используется GeoJSON Feature в EPSG:4326 [S09]. Общие properties:

| Поле | Значение |
|---|---|
| kind | building, road, utility_line, network_node, network_edge, connection_candidate, forbidden_zone, coverage_area, crossing_portal, entry_gate |
| logical_id | стабильный UUID внутри namespace источника |
| dataset_version_id | версия поставки |
| source_id | исходный ID, уникальный в namespace kind/layer/source |
| source_type | synthetic, organizer, open_data, user, derived |
| source_layer | исходный слой |
| lifecycle_status | existing, planned, decommissioned, unknown |
| valid_from / valid_to | nullable dates, валидный интервал |
| observed_at | nullable timestamp |
| quality_flags | список machine-readable codes |
| raw_properties | ограниченный объект исходных полей, не исполняемый код |
| attributes | типизированный объект по kind |

`Feature.id` — ID конкретной версии объекта. `logical_id` сохраняется при новой поставке, если источник сохраняет идентичность. Source namespace обязателен: одинаковый source_id в двух независимых слоях не должен случайно объединить объекты.

Ссылки между features относятся к той же согласованной совокупности версий. NetworkEdge не может сослаться на удалённый/невидимый узел соседнего проекта. Отсутствующий endpoint создаёт ошибку/карантин, а не ghost node без provenance.

### 4.1. Building

Geometry: Polygon/MultiPolygon. Attributes: external_name/address nullable, building_role (`target`, `existing`, `planned`), height_m nullable, height_source nullable, entry_points array nullable, required_load_kw nullable, availability_date nullable.

Значение height_m только для визуализации, если исходная модель не включает его в проверку. Default extrusion height в UI помечается synthetic/assumed и не записывается как измеренная высота здания.

### 4.2. Road

Geometry: Polygon/MultiPolygon для площадного препятствия. LineString допускается только с width model, который явно преобразует линию в polygon, иначе finding `ROAD_WIDTH_UNKNOWN`.

Attributes: road_class nullable, surface_type nullable, width_m nullable, crossing_policy (`forbidden`, `portal_only`, `profile_defined`, `unknown`), construction_availability nullable. Наличие public roadway geometry не является разрешением на работы.

### 4.3. UtilityLine

Geometry: LineString/MultiLineString. Attributes: utility_type (`heat`, `water`, `sewer`, `electric`, `gas`, `telecom`, `other`, `unknown`), horizontal_accuracy_m nullable, elevation/depth measurements nullable, vertical_datum nullable, protection_profile_key nullable.

`depth_m` с положительным направлением вниз от земли не эквивалентна абсолютной `elevation_m`. Высоты из разных vertical datum не сравнивать без преобразования.

### 4.4. NetworkNode

Geometry: Point. Attributes: node_type, source_network_id, circuit (`supply`, `return`, `paired_corridor`, `unknown`), elevation_m nullable, pressure_pa nullable, temperature_c nullable, source/consumer flags, connection_permission.

### 4.5. NetworkEdge

Geometry: LineString; MultiLineString только после явного решения multipart semantics. Attributes: from_node_id, to_node_id, source_network_id, circuit, diameter_m nullable, material nullable, roughness_m nullable, installation_method nullable, measured_length_m nullable, geometry_length_m derived, flow_capacity fields nullable, status.

Разница measured_length_m и geometry_length_m диагностируется и сохраняется. Для hydraulic input выбирать источник длины через policy, а не перезаписывать произвольно.

### 4.6. ConnectionCandidate

Geometry: Point. Attributes:
- network_node_id nullable или approved_edge_location reference;
- permission: allowed/forbidden/unknown;
- permission_source и valid period;
- available_capacity_kw measurement nullable;
- capacity_basis: net_available/gross_with_separate_reservations/unknown;
- reserved_capacity_kw nullable, только если применимо;
- compatible_circuit_layouts;
- connection_method;
- approach_heading_constraints nullable;
- allowed_connector_length_m nullable;
- source_id и review notes.

Кандидат может быть геометрически достижимым, но административно неизвестным или инженерно не проверенным. Эти состояния не объединяются.

### 4.7. ForbiddenZone

Geometry: Polygon/MultiPolygon. Attributes: restriction_kind, reason, source_reference, active period, severity hard, review_status. User-drawn forbid zone имеет source_type=user и автора.

### 4.8. CoverageArea

Geometry: Polygon/MultiPolygon. Attributes: covered_kinds, completeness (`known_complete`, `partial`, `unknown`, `synthetic`), validity, assertion_source, exclusions. Completeness описывает предоставленный набор, не доказывает абсолютное отсутствие неучтённых сетей.

### 4.9. CrossingPortal

Geometry: LineString оси специального перехода. Attributes: entry_point/exit_point references или coordinates, footprint polygon reference, applies_to_feature_ids, method, width_m, allowed_headings, length_m, quantity_model_key, approval_status, evidence notes, vertical_constraints nullable.

Портал может отменить только конкретное правило пересечения конкретной дороги. Остальные hard constraints и coverage checks применяются ко всему footprint.

### 4.10. EntryGate

Geometry: Polygon зоны разрешённого подхода. Attributes: target_building_id, entry_point, permitted_heading, max_connector_length_m, exception_rule_ids. Ограниченная область исключения не должна включать проход сквозь здание.

## 5. Scenario и ScenarioRevision

Scenario — именованная задача. Revision содержит полный воспроизводимый ввод:

```text
id, scenario_id, revision, planning_date
selected_dataset_version_ids[]
rule_profile_version_id, cost_catalog_version_id?
target_building_id?, entry_point_wgs84, entry_gate_id?
requested_load_kw?, load_source
circuit_layout, corridor_width_m, construction_methods[]
connection_candidate_ids[] или selection_policy
waypoints_wgs84[], user_forbidden_zones[], preferred_corridors[]
mode: strict | exploratory
explicit_assumptions[]
objective_profiles[]
search_settings: resolution_m, aoi_wgs84, search_buffer_m, candidate_limit, budgets
```


Перед запуском backend делает preflight: опубликованные версии, совместимость CRS/temporal, допустимость entry, наличие хотя бы потенциальных кандидатов, ширина и методы, наличие prices для economical objective, критические gaps.

Необязательная нагрузка разрешена только в geometry-only сценарии. Отсутствие target_building_id допускается для ручного point-to-network теста, но это явно иной input_mode, не автоматически распознанное новое здание.

После редактирования создаётся новая revision; optimistic locking защищает от потери параллельных правок.

## 6. CalculationRun

Поля: id, scenario_revision_id, job_id, input_hash, submitted_at, started_at, finished_at, job_state, outcome, phase, versions_snapshot, algorithm_name/version, runtime_library_versions, parameters, assumptions, findings_summary, alternatives[], statistics, search_completion, optimality_scope, cache_info.

### 6.1. Разделение статусов

| Сущность/поле | Значения |
|---|---|
| job_state | queued, running, cancel_requested, succeeded, partial, failed, cancelled |
| outcome | pending, routes_found, no_route_in_model, insufficient_data, no_eligible_candidates, budget_exceeded, invalid_input, execution_error, cancelled |
| search_completion | complete, budget_exhausted, cancelled, not_started |
| optimality_scope | exact_in_selected_graph, bounded_candidate_search, candidate_reranking, heuristic_only, not_applicable |
| geometry_status | valid_in_model, invalid_in_model, insufficient_data |
| check_status | passed, failed, not_performed, insufficient_data, not_applicable |
| cost_status | complete, partial, unavailable |
| candidate_status | eligible, ineligible, requires_review |

`job_state=succeeded, outcome=no_route_in_model` допустим: алгоритм завершился корректно, но пути в выбранном графе нет. `job_state=failed` — ошибка исполнения, не пользовательский вывод об инженерной невозможности.

### 6.2. RouteAlternative

id, run_id, candidate_id, objective_tags[], centerline_wgs84, corridor_wgs84, metric_geometry_ref, segments[], check_summary, findings[], metrics, cost_breakdown, assumptions, comparison, postprocessing_log, geometry_hash.

Metrics:
- route_length_m;
- supply_pipe_length_m/return_pipe_length_m nullable;
- length_by_method/coverage;
- bend_count по определённому angle model;
- road_crossing_count и utility_potential_crossing_count;
- unverified_length_m — длина объединения интервалов, без двойного счёта overlapping coverage gaps;
- unknown_items_count по определённым типам;
- estimated_cost string nullable + currency + cost_status;
- search_score/objective vector отдельно от money;
- elapsed_ms и expanded_states.

Не показывать arbitrary `risk=0.07` как вероятность. Любой risk/exposure index именуется, формула и units доступны в справке.

### 6.3. RouteSegment

id, sequence, geometry, start_chainage_m, end_chainage_m, primitive_type, construction_method, corridor_width_m, related_feature_ids[], quantity_items[], findings[]. Chainage непрерывна, неотрицательна и соответствует геометрии в tolerance.

## 7. Правила и цены

### 7.1. RuleProfileVersion

Поля: id/version, name, status (`demo`, `draft`, `reviewed`), jurisdiction_scope nullable, source_references[], approved_by/at nullable, applies_to, rules[], missing_data_policy, geometry_tolerances, defaults_with_provenance, schema_version.

Rule: id, type, applies_to_kind, parameters, severity, missing_policy, source_reference, effective period, message_template. Текст сообщения — шаблон с whitelisted placeholders, не исполняемая программа.

RuleEvaluator возвращает structured findings и не обращается к UI. Новый evaluator регистрируется явно с тестами.

### 7.2. CostCatalogVersion

Поля: id/version, currency, price_date, estimate_status (`synthetic`, `provided`, `reviewed`), region_scope, tax_policy, items[], exclusions[], rounding_policy.

Item: code, description, quantity_unit, rate string, lower_rate/upper_rate nullable, applies_to_method/circuit/surface, per (`corridor_m`, `pipe_m`, `m2`, `event`, `item`), source_reference, valid period.

Несовместимые единицы и неразрешённые overlaps каталога — ошибка, не скрытая «лучшая цена».

## 8. Finding и объяснение

Обязательные поля:

```json
{
  "id": "finding-id",
  "code": "UTILITY_DEPTH_UNKNOWN",
  "severity": "warning",
  "check_status": "insufficient_data",
  "message": "В точке пересечения отсутствуют отметки существующей коммуникации",
  "rule_id": "rule-id",
  "feature_ids": ["feature-id"],
  "geometry": {"type": "Point", "coordinates": [37.62, 55.75]},
  "measured": null,
  "limit": null,
  "missing_fields": ["elevation_m", "vertical_datum"],
  "recommended_action": "Получить и сопоставить отметки по согласованной системе высот",
  "source_refs": ["dataset-version:feature-id"],
  "assumption_id": null
}
```

Пример иллюстративный: строковые placeholders не объявляются валидными UUID. Реальные DTO используют UUID.

Объяснения «вариант B дороже из-за перехода P1» строятся по cost positions с refs. Текстовый генератор P0 — детерминированные шаблоны, не LLM. Explainability должна работать без внешнего интернета.

## 9. REST API P0

Для collection endpoints — cursor pagination, stable ordering, `limit` с верхней границей. Все nested IDs проверяются по project/workspace. Версии API не совпадают с версиями domain data.

| Метод и путь | Назначение / ответ |
|---|---|
| POST /auth/login | создать session |
| POST /auth/logout | завершить session |
| GET /auth/me | пользователь и права |
| GET /capabilities | реально доступные форматы/solvers/exports/features |
| GET, POST /projects | список/создание |
| GET, PATCH /projects/{id} | карточка/optimistic update |
| POST /projects/{id}/demo-seed | только dev/demo и отдельное право |
| POST /projects/{id}/datasets/uploads | multipart upload, 202 + import/job ID |
| GET /imports/{id} | состояние импорта |
| GET /imports/{id}/inspection | слои, типы, CRS, sample |
| PUT /imports/{id}/mapping | сохранить mapping revision |
| POST /imports/{id}/validate | 202 + job ID |
| GET /imports/{id}/report | диагностический отчёт |
| POST /imports/{id}/publish | 202, atomic publish |
| GET /projects/{id}/dataset-versions | доступные версии |
| GET /dataset-versions/{id}/layers | слои |
| GET /layers/{id}/features | bbox WGS84, pagination, simplified display optional |
| GET /features/{id} | полные свойства и provenance |
| GET /projects/{id}/quality | качество и coverage |
| POST /projects/{id}/scenarios | новая задача |
| GET /scenarios/{id} | задача с revisions |
| POST /scenarios/{id}/revisions | immutable revision |
| POST /scenario-revisions/{id}/preflight | проверка готовности без запуска поиска |
| POST /scenario-revisions/{id}/runs | запуск, 202; Idempotency-Key |
| GET /runs/{id} | результат/состояние |
| GET /runs/{id}/alternatives | computed alternatives |
| GET /runs/{id}/findings | issues с bbox/severity filters |
| GET /runs/{id}/events | SSE, sequence IDs/reconnect |
| POST /runs/{id}/cancel | запросить cooperative cancel |
| POST /alternatives/{id}/proposed-edits | создать draft geometry edit; не менять оригинал |
| POST /proposed-edits/{id}/validate | проверка изменённой трассы, 202 |
| POST /runs/{id}/exports | format enum, 202 |
| GET /exports/{id} | status + authorized download reference |
| GET /artifacts/{id}/download | файл после проверки прав |
| GET /rule-profiles | список versions |
| POST /rule-profiles/{id}/versions | новая draft/demo version |
| GET /cost-catalogs | список versions |
| POST /cost-catalogs/{id}/versions | новая версия |
| GET /jobs | scoped jobs list |
| GET /audit-events | scoped pagination, admin |
| GET /health/live | процесс жив |
| GET /health/ready | обязательные зависимости готовы |

P1: MVT `/layers/{id}/tiles/{z}/{x}/{y}.mvt`, diff dataset versions, adapter registry, OIDC, server PDF. Capabilities не должны включать эти endpoints до реализации.

### 9.1. Ошибки

Единый envelope:

```json
{
  "error": {
    "code": "CRS_UNCONFIRMED",
    "message": "Подтвердите систему координат перед публикацией",
    "details": {"import_id": "…", "missing_fields": ["source_crs"]},
    "request_id": "…",
    "retryable": false
  }
}
```

400 — синтаксически/семантически неверный запрос, 401 — нет аутентификации, 403/404 — политика недоступного ресурса без утечки, 409/412 — version/idempotency conflict, 413 — превышен upload limit, 422 — поля/валидация, 429 — budget/rate limit, 5xx — инфраструктурная ошибка. Финальные правила единообразно задокументировать в OpenAPI и тестах.

### 9.2. SSE

События: run.queued, run.phase_started, run.progress, run.alternative_validated, run.completed, run.failed, run.cancelled. Envelope: sequence, run_id, timestamp, phase, payload. Не отправлять сырые exception traces или геоданные другого workspace.

Reconnect с Last-Event-ID восстанавливает пропущенные события; при истёкшем retention клиент получает snapshot_required и перечитывает run. Polling fallback не создаёт повторного run.

## 10. Геоданные в БД и индексы

Рекомендуемые индексы: GiST geometry; btree workspace/project/version/kind; unique(version, source_namespace, source_id); network endpoint references; run input_hash; job status/created_at; audit project/time.

Любой spatial query сначала ограничивает tenant/project/version и AOI. Bbox/limit обязательны для map features. Состав полей тайлов минимален; секретные/raw атрибуты в MVT не включаются.

Prepared metric geometries/cache живут в контексте project CRS и version. `ST_SetSRID` не выполняет перепроецирование и не должен использоваться вместо transform. Это отдельный обязательный regression test.

## 11. Невырожденные интерфейсы расширения

```python
class GraphProvider(Protocol):
    def start_states(self, request: RoutingRequest) -> Iterable[State]: ...
    def neighbors(self, state: State) -> Iterable[Transition]: ...
    def is_goal(self, state: State) -> bool: ...

class RouteSolver(Protocol):
    def solve(self, graph: GraphProvider, objective: Objective,
              budget: ComputationBudget, control: RunControl) -> SolverResult: ...

class RouteValidator(Protocol):
    def validate(self, route: RouteGeometry,
                 context: ValidationContext) -> ValidationReport: ...

class QuantityEstimator(Protocol):
    def estimate(self, route: ValidatedRoute,
                 model: QuantityModel) -> QuantityReport: ...

class EngineeringValidator(Protocol):
    def required_inputs(self, model_version: str) -> InputRequirements: ...
    def validate(self, context: EngineeringContext) -> EngineeringReport: ...
```

Protocols — описание проектного контракта. Реализовать минимально достаточные типы, а не механически копировать неимпортируемый псевдокод. При обновлении DTO регенерировать OpenAPI/client и contract tests.

## 12. Проверка примеров комплекта

`examples/manifest.demo.json` связывает синтетические GeoJSON и profiles. `scripts/validate_spec_examples.py` проверяет структуру, ссылки, hashes, координаты, математические fixtures и маркировку demo.

Этот скрипт не валидирует соответствие будущего бэкенда ТЗ и не заменяет integration/geometry tests. Успешный запуск означает только внутреннюю согласованность поставляемых примеров.
