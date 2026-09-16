# START HERE

Продолжай разработку из `E:\job\_lct2026\heatroute_codex` на ветке `master`.

Перед изменениями прочитай в этом порядке:

1. `docs/implementation/TOMORROW_HANDOFF.md`;
2. `docs/implementation/OFFICIAL_ALIGNMENT_AUDIT.md`;
3. `docs/implementation/OFFICIAL_TZ_ROADMAP.md`;
4. `docs/TECH_SPEC.md` и `docs/DATA_CONTRACTS.md`;
5. `docs/ACCEPTANCE.md`;
6. `AGENTS.md`.

Backend только Java 11 / Spring Boot 2.6.3 в `apps/api`. Не возвращай Python, FastAPI, Celery,
Alembic или Redis. Не считай старые M0–M7 evidence текущей готовностью. Первый R4 vertical slice
закрыт; следующий критический этап — obstacle-aware R4/R6 routing, затем подключение R5 sizing.

Быстрая проверка состояния:

```powershell
git -C E:\job\_lct2026\heatroute_codex status --short
pwsh -File E:\job\_lct2026\heatroute_codex\scripts\dev.ps1 test
```
