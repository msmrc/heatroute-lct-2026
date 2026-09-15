# Implementation progress

Updated: 2026-09-15

> **Official-spec rebaseline:** the M0-M6 statuses below describe completion against the original
> internal HeatRoute specification. They do not mean compliance with the official LCT 2026 case
> specification received on 2026-09-15. The current gap assessment and delivery roadmap for PM and
> engineering are recorded in
> [`OFFICIAL_TZ_ROADMAP.md`](OFFICIAL_TZ_ROADMAP.md). Until that roadmap is completed, the project
> must not be described as ready for final competition submission.

## Milestones

| Milestone | Status | Evidence / next gate |
|---|---|---|
| M0 repository and infrastructure | complete locally | Compose, migration, readiness, worker smoke, web shell, CI definition and all local gates pass; first remote CI run remains |
| M1 ingestion and versions | complete locally | ING-01…12 and SEC-01…06 are covered by unit/live integration evidence; see `m1-evidence.md` |
| M2 rules, topology and validation | complete locally | GEO-01…14, TOP-01…10 and CRS-01…08 pass, including live building-to-network and manual revalidation; see `m2-evidence.md` |
| M3 routing vertical slice | complete locally | RTE-01…15, CST-01…10 and JOB-01…10 pass with live persistence; see `m3-evidence.md` |
| M4 web workspace | complete locally | Full API-backed project/import/scenario/map/results/jobs/catalog workspace and E2E-01…04/06 pass; see `m4-evidence.md` |
| M5 export and acceptance | complete locally | Exports, audit/metrics/logs, production web, security review, clean offline/restart rehearsal and final P0 pack; see `m5-evidence.md` |
| M6 requested engineering/data slice | complete locally | Secure Shapefile/GeoParquet, PostGIS MVT + MapLibre, validated shortcut optimizer, vertical profile and async pandapipes hydraulics; see `m6-engineering-evidence.md` |

## Decisions

- ADR-001: keep the specified modular-monolith stack; use the official FastAPI full-stack
  template only as a reference because SQLModel/JWT/Traefik do not match the required
  SQLAlchemy/PostGIS/Celery/cookie-session architecture.
- ADR-002: EPSG:4326 at the API boundary and a confirmed per-project metric CRS for computation.
- ADR-003: PostgreSQL is the durable job source of truth; Celery/Redis delivery is at-least-once.
- Contract clarifications are captured in ADR-004 before database migrations expand in M1.
- The routing core keeps heading in state, exposes neighbors lazily, checks the full corridor,
  and separates budget exhaustion from a completed no-route result.

## Commands and evidence

- `python scripts/validate_spec_examples.py` — exit 0 before implementation.
- `git init -b main` — repository initialized at `E:/job/_lct2026/heatroute_codex`.
- Exact package versions were checked against PyPI/npm on 2026-09-07.
- Container base tags and registry manifest digests were resolved before adding Compose.
- `uv lock` — succeeded after correcting the Celery/Kombu constraint from incompatible
  `redis-py 8.1.0` to `redis-py 6.4.0`; Python runtime installed under `E:/job/.tooling`.
- `pnpm install` — succeeded; store/cache configured under `E:/job/.tooling`.
- `pytest -m "not integration"` — 34 passed, 7 integration tests deselected.
- `pytest -m integration` — 8 passed against live PostGIS, Redis and Celery.
- `ruff check apps/api/src tests` — passed.
- `mypy apps/api/src` — passed, 32 files.
- `pnpm lint`, `pnpm typecheck`, `pnpm test` — passed (2 web tests).
- `pnpm build` — passed; bundle-size warning remains for MapLibre chunk splitting.
- `python -m heatroute export-openapi` — passed; generated
  `packages/api-client/openapi.json`.
- `docker compose config --quiet` — passed.
- `docker compose up --build -d` — passed; API, PostgreSQL, Redis and worker healthy,
  migration container exited 0, and web returned HTTP 200.
- `health/ready` returned `ready` with PostGIS 3.5 and Redis `ok`; database reported
  PostGIS 3.5.2 and Alembic revision `20260907_0002`.
- `python -m heatroute worker-smoke` — passed through the live Celery worker and verified
  database, PostGIS and Redis access.
- Migration `20260907_0002` persists scenarios, immutable revisions, calculation runs,
  deduplicated alternatives and transactional outbox events.
- `POST /api/v1/demo/runs` -> Celery A* -> independent corridor validation -> persisted
  WGS84 GeoJSON -> `GET /api/v1/runs/{id}` passed end to end. Idempotent replay returned the
  same run; same key with changed input returned HTTP 409.
- P0 baseline test counts were 118 unit, 20 live integration and 6 web tests; current extension
  counts are 129 unit, 22 live integration and 6 web tests and are recorded in
  `m6-engineering-evidence.md`.
- Browser smoke passed: the MapLibre workspace displayed a 2264.927 m route around the
  synthetic obstacle; removing the obstacle and rerunning displayed a distinct 1900.419 m route.
- The web workspace now follows the Mobbin Acctual visual system: readable product typography,
  a persistent left navigation rail, a large rounded work surface, status tabs, data tables,
  explicit validation states and a real route map. The last demo run ID is restored after reload.
- Browser QA covered the overview and routes list at `/?ui=acctual`; the persisted 2264.927 m
  obstacle-avoiding route, its 21,375 expanded states and its geometry status render from the API.
- `docs/ui/design-system.md` records the Acctual structure and Apple HIG-inspired typography,
  spacing, hierarchy, control, motion and accessibility rules. The web UI implements shared
  motion tokens, press feedback, short view continuity and a `prefers-reduced-motion` fallback.
- The non-demo workflow now exposes project create/list/get/optimistic PATCH, scenario create/get,
  immutable revision creation with `If-Match`, revision preflight and idempotent revision runs.
- Migration `20260907_0003` adds globally ordered `run_events`. Runs emit queued, started,
  progress, completed, cancel-requested, cancelled and failed events; polling supports reconnect
  through `after_sequence`.
- Cooperative cancellation is checked inside the grid search every 256 expanded states. A live
  integration run reached terminal `cancelled` instead of being reported as an execution error.
- Celery beat and the `outbox` worker queue continuously republish pending outbox rows. The live
  test forces the first broker send to fail, then verifies scheduler recovery, a completed run,
  a published event and at least two delivery attempts.
- Container HTTP smoke created a project/scenario/revision, passed preflight with the explicit
  engineering-check limitation, and completed a 2264.927 m revision run through the worker.
- CI now sets the repository `src` layout through `PYTHONPATH=apps/api/src`, matching Docker and
  pytest, so CLI OpenAPI and worker-smoke commands resolve the application consistently.
- Migration `20260907_0004` adds Dataset, immutable DatasetVersion, RawArtifact,
  DatasetVersionArtifact and DatasetImport provenance. Its complete offline PostgreSQL DDL
  generation passes.
- `POST /projects/{id}/datasets/uploads` now streams GeoJSON/JSON, GeoPackage and CSV into a
  SHA-256-addressed local artifact store with a 100 MiB limit. Original filenames are display-only,
  traversal components are removed, empty/unsupported uploads are rejected and identical bytes
  reuse the same workspace-scoped artifact record. `GET /imports/{id}` exposes the durable
  uploaded state without claiming inspection or publication.
- The DatasetVersion transition map enforces the documented upload-to-publication sequence and
  terminal failure/rejection/cancellation states. Unit coverage verifies deduplication, cleanup of
  rejected temporary files, filename normalization, size limits and invalid transition rejection.
- Migration `20260907_0005` adds durable ImportReport rows and links them to DatasetVersion.
  Upload now creates an inspection job plus transactional outbox event; the `ingest` worker checks
  the local artifact, persists the report and advances the version to `mapping_required`.
- GeoJSON and GeoPackage inspection uses the pinned Pyogrio/GDAL stack. It records layers,
  geometry types, CRS, fields, cheap feature counts/extents and a bounded five-row/property sample.
  Unit tests cover a content-addressed extensionless GeoJSON and a generated two-layer GeoPackage.
- Inspection rejects mismatched declared/actual drivers, invalid content-address keys, symlinked or
  missing artifacts, more than 64 layers and more than 256 fields per layer. GDAL plugin loading,
  Python VRT and SQLite extension loading are disabled for the inspection call; temporary GDAL
  data stays below the artifact root. OS-level worker sandboxing remains a production hardening gate.
- `GET /imports/{id}/inspection`, `GET /imports/{id}/report` and
  `GET /projects/{id}/dataset-versions` now expose scoped inspection/provenance state. A pending
  report returns an explicit `INSPECTION_NOT_READY` conflict instead of an empty success.
- CSV inspection uses the standard-library parser with UTF-8, bounded fields/cells, a restricted
  delimiter set, strict row width and duplicate/blank header rejection. It reports tabular fields
  but deliberately leaves geometry and CRS unresolved until mapping.
- Migration `20260907_0006` adds MappingProfile and immutable MappingProfileVersion rows, with
  optimistic `If-Match` revision checks for profile reuse. `PUT /imports/{id}/mapping` verifies the
  selected inspected layer and every referenced source field before moving the version from
  `mapping_required` to `ready_to_validate`.
- CSV mappings require distinct, explicit X/Y columns plus a parseable source CRS. Vector mappings
  reject a CRS that contradicts the inspected layer. The transform schema accepts only `rename`,
  `trim`, `parse_decimal`, `enum_map`, `unit_convert`, `date_parse` and `constant`; target keys use
  bounded lower_snake_case and approved unit pairs require their exact Decimal factor.
- Pytest temporary data is explicitly rooted at `E:/job/.tooling/pytest-heatroute`, keeping new
  test artifacts off `C:` in accordance with the machine storage policy.
- Migration `20260908_0007` adds persisted staging/quarantine rows, immutable canonical features,
  PostGIS GiST indexes, source provenance and a publication policy on DatasetVersion.
- Validation reuses Pyogrio's bounded raw batches, Shapely's full geometry validity checks and
  Pyproj transformers with traditional GIS axis order, network access disabled, ballpark
  operations forbidden and `only_best` required. GeoPandas and a separate ETL framework were not
  added because the existing lower-level libraries cover streaming reads and geometry/CRS work;
  HeatRoute still owns its domain mapping, issue and publication semantics.
- `POST /imports/{id}/validate` and `POST /imports/{id}/publish` use the transactional outbox and
  the existing `ingest` Celery queue. Validation stages at most one million rows in 500-feature
  batches, applies only the whitelisted transforms, rejects non-finite/out-of-range coordinates,
  enforces per-kind geometry types and never runs implicit MakeValid or `buffer(0)` repairs.
- Duplicate source IDs deterministically quarantine every occurrence rather than selecting a
  last-write winner. Counts satisfy `accepted + quarantined + rejected == read == total`; issue
  payloads retain source row, field and source ID references with a bounded report size.
- Atomic publication exposes no canonical feature until the complete accepted snapshot commits.
  Rejected rows block publication. Quarantined rows require both an explicit confirmation and a
  persisted coverage-limitations statement; only accepted staging rows are copied. Repeating
  publish is idempotent and does not duplicate canonical rows.
- Canonical feature APIs return WGS84 GeoJSON, stable cross-version logical IDs, version IDs,
  source layer/ID/type, quality flags, mapped attributes and bounded raw properties. Both the
  version-scoped feature list and `GET /features/{id}` enforce workspace/publication scope.
- Compose bind-mounts the ignored `artifacts/` directory from the repository on `E:` into the API
  and worker. Host-side integration clients and container workers therefore resolve the same
  content-addressed raw files; upload, inspection, mapping and byte deduplication pass end to end.
- Post-redesign web gates passed: `pnpm --filter @heatroute/web lint`, `typecheck`, `test`
  (2 tests) and `build`. Browser verification created a real 2264.927 m run, rendered its
  obstacle-avoiding geometry, and opened its persisted run details (21,375 expanded states).
- `.github/workflows/ci.yml` validates lint, typecheck, unit tests, web build, generated
  OpenAPI, Compose integration tests and worker smoke. YAML parsed locally; the first remote
  run requires publishing the repository.
- Migrations `20260908_0008` through `20260908_0011` add durable layer provenance, structured
  import diagnostics and server-side authentication sessions. HttpOnly cookies, scrypt password
  hashes, CSRF checks, viewer/editor/admin roles and workspace-scoped resource queries now cover
  the M1 access boundary; demo bypass is development-only and production rejects it.
- Canonical per-kind mappings now reject unknown or missing required fields and validate enums and
  non-negative measurements. Missing edge endpoints and other typed references are quarantined
  against the latest published project versions instead of creating ghost objects.
- `GET .../layers`, `GET .../diff` and `GET /projects/{id}/quality` expose selected/unselected
  layers, semantic version changes and explicit unknown/partial/synthetic coverage. Structured
  reports include source references, missing distributions, CRS/topology/coverage diagnostics and
  an explicit repair log.
- A live alternate-schema delivery renamed fields and converted millimetres to metres while
  retaining the same logical feature and an `unchanged` diff; a two-layer GeoPackage retained its
  unselected layer. Full M1 evidence is recorded in `docs/implementation/m1-evidence.md`.
- Migration `20260908_0012` adds project-scoped RuleProfile heads and immutable RuleProfileVersion
  rows. Profiles are structurally validated, hashed, protected by optimistic `If-Match` revisions,
  pinned into scenario snapshots and integrity-checked during preflight.
- The live worker now independently revalidates a computed swept corridor with the selected rule
  definition and persists its ID, revision, findings and unverified length in the alternative's
  validation report.
- Explicit NetworkGraph construction and candidate screening cover cycles, ambiguous XY crossings,
  circuit isolation, dangling endpoints, permission/date/capacity rejection and net-vs-gross
  reserve semantics. Exact corridor predicates cover named portals, local entry gates, polygon
  holes, MultiPolygons and strict/exploratory coverage behavior. Detailed evidence and remaining
  integration gates are in `docs/implementation/m2-evidence.md`.
- Migration `20260908_0013` adds the immutable `versions_snapshot` to CalculationRun. Scenario
  preflight now resolves exact published DatasetVersions, verifies project/CRS scope, materializes
  their WGS84 canonical features into the confirmed metric CRS and reports topology errors.
- Canonical hard exclusions and portal/coverage search exclusions feed grid expansion as exact
  Shapely geometries and are rechecked independently on the final swept corridor. A live fixture
  proves that a published building changes a straight calculation into a valid computed detour.
- Candidate IDs, planning date, requested load and candidate limit are revision inputs. The live
  fixture screens an explicit 500 kW candidate for a 250 kW request and persists selected/rejected
  decisions and reasons in the hashed run manifest.
- M2 is complete locally. `building_to_network` resolves an eligible candidate to its explicit
  network node and a named target gate to the actual search endpoints, then includes the bounded
  entry connector in the persisted centerline, corridor and length.
- Manual GeoJSON edits are revalidated by
  `POST /scenario-revisions/{id}/validate-route`; invalid edits return structured WGS84 conflict
  geometries. Named portal crossings emit one quantity event regardless of grid-step count.
- The acceptance matrix is fully exercised: GEO-01…14, TOP-01…10 and CRS-01…08. Invalid topology
  edges are quarantined from the usable graph; touch policy and true CRS transformation have
  dedicated regressions.
- Compose backend rebuilds now use `--provenance=false --sbom=false` during rapid local iteration;
  this avoids the pathological BuildKit attestation delay without changing image contents.
- Migration `20260908_0014` adds immutable cost catalogs, calculation passports, per-alternative
  quantities/costs, durable job leases, retry metadata and progress fields.
- M3 routing now uses a lazy GraphProvider with heading/mode/ordered-waypoint state, state/time/
  memory budgets, shortest/estimated-cost/least-unverified objectives, real penalized alternatives,
  geometric deduplication, configured refinement and independent final validation.
- Costing builds non-overlapping construction segments, explicit paired-pipe quantities, unique
  crossing/tie-in events and Decimal totals. Missing rates remain visible as partial; synthetic
  catalog status survives JSON/CSV/HTML export.
- Runs persist runtime library versions, assumptions, finding summary, search completion, cache
  provenance and optimality scope. Same-manifest runs clone validated results; changed cost/rule/
  dataset hashes cannot hit the old cache.
- Jobs expose progress, renew leases, recover stale attempts through the transactional outbox,
  reject configured queue overload with HTTP 429 and support both SSE `Last-Event-ID` reconnect and
  ordered polling fallback.
- The CLI generated `artifacts/m3/run-passport.json`; the first measured routing benchmark is in
  `artifacts/benchmarks/m3-routing.json` and summarized in `m3-evidence.md`.
- Final container HTTP smoke `757f22cc-6bbe-4ec8-9b98-ab999d1da19a` completed through the
  published API, transactional outbox and Celery worker with `routes_found`, one independently
  validated 1,132.394 m alternative, `search_completion=complete` and `grid-v2`.
- CLI `start-run` independently submitted the same immutable revision and waited for persisted run
  `d193a5a8-b620-4acd-8e9a-1cf047053492` to finish with `routes_found`.
- M4 is complete locally. The generated typed client, lazy route modules and API-backed pages now
  cover projects, settings, imports, datasets, quality, scenario revisions, map layers,
  alternatives, findings, passports, history, jobs and rule/cost versions.
- The browser workflow published both GeoJSON and two-layer GeoPackage inputs, used the GeoJSON
  version in a calculation, exercised optimistic revision updates, live SSE progress/reconnect,
  cancellation and stale-run indicators, and verified all four server exports.
- Adding a third forbidden polygon created scenario revision 5 and changed the persisted route
  from 2,264.9 m to 2,380.9 m in run `afc347b7-3beb-4111-9275-fc45cb11dd94`; previous runs remain
  addressable through the deep-linked history.
- Final Playwright QA passed at 1440×900, 1280×800 and 640×800 in light/dark themes, including
  keyboard focus and the narrow scenario/inspector drawers. The session had zero console errors
  and no HTTP 4xx/5xx responses. Complete evidence is in `m4-evidence.md`.
- M5 adds GeoJSON/JSON/CSV/HTML result exports to the final acceptance path, spreadsheet-formula
  neutralization and HTML escaping with unit/live regressions, plus workspace-scoped durable audit
  events, JSON allowlist request logging and admin-only aggregate HTTP metrics.
- The web image now uses a Vite build stage and pinned-digest non-root Nginx runtime with
  same-origin API proxy and security headers. Final Playwright QA opened the passport and all four
  same-origin exports with zero console errors; a canvas overflow that intercepted clicks was
  fixed during the run.
- A clean rehearsal in `E:/job/.tooling/heatroute-m5-prod-rehearsal-20260908-1445` used a different
  directory, fresh volumes, `--no-build --pull never` and an internal network. Seeded run
  `57522895-82e0-4b98-99e4-910c1f0b9919` succeeded with two alternatives and four exports,
  external DNS was unavailable by design, and the exact result survived full service restart.
- Production settings reject demo mode and the development session secret. The repository secret
  audit passed 168 text files, and image environment/history inspection found no application
  secret. Complete final evidence is in `m5-evidence.md`.
- The requested M6/M7 slice is implemented: secure zipped Shapefile and GeoParquet ingestion,
  workspace-scoped PostGIS MVT, MapLibre vector sources for published layers, a fully revalidated
  shortcut optimizer, preserved source Z ordinates, explicit vertical profile/crossing reports,
  real construction-method contracts and durable worker-based pandapipes hydraulics.

## Environment

- All new uv, pnpm, npm and Playwright caches/runtimes must live below
  `E:/job/.tooling`; do not accept their Windows defaults on `C:`.
- Docker Desktop was updated in place from 4.88.1 to build `4.89.0.238018` under
  `E:/job/.tooling/apps/DockerDesktop`. The official SHA-256 and Docker Inc. signature were
  verified before installation. The inaccessible AF_UNIX runtime directory was moved to the
  recoverable `run-quarantine-20260908-1015` directory; Docker recreated it cleanly and the engine
  now runs normally. The WSL disk, images, volumes and `C:/Users/dragon/.docker` junction to `E:`
  were preserved.
- Host ports use PostgreSQL `55432` and Redis `56379` to avoid collision with existing local
  services. Docker-internal ports remain `5432` and `6379`.
- The default Compose file is a local-development profile and explicitly disables PostgreSQL
  `fsync`, synchronous commit and full-page writes to avoid pathological Docker Desktop volume
  latency. This trades crash durability for fast local startup and must not be reused as a
  production database configuration.
- Host Python is 3.13.7; the project runtime is pinned to Python 3.12.11 and should be
  provisioned through uv with explicit `E:` paths or through the pinned container.
- GNU Make is not installed on the host; `scripts/dev.ps1` is the Windows entrypoint and
  Make targets are a compatibility facade for CI/Linux.

## Official-TZ Java rebaseline (started 2026-09-15)

The complete organizer task invalidates the old completion percentage. The active plan is now
R0–R9 in `OFFICIAL_TZ_ROADMAP.md`; old M0–M7 evidence remains useful regression evidence only.

- [x] R0: official gap audit and team roadmap.
- [x] R0: ADR-005 selects Java 11, Spring Boot 2.6.3 and a side-by-side cutover strategy.
- [ ] R1: Java foundation, PostgreSQL/PostGIS readiness, Swagger, migrations and durable jobs.
  Foundation, pinned Java 11 / Spring Boot 2.6.3 runtime, Liquibase, readiness, Swagger,
  non-root image, Java CI and a Compose 1.29.2-compatible clean-start smoke are verified.
  Durable asynchronous calculation jobs and the frontend compatibility adapter remain open.
- [ ] R2: streaming import and exact seven-type official input contract.
  The service streams features without materializing the collection, validates all seven input
  types and their geometry/property contracts, detects duplicate IDs and broken typed references,
  records SHA-256 and reports, and persists valid features in both EPSG:4326 and EPSG:32637 with
  GiST indexes. The minimal eight-feature fixture and invalid semantic fixture pass. Extended and
  large-file fixtures, replay/deduplication policy and measured memory evidence remain open.
- [ ] R3: existing-network topology, automatic candidates and tie-in feasibility.
- [ ] R4: multi-OKS branched routing and independent validation.
- [ ] R5: flows, official diameters, maximum continuous lengths and reconstruction to source.
- [ ] R6: exact restrictions and special crossings.
- [ ] R7: official costs, score, three alternatives and strict seven-type export.
- [ ] R8: optional depth rerouting task after mandatory 2D completion.
- [ ] R9: 3 GB/500 MB/16 GB/50-user evidence, Ubuntu 22 clean deployment and submission kit.

The VPS remains on the last verified Python image until the Java parity gates in ADR-005 pass.
The next executable step is durable asynchronous jobs plus the R3 existing-network topology gate.
