# HeatRoute — предварительная трассировка тепловых сетей

HeatRoute — система проектирования маршрутов теплоснабжения. Production backend полностью переведён
на Java 11 / Spring Boot 2.6.3. Старого Python runtime, FastAPI, Celery, Alembic и Redis в активном
контуре нет.

## Текущий состав

- `apps/api` — Java API, официальный GeoJSON-контракт, PostGIS, Liquibase, durable topology и
  immutable all-demand calculation runs;
- `apps/web` — React/TypeScript UI для загрузки официального файла, диагностики, запуска R4,
  быстрой векторной GIS-карты MapLibre/CARTO и инженерной схемы рассчитанных вариантов;
- `datasets/official/lct-2026.geojson` — единственный отслеживаемый набор геоданных, побайтовая
  схема базового входного профиля;
- `infra` — pinned Docker images, Nginx и Caddy;
- `docs/implementation/OFFICIAL_TZ_ROADMAP.md` — единый roadmap R0–R9;
- `docs/ALGORITHM.md` — описание обязательного 2D-алгоритма и границ применимости;
- `docs/CONTEST_DEMO.md` — воспроизводимый демонстрационный сценарий;
- `docs/CONTEST_SUBMISSION.md` — короткое описание решения для экспертов и презентации;
- `docs/contracts` — тестируемые JSON Schema расширенного входа, базового входного профиля и результата;
- `docs/implementation/TOMORROW_HANDOFF.md` — точка входа для следующей смены.

Сейчас реализован обязательный 2D pipeline: потоковый вход, существующая топология, автоматические
врезки, obstacle-aware multi-OKS routing с тремя стратегиями, независимая проверка ограничений,
расходы/ДУ, реконструкция, официальная экономика/rank и строгий семитипный потоковый экспорт.
Поставленный файл не содержит baseline/direction данных для полной реконструкции, поэтому его
официальный score/export честно блокируются; это не обходится выдуманными значениями.

## Запуск

```powershell
pwsh -File scripts/dev.ps1 bootstrap
pwsh -File scripts/dev.ps1 up
```

- UI: `http://localhost:5173/`
- readiness: `http://localhost:8000/api/v1/health/ready`
- Swagger: `http://localhost:8000/api/v1/swagger-ui.html`
- JSON Schema результата: `http://localhost:8000/api/v1/official/contracts/output.schema.json`

Проверки:

```powershell
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 lint
pwsh -File scripts/dev.ps1 typecheck
```

По умолчанию локальные tool/cache-файлы создаются в `.tooling` внутри checkout. При необходимости
путь переносится переменной `HEATROUTE_TOOLING_ROOT` без изменения файлов проекта.
Порты также настраиваются без правки Compose: например, перед `up` можно задать
`$env:WEB_HOST_PORT='5174'` и `$env:API_HOST_PORT='8080'`. На Windows скрипт автоматически
использует ASCII-ссылку для Docker build context, если путь checkout содержит не-ASCII символы.

### Локальный просмотр расчёта без Docker/PostGIS

Read-only стенд строит данные из единственного официального GeoJSON тем же Java-ядром и не
публикует их на VPS:

```powershell
$root = (Get-Location).Path
Push-Location apps/api
mvn "-Dheatroute.demo.output=$root\tmp\local-demo-bundle.json" `
  -Dtest=OfficialDatasetRoutingTest test
Pop-Location

# Терминал 1
node scripts/local-demo-server.mjs tmp/local-demo-bundle.json

# Терминал 2; 5174 не конфликтует с проектом, занимающим 5173
$env:VITE_DEV_API_PROXY = 'http://127.0.0.1:8000'
pnpm --filter @heatroute/web dev -- --host 127.0.0.1 --port 5174 --strictPort
```

Откройте `http://localhost:5174/`. Можно нажать «Открыть демо» либо загрузить
`datasets/official/lct-2026.geojson`: локальный API проверит точный размер и SHA-256, воспроизведёт
полный пользовательский переход «загрузка → расчёт → карта» и не покажет чужой результат для
другого файла. Сгенерированный bundle находится в игнорируемом `tmp/`; в Git он не попадает.
Bundle не подменяет отчёт импорта демонстрационными значениями: тот же потоковый Java-инспектор
записывает фактические 233 277 байт, SHA-256 и 76 содержательных предупреждений об отсутствующих
данных реконструкции. Поле `railway` в этом наборе трактуется как ошибочно названный `tram_tracks`.

Для проверки именно production-сборки при работающем локальном API:

```powershell
$env:VITE_DEV_API_PROXY = 'http://127.0.0.1:8000'
pnpm build
pnpm preview -- --host 127.0.0.1 --port 5175 --strictPort
```

После этого стенд доступен на `http://localhost:5175/`; preview проксирует тот же `/api` и не
требует изменения frontend-конфигурации.

## Документы

1. [Старт следующей смены](docs/implementation/TOMORROW_HANDOFF.md)
2. [Официальный roadmap и gap analysis](docs/implementation/OFFICIAL_TZ_ROADMAP.md)
3. [Техническая спецификация](docs/TECH_SPEC.md)
4. [Контракты данных](docs/DATA_CONTRACTS.md)
5. [Критерии приёмки](docs/ACCEPTANCE.md)
6. [Обновление VPS](docs/operations/VPS_DEPLOYMENT.md)
7. [Повторный аудит официального соответствия](docs/implementation/OFFICIAL_ALIGNMENT_AUDIT.md)
8. [Краткое описание решения](docs/CONTEST_SUBMISSION.md)
9. [Машиночитаемые JSON Schema](docs/contracts/README.md)

Старые `m*-evidence.md` описывают прежний прототип и используются только как историческая
справка. Они не подтверждают готовность Java-решения.
