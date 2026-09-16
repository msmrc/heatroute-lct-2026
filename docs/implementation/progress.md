# Current implementation progress

**Updated:** 2026-09-16

## Active baseline

The production path is Java-only. `apps/api` contains Java 11 / Spring Boot 2.6.3; default,
offline and VPS Compose use that image. Python application code, dependencies, migrations, tests
and runtime services were removed. The frontend calls only the current official Java endpoints.

## Verified in this cutover

- Maven verifies 76 Java tests plus opt-in scale probes (the production/CI gate remains pinned to
  Java 11).
- Web ESLint, TypeScript, Vitest (9 tests) and production Vite build pass.
- Compose starts PostGIS, Java API and web; all three become healthy.
- `/api/v1/health/ready` reports PostGIS ready.
- Java OpenAPI is saved as `packages/api-client/openapi.json`.
- Browser smoke at 1264×712 shows the official workspace, Java-ready status and upload action
  without a blank page or framework error overlay.
- Live fixture import `63d30d86-e1db-4d03-b839-0300f583df4f` persisted as `valid` with eight
  features across the seven required types.
- Durable topology job `0afabe99-408e-43d4-84b5-148bc8835bb8` completed on attempt 1 and returned
  a valid topology with two deterministic tie-in candidates.
- GitHub Actions run `35073870126` passed all backend, web and integration gates for commit
  `e47cd72`; integration uploads the official dataset and waits for a real calculation run.
- GitHub Actions run `35075903157` passed backend, web and integration gates for the visual
  result viewer and latest-completed-run API in commit `becdaca`.
- Commit `e47cd72` is deployed to the VPS. The production Compose project contains only `db`,
  Java `api`, `web` and `gateway`; all four services are healthy. The former Python API,
  worker/scheduler, migration container and Redis were removed from the running project.
- External HTTPS smoke returned HTTP 200, Java readiness reported PostGIS `ok`, and the VPS
  official fixture import persisted eight valid features. Its topology job completed on attempt 1
  with two deterministic tie-in candidates.
- Commit `becdaca` is deployed to the VPS after a PostgreSQL backup. All four services are healthy;
  the public latest-run endpoint returns the completed 17-demand official calculation with two
  variants and the browser viewer renders it without console errors.

## Supplied dataset received on 2026-09-16

- The organizer GeoJSON contains 144 features: 17 demand connection points, 88 restrictions,
  29 network sections, 9 chambers and one source.
- It materially differs from the published input table: numeric IDs, no `oks_future`, no `oks_id`,
  no existing flow/upstream links and no chamber diameters.
- Input contract v2 now has a named compatibility profile with explicit warnings; the strict
  official profile remains available.
- The byte-identical organizer file is now the only tracked geodata at
  `datasets/official/lct-2026.geojson`; synthetic GeoJSON fixtures and the old demo pack were
  removed. Narrow invalid-input cases are constructed inline in unit tests.
- Topology analysis now falls back to geometric source connectivity when the whole dataset omits
  upstream links, while preserving explicit-link validation for the strict profile.
- Full findings and PM questions are in `SUPPLIED_DATASET_AUDIT.md`.
- Production verification passed on the untouched file: import `valid`, 144 features, zero blocking
  errors, 323 explicit compatibility warnings. The durable topology job completed on attempt 1
  with zero issues and 204 deterministic candidates for the 17 demand points.
- Repository and production dataset cleanup is complete: the synthetic imports and the obsolete
  pre-compatibility invalid import were removed after a database backup. Production retains one
  valid import with the official SHA-256.
- Production calculation run `660c203d-287a-4fb7-bc8a-eb3debe5f1c0` completed on attempt 1 for
  all 17 demands. The independent variant validly connected 16 and preserved one explicit
  `NO_NON_CROSSING_ROUTE`; the preferred shared variant connected all 17 with 21 sections,
  3,448.671 m total length and zero validator issues.
- The official workspace now defaults to a MapLibre GL GIS view: CARTO vector basemap, calculated
  route/nodes transformed from EPSG:32637, and bounded WGS84 source layers from PostGIS. Users can
  toggle the basemap, existing heat network, restrictions and result, inspect map objects, switch
  route variants, or return to the EPSG:32637 engineering diagram. The bounded
  `GET /api/v1/official/imports/{id}/map` endpoint caps a viewport at 10,000 features and reports
  truncation. `GET /api/v1/official/runs/latest` powers the dataset-independent “open demo” action.
  Complete variants render from the strict seven-type R7 adapter; the supplied incomplete dataset
  intentionally uses the internal R4 preview because official reconstruction fields are absent.
- The result viewer now uses a map-first planning workspace: route variants and layer controls sit
  on the map, while selected-object details and route totals live in separate floating islands over
  one uninterrupted map canvas. The toolbar shows the actual imported filename and the left
  navigation collapses to an icon rail with persisted state. Framework/runtime status, theme
  controls and developer-only labels were removed from this flow; stack, version, team Dragons and
  Swagger moved to `/system`.
- The map now uses the same MapLibre 5.24 renderer and vector CARTO Positron treatment as the local
  GdeBenzin project: warm background, amber road hierarchy, calm water/parks and Russian labels.
  OpenLayers and the raster OSM tile path were removed from the web dependency graph. Four
  persistent layer buttons and the map legend remain consolidated in one compact menu;
  restrictions start hidden and layer visibility changes in-place without rebuilding the map.

## Roadmap truth

## UX update — direct upload and diagnostics (2026-09-16)

- Valid GeoJSON uploads now start the calculation immediately. The technical import/report screen
  is skipped in the happy path; one loader covers validation, queueing and calculation until the
  map result is ready.
- The result validation metric is clickable and opens all import warnings in a scroll-contained,
  keyboard-accessible modal with code, message, object and field context.
- Enabled buttons and links expose a pointer cursor. The sidebar toggle is contained within the
  navigation rail and no longer overlaps the dataset icon in either sidebar state.
- Web verification: TypeScript, ESLint, production build and 9 Vitest tests pass. CI run
  `35089560867` passed web, Java backend and integration jobs. VPS commit `407c0a7` is healthy.

## R4/R6 obstacle-aware checkpoint (local, not deployed)

- The route planner now searches real polylines around buffered forbidden geometry instead of
  accepting straight endpoint links. Existing OKS clearance is selected from the planned DU
  (5/7/9 m), and park, social area, prohibited site, water and railway constraints participate in
  construction as hard obstacles.
- Road, tram, gas, power and independent heat-network crossings are split into reproducible
  `base`/`special` sections. The independent final validator rechecks the complete resulting
  polyline, crossing angle and unrelated constraints after path simplification.
- One official-dataset run deterministically returns `independent`, `shared` and `diverse`
  strategies. The preferred independent variant connects all 17 OКС; strategies that cannot
  connect an object without violating constraints retain the valid partial network and explicit
  `NO_NON_CROSSING_ROUTE` reason.
- R5 bottom-up sizing is now applied to every accepted new-network tree. Result edges expose
  calculated `flow_tph` and DU to the API and visual inspector. Continuous-length violations are
  preserved as explicit sizing issues; the planner does not yet increase/split DU to resolve them.
  At this historical checkpoint propagation into the existing network and reconstruction were
  still missing; the following R5 checkpoint supersedes that limitation.
- A read-only local demo API can serve the result produced directly from the sole tracked official
  GeoJSON. The UI was browser-checked at `http://localhost:5174`: all three strategies render on
  the vector map, switching works, and no console error or Vite overlay is present.
- This checkpoint has intentionally not been deployed to the VPS. Deployment is deferred until an
  explicit user command.

## R5 sizing and reconstruction checkpoint (local, not deployed)

- New-network sizing now chooses the minimum official DU satisfying both flow and uninterrupted
  length. It promotes DU when the current row's length is exhausted and never resets unchanged-DU
  length at an intermediate chamber.
- Added tie-in flow is traced through explicit `upstream_object_id` chains to a source. Multiple
  tie-ins are summed on common existing sections; `LengthIndexedLine` splits the target segment at
  the projected tie-in so only the upstream part participates.
- Existing sections and used chambers are emitted as reconstruction only when the resulting flow
  requires a DU larger than the supplied baseline. Missing direction, existing flow or chamber DU
  produces `RECONSTRUCTION_INPUT_UNAVAILABLE`; no baseline is inferred.
- The map/API expose reconstruction sections, chambers, old/new DU and added/resulting flow. The
  supplied organizer file displays one grouped Russian warning because its documented compatibility
  profile lacks reconstruction inputs.
- Verification: 56 Java tests pass locally in Java-11 compatibility mode, including every flow and
  length boundary of all 18 catalog rows, partial tie-in, overlapping loads, chamber reconstruction
  and planner integration. Nine web tests, ESLint, TypeScript and production build pass. Browser
  smoke at `http://localhost:5174` confirms 17/17 connected OKS, three variants, zero calculation
  errors and zero console warnings/errors.

## R7 costing checkpoint (local, not deployed)

- Exact official rates now cover base/special new-network sections, reconstruction by required DU,
  new chambers in the 3/5/8/12 million bands, 5 million per independent tie-in, reconstructed used
  tie-in chambers and the per-OKS unconnected penalty.
- Every variant exposes component totals, new/reconstruction/combined length and calculated cost.
  Contract-complete variants receive official score and deterministic rank; variants whose
  reconstruction baseline is unavailable expose known cost but deliberately withhold score/rank.
- The local UI displays the known cost and explains why the final score is unavailable for the
  supplied organizer file. The checkpoint passes 59 Java tests, 10 web tests, lint, typecheck,
  production build and browser smoke without console errors.

## R7 official-output checkpoint (local, not deployed)

- A dedicated adapter serializes every complete ranked alternative into one GeoJSON containing
  only `heat_network`, `tie_in`, `heat_network_reconstruction`, `heat_chamber`,
  `heat_chamber_reconstruction`, `technical_node` and `variant_summary`.
- Output IDs are globally unique across alternatives. New-network start/end references resolve to
  scoped tie-ins, chambers or technical nodes; EPSG:32637 calculation geometry is converted to
  WGS84.
- An independent contract validator enforces exact per-type field whitelists, required scalar
  types, WGS84 geometry, unique IDs, network references and one summary per variant. Tests prove
  component-sum equality, multi-variant ID isolation and rejection of incomplete calculations.
- `GET /api/v1/official/runs/{runId}/export` returns `application/geo+json` for a complete result.
  The organizer file remains intentionally non-exportable and returns
  `409 OFFICIAL_EXPORT_INCOMPLETE`, because its reconstruction baseline is absent.
- The workspace exposes the download only when a variant has complete economics and rank. For the
  organizer demo it shows a disabled, explanatory action rather than downloading a partial file.
- Complete ranked alternatives are requested with `variant_id` and rendered on the map from the
  same strict output types used by download. `variant_summary` is correctly omitted from spatial
  layers. The internal nodes/edges conversion remains only as a preview fallback for the supplied
  incomplete dataset or a transient official-layer request failure.
- Current verification after the selected-tie-in persistence checkpoint: 76 Java tests and 13 web tests, ESLint,
  TypeScript, production build and local browser smoke all pass.
- Export performs a feature-by-feature preflight and then writes with Jackson `JsonGenerator`; the
  full output tree is not retained. The all-seven-type fixture includes existing-chamber
  reconstruction. Spring MVC streaming uses a bounded 2–16 thread executor with a 64-request queue
  and 15-minute timeout instead of the unbounded fallback. The writer/validator now has measured
  500 MiB evidence on Java 11; full-calculation scale and the 50-user gate remain separate checks.

## R6 full 2D boundary matrix (local, not deployed)

- Every published 2D constraint row now has exact-value coverage plus positive, exact-boundary and
  negative behavior tests: park, social area, prohibited site, water, three OKS DU bands,
  road/tram crossings and gas/power/independent-heat-network crossings.
- A route exactly on the minimum-clearance buffer boundary is accepted, while a 0.01 m intrusion
  is rejected. The previous prepared-geometry predicate treated legal tangential contact as a
  violation; the blocked buffer now excludes only a 1 µm numerical boundary epsilon and retains
  the indexed prepared-geometry search path.
- Road and tram accept exactly 45° and reject just below it; their 3 m extensions produce a 6 m
  special span around a linear crossing. Utility crossings produce the required 2+2 m span, and
  the final validator rejects a crossing omitted from special sections.
- `railway` is tested separately as the supplied-dataset compatibility rule (1.5 m forbidden
  clearance), not represented as a published official row while organizer clarification is open.
- Full backend verification: 75 tests, zero failures; the official dataset routing case retains
  the prepared-geometry performance path.

## R4 dense-geometry lookup checkpoint (local, not deployed)

- Candidate segment checks now use an adaptive JTS `STRtree`: the small organizer dataset retains
  the lower-overhead linear prepared-geometry path, while dense constraint sets query only
  envelopes intersecting the candidate segment or navigation corridor.
- A deterministic fixture builds 1,001 constraints, proves indexed and linear decisions identical
  for blocked and clear segments, verifies that only the nearby object is returned and completes
  20,000 indexed checks inside a five-second budget.
- This closes the missing dense lookup primitive, not the geometry-complexity part of R9. Byte-size
  input/output boundaries and the exact Ubuntu 22/docker-compose 1.29.2 environment are now
  measured separately; maximum-topology route quality remains open.

## R2 deterministic replay checkpoint (local, not deployed)

- Imports are now idempotent for the same input contract and raw SHA-256. A repeated upload returns
  the existing durable import and does not reload identical features into PostGIS.
- PostgreSQL enforces the invariant with a unique `(contract_version, raw_sha256)` index;
  `INSERT ... ON CONFLICT DO NOTHING` resolves concurrent uploads without a check-then-insert race.
- Unit coverage proves both an ordinary replay and the concurrent-conflict winner path. The exact
  3 GiB streaming boundary is measured separately; representative maximum-topology work remains.

## R9 bounded-worker preparation (local, not deployed)

- Durable jobs no longer depend on one unbounded synchronous scheduler call. A dedicated executor
  runs a configurable, hard-clamped 1–16 calculations (default 2), while PostgreSQL `SKIP LOCKED`
  remains the only claim authority.
- Active jobs renew their five-minute lease every minute. Tests prove the concurrency bound and
  heartbeat behavior, preventing a long calculation from being reclaimed and executed twice.
- `scripts/r9-generate-byte-boundary.mjs` and `scripts/r9-concurrency.mjs` provide reproducible
  3 GiB transport and 50-user probes; `docs/operations/R9_ACCEPTANCE.md` states exactly what each
  probe proves and what evidence is still missing. The 3 GiB/500 MiB byte-boundary workflow passed;
  the 50-user measurement is now enforced by the clean-stack CI gate.

## R9 Ubuntu 22 / Compose 1.29.2 gate

- CI run `35110318718` passed on a clean `ubuntu-22.04` runner using the checksum-pinned official
  `docker-compose 1.29.2` binary, not the modern Compose plugin.
- The job built the pinned Java 11/PostGIS/web images from a clean checkout, applied Liquibase,
  imported the organizer file, calculated all 17 demands, validated the UI/API contracts and
  stopped the stack cleanly. VPS deployment remains intentionally unchanged.
- Run `35112362689` passed the restart-recovery assertion: after the real 17-demand calculation,
  CI restarted the API container and read the same completed result from PostgreSQL. The assertion
  remains mandatory in every integration run.
- Local 3 GiB parser preflight on commit `915d42f` passed under `-Xmx512m`: 4,308 ms and
  42,005,872 bytes reported peak heap. This is recorded in `R9_INPUT_SCALE_EVIDENCE.md`; the manual
  Java 11/Ubuntu 22 run `35111560434` also passed with 40,650,752 bytes peak heap and 357,272 KiB
  maximum process RSS.
- The extracted production stream writer and exact validator passed a local 524,781,467-byte
  output probe under the same 512 MiB heap cap in 6,386 ms. Clean Ubuntu 22 / Temurin Java 11 run
  `35112046184` repeated it in 6,942 ms with 104,260,560 bytes reported peak heap and 267,096 KiB
  maximum process RSS. The same run repeated the exact 3 GiB input probe and passed.
- Clean-stack run `35112362689` accepted 50 simultaneous organizer-file imports in 3.674 seconds;
  p95 response latency was 3,614 ms and all responses resolved to one durable import ID. This
  proves 50 concurrent public API sessions and the deduplication race, not 50 simultaneously
  executing heavy calculations. Backend, web, integration, real calculation and restart recovery
  all completed successfully in the same run. Exact evidence is in `R9_CONCURRENCY_EVIDENCE.md`.
- Final checkpoint run `35113595198` passed all Java 11, web and integration gates after adding the
  selected-tie-in persistence regression; it repeated the 50-user race, all-demand calculation and
  restart recovery successfully.

- R0 — complete: official gap audit, Java decision and team roadmap.
- R1 — complete for current single-process foundation: Java runtime, PostGIS readiness, Liquibase,
  Swagger, durable PostgreSQL job state, claim/lease/cancel/recovery, Docker and CI.
- R2 — functionally implemented for the current contract fixture, including deterministic
  contract+SHA replay/deduplication and the exact 3 GiB streaming boundary; broader
  geometry-representative fixtures remain acceptance work.
- R3 — functionally complete: topology validation, chamber rule, deterministic candidates, line
  splitting and adaptive dense-constraint lookup. Selected tie-in target IDs are part of every
  immutable variant and persisted in the run JSON; maximum-scale evidence belongs to R9.
- R4 — functional obstacle-aware checkpoint: immutable all-demand runs, independent/shared/diverse
  strategies, actual polyline search, simplification, partial no-route, an independent validator
  and GIS/result viewer. Dense constraint lookup is indexed; end-to-end maximum-scale performance
  and broader diversity/quality evidence remain.
- R5 — functionally complete for contract-complete input: bottom-up flow/DU sizing, automatic
  continuous-length promotion, upstream propagation, partial/common-section reconstruction and
  chamber reconstruction are covered by focused tests. The supplied organizer file cannot produce
  reconstruction because its existing-network baseline and direction fields are absent.
- R6 — complete for the published mandatory 2D table: dynamic OKS buffers, hard forbidden zones,
  special crossings and final validation have exact-value and positive/boundary/negative coverage.
  The supplied `railway` alias remains conservative pending organizer clarification; vertical
  depth rules belong to optional R8.
- R7 — complete for mandatory 2D: component costing, length, score/rank, all seven output types,
  independent validation, incremental download and official-output map rendering are integrated.
  The section 10.8 illustrative-number discrepancy is documented and the normative arithmetic is
  locked by a golden test.
- R8 — not implemented; optional after mandatory 2D.
- R9 — substantially closed: exact 3 GiB input and 500 MiB valid-output boundaries pass on Ubuntu
  22 / Java 11 with a 512 MiB heap; 50 concurrent API users and clean Compose 1.29.2 deployment are
  measured in CI. A representative maximum-topology calculation and production-like Ubuntu 22 host
  rehearsal remain acceptance items; the current VPS is intentionally not changed.

## Next change

Close the remaining R9 evidence: representative maximum-topology/full-calculation scale and a
production-like Ubuntu 22 host rehearsal. Do not start depth, MVT or additional file formats before
that mandatory gate.

Older `m1-evidence.md` … `m6-engineering-evidence.md` are historical prototype records only.
The current cross-check against all three organizer artifacts is in `OFFICIAL_ALIGNMENT_AUDIT.md`.

## Workstation note

After the latest Windows reboot, local Docker Desktop fails during startup on a stale internal
AF_UNIX socket. No project volume or Docker data was reset or deleted. CI and the VPS deployment
are green, so this is a workstation repair item rather than an application blocker. Diagnose it
separately before relying on local Compose; do not use factory reset or relocate Docker data from
`E:`.
