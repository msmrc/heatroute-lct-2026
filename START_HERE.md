# START HERE

Работай из корня репозитория на ветке `master`. До первого Git-действия проверь:

```powershell
git rev-parse --show-toplevel
git branch --show-current
git status --short
```

Ожидаемый root: `E:\job\_lct2026\heatroute_codex`.

## Что прочитать

1. `AGENTS.md`;
2. `docs/implementation/ACTIVE_ROUTING_RULES.md`;
3. `NETWORK_SOLVER_SPEC.md`;
4. `docs/ALGORITHM.md`;
5. `docs/DATA_CONTRACTS.md`;
6. `docs/ACCEPTANCE.md`;
7. `docs/implementation/progress.md` для истории проверок.

Официальное ТЗ и письменные разъяснения организаторов от 29.09.2026 имеют приоритет над старыми
evidence-файлами и рабочими гипотезами.

## Текущий production-контур

- backend: Java 11 / Spring Boot 2.6.3;
- геометрия: JTS, Proj4J, PostGIS;
- оптимизация: OR-Tools CP-SAT;
- planner: `HeatRoutePlanner`;
- production API использует один `HeatRouteRoutingAlgorithm`;
- профилей алгоритма и скрытого переключения реализации нет;
- актуальный output whitelist: `heat_network`, `heat_chamber`, `technical_node`,
  `variant_summary`.

Не возвращай старую архитектуру independent/shared/diverse, seven-type export, трактовку
`railway` как `tram`, обязательные три варианта или выдуманный минимум 10 м между новыми
камерами.

## Проверенный witness

Production run `32df0407-22a4-42b2-8426-2bb88be8d46e` от 29.09.2026:

- сборка алгоритма 6;
- 17/17 подключений;
- 15,918 с между созданием и завершением повторного run; cold run — 19,017 с;
- 2 170,113 м;
- 300 092 048,85 ₽;
- validation / engineering / sizing issues: 0 / 0 / 0.

Это evidence конкретного набора и VPS. Не выдавай его за гарантированное время для скрытого
городского набора или доказательство глобального оптимума.

## Быстрые проверки

```powershell
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 lint
pwsh -File scripts/dev.ps1 typecheck
pwsh -File scripts/dev.ps1 build
```

Публичный стенд:

- <https://130-49-150-217.sslip.io/>;
- <https://130-49-150-217.sslip.io/api/v1/swagger-ui.html>;
- <https://130-49-150-217.sslip.io/api/v1/health/ready>.

Не публикуй изменения на VPS, не коммить и не пушь без прямой команды пользователя.
