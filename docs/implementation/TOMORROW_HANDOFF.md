# Handoff — Артём / PM / developer

**Prepared:** 2026-09-16
**Repository:** `git@github.com:msmrc/heatroute-lct-2026.git`
**Branch:** `master`
**Public demo:** `https://130-49-150-217.sslip.io/`

> Текущий код зафиксирован в Git и проверен локально/в CI. Публичный VPS намеренно остаётся на
> более раннем демонстрационном checkpoint: не обновлять его без отдельной команды владельца.

## What changed today

- Java is now the only backend and lives at `apps/api`.
- Default/local/offline/VPS Compose all point to the Java image.
- The old Python application, Alembic, Celery/Redis services, Python tests and lockfiles are gone.
- The web app calls the Java official import/job contract and no longer calls legacy project/run
  endpoints.
- CI has Java verify/image, web quality/build and clean Ubuntu 22 / Compose 1.29.2 integration
  gates, including 50 concurrent imports, a real all-OKS calculation and restart recovery.
- OpenAPI comes from springdoc and is committed at `packages/api-client/openapi.json`; strict input,
  supplied-dataset compatibility and output JSON Schemas are published by the Java API.
- Commit `e47cd72` is deployed on the VPS. Production now runs only PostGIS, Java API, web and
  gateway; public HTTPS, Java readiness, official import, topology and immutable calculation run
  were verified.
- R8 vertical profiling доведён на официальном наборе: все 50 участков трёх вариантов имеют
  завершённый профиль, без `NO_VERTICAL_PASSAGE` и `VERTICAL_TRANSITIONS_OVERLAP`. Переходы рядом
  с камерой могут начинаться/заканчиваться на допустимой глубине, соседние пересечения на одной
  отметке объединяются в непрерывную полку, а выход вдоль выбранной сети не считается ложным
  пересечением.
- Последняя локальная проверка: Java 11 `mvn verify` — 111 тестов, 0 failures/errors, 3 явно
  отключённых scale-probe; web — 16 Vitest + 4 replay API tests, lint/typecheck/build/audit зелёные.

## Start in five minutes

```powershell
cd E:\job\_lct2026\heatroute_codex
git status --short
git pull --ff-only origin master
pnpm install --frozen-lockfile

# Терминал 1 — воспроизводимый локальный API на заранее рассчитанном официальном результате
node scripts/local-demo-server.mjs tmp/local-demo-bundle.json

# Терминал 2 — web; 5174 выбран, чтобы не конфликтовать с другим проектом на 5173
$env:VITE_DEV_API_PROXY = 'http://127.0.0.1:8000'
pnpm --filter @heatroute/web dev -- --host 127.0.0.1 --port 5174 --strictPort
```

Открой `http://localhost:5174/`. Нажми «Открыть демо» или загрузи
`datasets/official/lct-2026.geojson`: локальный replay принимает только точные официальные байты
по размеру и SHA-256, запускает тот же пользовательский happy path и не подменяет чужой файл
готовым результатом.

Если нужно заново рассчитать bundle из Java, используй Java 11 и Maven с кэшами на `E:`:

```powershell
$env:JAVA_HOME = 'E:\job\.tooling\apps\temurin-11\jdk-11.0.32.1+1'
& 'E:\job\.tooling\apps\apache-maven-3.9.16\bin\mvn.cmd' `
  '-Dmaven.repo.local=E:\job\.tooling\m2\repository' `
  '--batch-mode' '--no-transfer-progress' `
  '-Dheatroute.demo.output=E:\job\_lct2026\heatroute_codex\tmp\local-demo-bundle.json' `
  -f apps/api/pom.xml verify
```

## Current reality

Before using the older handoff below, read `ORGANIZER_VIDEO_CLARIFICATIONS.md`. The organizer Q&A
changed the active supplied-dataset scope: reconstruction is not mandatory, depth is second-stage,
and several 2D/economics rules are not yet implemented. Older statements that R4–R8 need no
algorithm changes are superseded by this note.

The mandatory 2D Java pipeline is implemented end to end on contract-complete fixtures. The public
UI exposes only what the backend can prove. Do not restore legacy screens or fabricate missing
organizer fields.

The newly supplied dataset is not shaped like the published seven-type contract. Read
`SUPPLIED_DATASET_AUDIT.md` before changing validation or routing. Use its 17 connection points as
demand objects under the named compatibility profile; never fabricate missing existing flows or
upstream links.

Already usable:

- streaming seven-type GeoJSON inspection and PostGIS persistence;
- WGS84 plus EPSG:32637 storage;
- existing-network topology diagnostics;
- deterministic tie-in candidates and 10 m chamber feasibility rule;
- durable PostgreSQL topology jobs with progress/cancel/recovery;
- pure Java official restriction catalog, crossing geometry and DU sizing primitives.
- immutable R4 runs with deterministic independent/shared/diverse variants, obstacle-aware
  polylines, partial no-route and a separate tree/chamber/crossing validator;
- integrated R6 construction/final validation for dynamic OKS buffers, hard forbidden zones and
  reproducible base/special crossings;
- bottom-up `flow_tph` and automatic DU selection across all flow/continuous-length catalog rows;
- upstream propagation, partial/common-section reconstruction and used-chamber reconstruction for
  contract-complete existing-network input;
- current reproducible evidence on the organizer file: the preferred independent variant connects
  all 17 demands with zero structural validator issues; shared connects 16 and diverse 14 while
  preserving explicit no-route diagnostics for the remaining objects. Across all three variants,
  all 50 route edges have complete validated depth profiles and zero depth issues.
- interactive result viewer with MapLibre GL/CARTO vector basemap, PostGIS source context, layer
  toggles, variant comparison, map-object inspection, no-route diagnostics, a retained metric
  schematic and a latest-completed-run demo endpoint. Complete variants render through the strict
  R7 seven-type adapter; the supplied incomplete file intentionally uses the internal preview.
- map-first result UX with floating inspector/results islands over one uninterrupted map, the real
  imported filename in the toolbar and a collapsible navigation rail. Technical stack, version,
  team and Swagger live on the separate `/system` page instead of the work screen.

Implementation work before an unconditional official P0 claim:

- allow complete supplied-profile cost/rank/export without reconstruction;
- enforce normal egress from the containing OKS for all 17 demand points;
- optimize connect versus official unconnected penalty;
- account for non-standard bend ×1.5, max overlapping `K_special` and one tie-in per new ray;
- separate optional depth from mandatory 2D and remove whole-import Java-list routing.

External decisions still required:

- organizer approval that the passing full 2× topology gate represents the hidden maximum;
- organizer clarification of `railway`, reduced output types and disputed depth/length rules;
- production-like Ubuntu 22 host rehearsal only if clean ephemeral CI is not accepted.

## Developer: next vertical slice

Preserve the immutable run/job contract, but do not treat current R7 as final for the supplied
profile. Continue from routing/economics/export. R7 component costs,
length, score/rank, strict seven-type serialization, independent whitelist/type/reference
validation, feature-by-feature preflight and incremental Jackson download are already integrated.
R7 is closed against the normative appendix tables and formulas. Section 10.8 explicitly calls its
numbers illustrative and differs by 19/33 RUB; keep the golden expectations derived from tables
4.1, 5.1, 8 and 9. Complete variants are fetched per `variant_id` and rendered from the strict
official output model. Dense constraint lookup uses adaptive JTS STRtree and is locked by a
1,001-constraint/20,000-query fixture. The project-owned full 2× topology gate passes on Ubuntu 22
/ Java 11 with 34/34 demands; obtain organizer approval before calling it the official maximum.
R8 is preserved evidence for the published rules, but must become a separate second-stage mode.
Do not change disputed 0.5 m / 0.7 m / slope semantics before a written organizer answer.
Repeated imports are idempotent by `(contract_version, raw_sha256)` and concurrent duplicates are
resolved by PostgreSQL `ON CONFLICT`; preserve this invariant in all future import changes.
Job execution is bounded by `HEATROUTE_JOB_CONCURRENCY` (default 2, hard maximum 16) and active
leases are renewed every minute. Use `docs/operations/R9_ACCEPTANCE.md` for scale evidence; do not
call the probes themselves a pass until their generated measurements are archived.
Manual run `35112046184` proves exact 3 GiB input and ≥500 MiB valid output on Ubuntu 22 / Java 11
under `-Xmx512m`. Final clean-stack run `35129162919` passes backend/web/integration, including the
50-user import race, the real calculation, schema/API contracts and restart recovery; topology run
`35120995991` passes 288 features, 34/34 demands and three variants in 2:14.65 with 406,608 KiB
peak RSS. Do not conflate this with a VPS deployment, which remains explicitly deferred.
Do not mix MVT or extra formats into the remaining external acceptance gate.

## Артём: с чего продолжать

1. Сначала проверь чистый `master`, запусти команды из раздела «Start in five minutes» и пройди
   основной сценарий `файл -> loader -> карта -> варианты -> предупреждения -> профиль`.
2. Не переписывай каркас R4–R8, но исправь перечисленные в
   `ORGANIZER_VIDEO_CLARIFICATIONS.md` Q&A-P0 правила. Реконструкция supplied dataset больше не
   должна считаться внешним блокером результата.
3. Для конкурсной сдачи собирай материалы по `docs/CONTEST_SUBMISSION.md`, а критерии сверяй с
   `docs/ACCEPTANCE.md`. Реконструкцию показывай только как расширенный strict-profile.
4. Если потребуется менять R8, сначала сохрани инварианты из
   `docs/implementation/R8_VERTICAL_EVIDENCE.md`: полка 4 м, шаг глубины 0.5 м, уклон не более
   0.10 м/м, независимая валидация, честный partial/no-route при невозможном проходе.
5. Любую новую контрольную точку: локальные тесты -> commit -> push -> дождаться всех GitHub Actions.
   На VPS не выкладывать, пока владелец явно не скажет это сделать.

Текущее честное ограничение результата: 324 записи в интерфейсе — это 323 входных предупреждения
официального файла и 1 предупреждение реконструкции. Вертикальных ошибок после этого checkpoint нет.

## PM: tasks tomorrow

- include the published, CI-tested JSON Schemas from `docs/contracts` in the submission kit;
- confirm whether Ubuntu 22 is mandatory for judging even though the current demo VPS uses a
  newer Ubuntu release;
- supply or approve an official-like maximum-topology fixture and load-test environment;
- keep MVT and extra formats outside P0 until the external R9 decisions close;
- review every “complete” claim against `docs/ACCEPTANCE.md`, not old M-stage evidence.
- ask the organizer to resolve the supplied-dataset mismatch, especially `railway`, direct demand
  on connection points, reduced output types, continuous-length branching and disputed depth rules.

## Known operational notes

## Latest product-flow decision (2026-09-16)

- Do not reintroduce the import/report page into the valid-file happy path. Upload must proceed as
  `file -> loader/progress -> completed map` and start the official run automatically.
- Input warnings remain available from the clickable `Проверка структуры` metric in the result
  island. Keep the full API diagnostics; do not replace them with a fake aggregate.
- The VPS intentionally remains on an older demonstrated baseline. Current checkpoints are
  Git/local/CI-only; do not deploy them without a separate user command.

- Local Docker data and tool caches must remain on `E:`.
- Never commit `.env.vps`, keys or dumps. The sole approved organizer dataset is the byte-identical
  `datasets/official/lct-2026.geojson`; do not add copies or synthetic dataset files.
- VPS updates follow `docs/operations/VPS_DEPLOYMENT.md`; take a DB backup first.
- Database schema history is now Liquibase under `apps/api/src/main/resources/db/changelog`.
- On the current Windows workstation Docker Desktop is blocked after reboot by a stale internal
  socket. CI and VPS are healthy. Do not factory-reset Docker or move its `E:` data; repair the
  local daemon separately before using the local `up` command.
