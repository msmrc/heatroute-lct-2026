# HeatRoute — предварительная трассировка тепловых сетей

HeatRoute — монорепозиторий конкурсного решения ЛЦТ 2026. Production backend полностью переведён
на Java 11 / Spring Boot 2.6.3. Старого Python runtime, FastAPI, Celery, Alembic и Redis в активном
контуре нет.

## Текущий состав

- `apps/api` — Java API, официальный GeoJSON-контракт, PostGIS, Liquibase, durable jobs;
- `apps/web` — React/TypeScript UI для загрузки официального файла и topology job;
- `infra` — pinned Docker images, Nginx и Caddy;
- `docs/implementation/OFFICIAL_TZ_ROADMAP.md` — единый roadmap R0–R9;
- `docs/implementation/TOMORROW_HANDOFF.md` — точка входа для следующей смены.

Сейчас реализованы и проверены foundation, потоковая инспекция/сохранение семи входных типов,
базовый анализ существующей сети, кандидаты врезки, правила ограничений и чистое Java-ядро
подбора ДУ. Полный multi-OKS routing, реконструкция, официальный расчёт стоимости/score и строгий
семитипный экспорт ещё не готовы. Проект нельзя считать закрытым по официальному ТЗ.

## Запуск

```powershell
pwsh -File scripts/dev.ps1 bootstrap
pwsh -File scripts/dev.ps1 up
```

- UI: `http://localhost:5173/`
- readiness: `http://localhost:8000/api/v1/health/ready`
- Swagger: `http://localhost:8000/swagger-ui.html`

Проверки:

```powershell
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 lint
pwsh -File scripts/dev.ps1 typecheck
```

Все большие tool/cache paths на Windows должны оставаться под `E:\job\.tooling`.

## Документы

1. [Старт следующей смены](docs/implementation/TOMORROW_HANDOFF.md)
2. [Официальный roadmap и gap analysis](docs/implementation/OFFICIAL_TZ_ROADMAP.md)
3. [Техническая спецификация](docs/TECH_SPEC.md)
4. [Контракты данных](docs/DATA_CONTRACTS.md)
5. [Критерии приёмки](docs/ACCEPTANCE.md)
6. [Обновление VPS](docs/operations/VPS_DEPLOYMENT.md)

Старые `m*-evidence.md` описывают прежний прототип и используются только как историческая
справка. Они не подтверждают готовность Java-решения.
