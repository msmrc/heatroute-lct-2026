# Tomorrow handoff — PM and developer

**Prepared:** 2026-09-16
**Repository:** `git@github.com:msmrc/heatroute-lct-2026.git`
**Branch:** `master`
**Public demo:** `https://130-49-150-217.sslip.io/`

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

## Start in five minutes

```powershell
cd E:\job\_lct2026\heatroute_codex
git status --short
git pull --ff-only origin master
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 up
```

Open `http://localhost:5173/` and `http://localhost:8000/api/v1/swagger-ui.html`. If 5173 is occupied,
set `$env:WEB_HOST_PORT='5174'` for the smoke rather than killing an unknown process.

## Current reality

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
- production evidence on the organizer file: the preferred shared variant connects all 17 demands
  with zero structural validator issues.
- interactive result viewer with MapLibre GL/CARTO vector basemap, PostGIS source context, layer
  toggles, variant comparison, map-object inspection, no-route diagnostics, a retained metric
  schematic and a latest-completed-run demo endpoint. Complete variants render through the strict
  R7 seven-type adapter; the supplied incomplete file intentionally uses the internal preview.
- map-first result UX with floating inspector/results islands over one uninterrupted map, the real
  imported filename in the toolbar and a collapsible navigation rail. Technical stack, version,
  team and Swagger live on the separate `/system` page instead of the work screen.

External decisions before an unconditional official P0 claim:

- organizer approval that the passing full 2× topology gate represents the hidden maximum;
- organizer clarification of `railway` and missing reconstruction attributes;
- production-like Ubuntu 22 host rehearsal only if clean ephemeral CI is not accepted.

## Developer: next vertical slice

Continue from `domain/export`; do not replace the immutable run/job contract. R7 component costs,
length, score/rank, strict seven-type serialization, independent whitelist/type/reference
validation, feature-by-feature preflight and incremental Jackson download are already integrated.
R7 is closed against the normative appendix tables and formulas. Section 10.8 explicitly calls its
numbers illustrative and differs by 19/33 RUB; keep the golden expectations derived from tables
4.1, 5.1, 8 and 9. Complete variants are fetched per `variant_id` and rendered from the strict
official output model. Dense constraint lookup uses adaptive JTS STRtree and is locked by a
1,001-constraint/20,000-query fixture. The project-owned full 2× topology gate passes on Ubuntu 22
/ Java 11 with 34/34 demands; obtain organizer approval before calling it the official maximum.
R8 depth is separately complete for published rules. Do not fold unresolved `railway` semantics
into either the R6 or R8 claim.
Repeated imports are idempotent by `(contract_version, raw_sha256)` and concurrent duplicates are
resolved by PostgreSQL `ON CONFLICT`; preserve this invariant in all future import changes.
Job execution is bounded by `HEATROUTE_JOB_CONCURRENCY` (default 2, hard maximum 16) and active
leases are renewed every minute. Use `docs/operations/R9_ACCEPTANCE.md` for scale evidence; do not
call the probes themselves a pass until their generated measurements are archived.
Manual run `35112046184` proves exact 3 GiB input and ≥500 MiB valid output on Ubuntu 22 / Java 11
under `-Xmx512m`. Final clean-stack run `35120982320` passes backend/web/integration; topology run
`35120995991` passes 288 features, 34/34 demands and three variants in 2:14.65 with 406,608 KiB
peak RSS. Do not conflate this with a VPS deployment, which remains explicitly deferred.
Do not mix MVT or extra formats into the remaining external acceptance gate.

## PM: tasks tomorrow

- include the published, CI-tested JSON Schemas from `docs/contracts` in the submission kit;
- confirm whether Ubuntu 22 is mandatory for judging even though the current demo VPS uses a
  newer Ubuntu release;
- supply or approve an official-like maximum-topology fixture and load-test environment;
- keep MVT and extra formats outside P0 until the external R9 decisions close;
- review every “complete” claim against `docs/ACCEPTANCE.md`, not old M-stage evidence.
- ask the organizer to resolve the supplied-dataset mismatch, especially `railway`, direct demand
  on connection points and the missing existing-network reconstruction attributes.

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
