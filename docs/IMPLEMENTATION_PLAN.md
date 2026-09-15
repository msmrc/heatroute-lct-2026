# План реализации для Codex

## 1. Порядок важнее количества экранов

Работать последовательно, завершая вертикальные срезы. Ориентир распределения усилий: основная часть — данные, backend, вычисления и тесты; frontend собирается на готовых компонентах. Не тратить начало проекта на лендинг, логотип, анимации и выдуманный dashboard.

Оценки сроков не зафиксированы: исходный репозиторий, состав команды и реальный массив пока неизвестны. Каждый milestone имеет вход, выход и проверку. Недоступность данных организаторов не блокирует M0–M5.

Вести `docs/implementation/progress.md`: статус задач, принятые ADR, реально выполненные команды, проблемы среды, следующий конкретный шаг. Сохранять контекст для следующей сессии, но не заменять реализацию бесконечным планированием.

## 2. M0 — репозиторий и минимальная инфраструктура (P0)

**Задачи.** Проанализировать существующий checkout; не уничтожать чужие изменения. Если репозиторий пустой — создать monorepo. Зафиксировать Python/Node/dependency versions, uv/pnpm lockfiles, Docker Compose с PostgreSQL/PostGIS, Redis, API/worker/web, healthchecks, volumes, `.env.example`. Подготовить migrations, lint/typecheck/test commands и CI.

Создать ADR-001 `stack-and-module-boundaries`, ADR-002 `coordinate-systems`, ADR-003 `jobs-and-idempotency`. Сразу определить DTO errors и schema versioning. Не предлагать десять стеков: применять выбранный в ТЗ, если нет конкретной доказанной несовместимости.

**Готово, когда:** из чистого checkout поднимаются зависимости, migrations создают схемы, health endpoints отражают состояние; тест API обращается к реальному PostGIS, worker получает тестовое безопасное задание. Frontend может быть минимальной оболочкой без декоративных экранов.

**Не считается готовым:** только структура директорий/README, fake health без проверки обязательных зависимостей или Compose с отсутствующими build contexts.

## 3. M1 — проекты, версии, сырьё и импорт (P0)

**Задачи.** Workspace/user/session/access; project/version model; LocalArtifactStorage; Raw/Staging/Canonical; upload/inspect/map/validate/publish; GeoJSON и GeoPackage adapters; CSV declared-columns adapter; provenance; CRS confirmation; typed safe transforms; geometry report/quarantine; atomic publication.

Добавить profiles и demo dataset. Создать основной DatasetAdapter contract и тест второй схемы: те же смысловые данные с переименованными полями/другими единицами должны нормализоваться через новый mapping без изменения routing-модуля.

**Готово, когда:** raw bytes/hashes сохраняются; правильный импорт публикуется; неверный CRS/геометрия/ID создаёт ожидаемые findings; предыдущая version неизменна. API/CLI могут получить слой и feature provenance без UI.

**Проверки:** ING-01…12, SEC-01…06, contract tests dataset/version.

## 4. M2 — предметные ограничения и существующая сеть (P0)

**Задачи.** NetworkGraph с явной связностью и circuits; topology diagnostics; ConnectionCandidate screening; RuleProfile evaluator registry; coverage/temporal/missing policies; corridor validation; entry gate и bounded connectors; crossing portals; независимый RouteValidator.

Сначала реализовать геометрические предикаты и tests, потом подключать поиск. Даже вручную заданная линия должна проверяться сервером и давать качественный report.

**Готово, когда:** валидный коридор проходит, пересечение здания/узкая щель/недопустимый portal отклоняются; unknown-data не становится passed; разрешение на врезку не выводится из расстояния до трубы; сеть с циклом и XY crossings сохраняет правильную topology.

**Проверки:** GEO-01…14, TOP-01…10, CRS-01…08.

## 5. M3 — реальные расчёты и первый вертикальный срез (P0)

**Задачи.** ScenarioRevision и preflight; CalculationRun snapshots; outbox/queue/leases; Dijkstra и A* над GraphProvider; неявное раскрытие neighbors; headings/waypoints/construction modes; limits и cooperative cancellation; objective profiles; alternatives и dedup; final validation; quantity/cost engines; explainability templates; SSE/polling; persistence.

Сделать CLI и HTTP run. Первый UI-срез: карта показывает **реальную** линию из API, obstacle обходится вычислительно, а не выдаётся предрисованная geometry из fixtures. При изменении запрета расчёт меняется.

Не запускать full city grid и не материализовать миллионы NetworkX objects. Добавить измерение памяти и этапов до сложного ускорения.

**Готово, когда:** демонстрационный район даёт проверенные разные варианты при наличии; shortest/cost различаются на специальном fixture; no-route и budget различаются; результат сохраняется; повтор доставки безопасен; стоимость арифметически проверяется.

**Проверки:** RTE-01…15, CST-01…10, JOB-01…10, малые seeded random graph comparisons.

**Артефакты:** первый benchmark report и CLI-generated run passport. Не заявлять достигнутые performance targets без замеров.

## 6. M4 — полноценный web workspace (P0)

**Задачи.** Реализовать `UI_SPEC.md` на готовых shadcn components: projects, import wizard, data/quality, scenario editor, map layers, route alternatives/comparison, properties/findings, history, jobs, rule/cost versions. Typed API client генерируется из OpenAPI.

Server state через TanStack Query, UI state отдельно. Deep links/reload, optimistic revision control, progress/reconnect/cancel, loading/error/empty/partial/stale states, dark/light и адаптивные панели.

**Готово, когда:** нет обязательных fake buttons, run/search не живёт на фронте, редактирование запретов создаёт новые revisions, inspector показывает source/versions/missing fields. Пользователь проходит сценарий без DevTools и ручной подстановки UUID.

**Проверки:** E2E-01…04, E2E-06, browser console errors, focus/keyboard, screenshots основных состояний.

## 7. M5 — экспорт, устойчивость и приёмка законченного P0

**Задачи.** GeoJSON/CSV/JSON/HTML exports; dependency-free runtime demo; полный access review; безопасный file pipeline; audits; readiness/logs/basic metrics; restart/retry tests; README и actual capabilities; окончательная OpenAPI/client; полный acceptance evidence pack.

Провести clean-checkout rehearsal: другой каталог/чистые volumes, documented commands, seed-demo, весь пользовательский сценарий. Проверить сохранность после restart. Production demo bypass disabled.

**Готово, когда:** все P0 acceptance groups пройдены либо конкретное невыполненное требование явно блокирует «P0 готов». Ни front screenshot, ни отсутствие ошибок typecheck сами по себе не завершают этап.

**Результат:** рабочая основа, которую уже можно показывать и развивать без переписывания ядра после получения данных.

## 8. M6 — полезные расширения P1

Реализовывать после M5 по измеренной потребности:

| Возможность | Условие/критерий |
|---|---|
| Local primitive optimizer | те же вход/выход/heading; before/after tests; final validation и measurable quantities |
| Zipped Shapefile/GeoParquet | demand формата, secure parser, fixtures и mapping reuse |
| MVT/big layer UX | доказанная проблема GeoJSON/bbox; версии и auth в cache keys |
| Dataset diff/replay | added/changed/deleted/quality diff; reproducible replay прежнего сценария |
| S3ArtifactStorage | тот же storage contract и интеграционные тесты |
| GeoPackage/PDF exports | корректное CRS/слои/метаданные и визуальный контроль PDF |
| 3D extrusion/model attachments | не меняет status инженерной проверки; offline assets |
| Backups/restore/OIDC/observability | production readiness перед значимым внедрением |

P1 не должен менять смысл старых результатов и baseline-контрактов. Любое улучшение search сопровождается regression suite и comparator report.

## 9. M7 — адаптация к организаторам и инженерный P2

Исполнить `ORGANIZER_DATA_PLAYBOOK.md` на реальной поставке. Первым результатом является data-readiness report, а не обещание «подключили всё». Согласовать required checks, temporal semantics, источники нормативов, candidate permissions, cost/capacity meaning.

При достаточных данных добавить инженерный adapter и validation fixtures. Не активировать на production результатах, пока не проверены сеть/units/boundary conditions/convergence и отсутствующие данные.

Batch connections/shared capacities, longitudinal profile, 3D collisions, real CAD/BIM и интеграции — отдельные законченные функции с собственными тестами. Они не входят автоматически в P0 только от наличия слова «полная система».

## 10. Правило остановки и продолжения сессии Codex

Если исчерпан доступный ресурс или обнаружен внешний blocker, не маркировать всё готовым. Зафиксировать точный последний прошедший milestone, новые/изменённые файлы, команды/exit codes, неуспешные тесты и следующий шаг. Код должен оставаться в осмысленном состоянии, без заглушек, замаскированных под вычисления.

При продолжении сначала перечитать progress и соответствующий раздел контрактов, воспроизвести последний smoke test, затем реализовать следующий кусок. Не начинать новый UI/стек с нуля в каждой сессии.
