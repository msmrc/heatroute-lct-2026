# Current implementation progress

## Code-quality rules and refactoring backlog — 2026-09-21

[REFACTORING.md](REFACTORING.md) records the Java-focused audit baseline (`0096da4`), concrete
rules for new/changed code, open correctness findings B-01–B-12, and staged work RF-00–RF-06.
Feature development continues; broad structural refactoring is deferred until the active scope
and its behavioral baseline are stable. Correctness and recovery fixes remain separate priority
work, with reproducing tests before changes. AGENTS and handoff now link to these rules.
The rules also require concise Russian JavaDoc/JSDoc for key entry points, domain algorithms
and non-obvious contracts, with examples and a reviewer-oriented documentation check.

This is documentation only. No application code was changed, and no B/RF/R gate was completed.
The audit's Java findings are static; Java 11/Compose verification remains required. The evidence
and limits of the previously run web checks are recorded in the new document.

## Organizer video clarification review — 2026-09-17

The full organizer Q&A recording was reviewed against `origin/master` at `c5413b8`. The active
decisions and unresolved contradictions are recorded in
`ORGANIZER_VIDEO_CLARIFICATIONS.md`; `docs/ALGORITHM.md`, `docs/IMPLEMENTATION_PLAN.md`, the roadmap,
handoff, contracts and acceptance gates now point to that interpretation.

The review was followed by a local Q&A-P0 implementation pass:

- supplied-profile reconstruction no longer blocks cost/rank/export; strict-profile gating remains;
- demand points inside OKS receive validated nearest-boundary normal egress with DU 5/7/9 m clearance;
- direct exclusive spurs are compared with penalty; bend ×1.5, overlap max `K_special` and per-ray
  tie-in cost are integrated;
- default runs are mandatory 2D; optional R8 requires `depth_enabled=true`;
- calculation materializes only core network/connection features; restrictions and existing OKS
  are fetched from PostGIS by EPSG:32637 route windows. A dense-window and PostGIS equivalence gate
  is still required before this scale slice can be accepted.

Focused planner/export tests, a final shared-OKS regression, a healthy local Compose build and the
real supplied-file import → run → export cycle have now been run. The full Java 11/lint/typecheck/R9
gate has not been repeated on this final working tree and remains the next verification checkpoint.

Historical R5/R7/R8 evidence below remains valid for the implementation that was tested, but it is
not proof that the clarified supplied-dataset P0 is complete.

**Updated:** 2026-09-17

## Active baseline

The production path is Java-only. `apps/api` contains Java 11 / Spring Boot 2.6.3; default,
offline and VPS Compose use that image. Python application code, dependencies, migrations, tests
and runtime services were removed. The frontend calls only the current official Java endpoints.

### Official contest input correction

- Organizer Q&A confirmed that the supplied GeoJSON shape is the judging input and that
  `oks_connection_point.flow_tph` directly represents demand. The active profile is now
  `official_contest_dataset`, not a compatibility exception.
- Numeric IDs, direct-demand connection points and `restriction_type=oks` are accepted without
  warnings. The unchanged organizer file now produces 76 actionable warnings: 29 missing existing
  flows, 38 inferred upstream links and 9 chamber diameters. `railway` is treated as `tram_tracks`.
- The UI presents the file as ready for calculation and keeps only reconstruction warnings.
  The routing, sizing and depth algorithms are unchanged by this contract correction.
- Final Java 11 `OfficialDatasetRoutingTest` passes on the untouched 233,277-byte organizer file
  and generates a completed three-variant replay bundle with 76 input warnings and zero route/depth
  issues. A local browser pass confirms the map, 17/17, 16/17 and 14/17 variants, grouped diagnostics
  and a validated longitudinal profile.
- The same browser pass exposed a remaining UI defect: after switching from profile mode back to
  the map and then changing variants, the CARTO basemap and some route overlays can disappear while
  the result data remains present. Treat this as an open renderer-lifecycle bug before demo freeze.

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
- Input contract v2 now has a named official contest profile with explicit actionable warnings; the strict
  official profile remains available.
- The byte-identical organizer file is now the only tracked geodata at
  `datasets/official/lct-2026.geojson`; synthetic GeoJSON fixtures and the old demo pack were
  removed. Narrow invalid-input cases are constructed inline in unit tests.
- Topology analysis now falls back to geometric source connectivity when the whole dataset omits
  upstream links, while preserving explicit-link validation for the strict profile.
- Full findings and PM questions are in `SUPPLIED_DATASET_AUDIT.md`.
- Production verification passed on the untouched file: import `valid`, 144 features, zero blocking
  errors, 76 actionable warnings. The durable topology job completed on attempt 1
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
- The map now uses the same MapLibre renderer and vector CARTO Positron treatment as the local
  GdeBenzin project: warm background, amber road hierarchy, calm water/parks and Russian labels.
  OpenLayers and the raster OSM tile path were removed from the web dependency graph. Four
  persistent layer buttons and the map legend remain consolidated in one compact menu;
  restrictions start hidden and layer visibility changes in-place without rebuilding the map.
- MapLibre GL JS was upgraded to the patched 6.10.0 release after auditing production dependencies
  against GHSA-jrc7-96c5-q579. The Vite worker is now loaded as an explicit module worker, the
  basemap customization uses the stricter v6 style types, and CI rejects new high-severity runtime
  dependency advisories. A real-browser replay confirmed vector tiles, route overlays, all three
  variants, vertical profile, diagnostics modal and the direct official-file upload flow.

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
  supplied organizer file displays one grouped Russian warning because the official contest
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
  Baseline/supplied profile may omit `existing_diameter` on a tie-in and remains exportable without
  invented reconstruction data; extended strict profile still rejects the same omission.
- The workspace exposes the download when variants have complete economics and rank.
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
- `railway` is tested as a supplied-dataset alias of the published `tram_tracks` special crossing.
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
- Final depth/scale checkpoint run `35120982320` passes backend, web and clean integration gates;
  Java 11 run `35120995991` repeats the full 2× calculation in 2:14.65 with 406,608 KiB peak RSS,
  34/34 demands connected and three valid variants.
- Draft 2020-12 schemas for appendix input, the official contest dataset profile and strict
  output are versioned in `docs/contracts`, served by the Java API and compiled by NetworkNT 2.0.3.
  Contract tests validate the actual organizer file and the actual exporter result, not only hand
  written examples.
- Run `35124933139` caught a timing-dependent connection-pool starvation bug in the 50-user import
  gate. Commit `6b0ff88` now commits content-hash registration before the long feature transaction,
  keeps duplicate waiters outside database transactions and marks a rolled-back winner `failed`.
  Clean Ubuntu 22 run `35126566499` passes all 50 imports against one durable ID (p95 3,838 ms),
  the real all-demand calculation, published schema/OpenAPI checks and restart recovery. The local
  backend suite now contains 107 tests: 104 passed and three explicit scale probes skipped by
  default.
- Contest-path browser audit found two issues that isolated unit/API gates did not expose. The
  local read-only bundle had replaced the real import report with a synthetic SHA, serialized byte
  size and zero warnings; it now uses `OfficialGeoJsonInspector` over the exact organizer bytes and
  asserts 233,277 bytes, the official SHA-256 and 76 warnings. The warning dialog groups those 76
  records into three localized causes, uses readable 13–14 px text and separates input, depth and
  reconstruction diagnostics. At 1280×720 the map/profile switch had also overlapped the third
  route tab; the responsive top controls are now separated. A real Chromium smoke opens the demo,
  switches to `Альтернативные врезки`, opens the grouped modal and renders the longitudinal profile
  with no console errors or warnings.
- Clean Ubuntu 22 run `35129162919` is the release checkpoint for those contest-path fixes. Web,
  Java 11 backend and integration jobs all passed; integration repeated the 50-user import race,
  real calculation, public/internal contracts and restart recovery. The immutable organizer file
  now reproduces preferred independent 17/17, shared 16/17 and diverse 14/17; team/demo documents
  use those current figures rather than the superseded first-slice result.
- Full-story browser verification then exercised the primary local path instead of only “open
  demo” and exposed a real `405` on file upload. `local-demo-server.mjs` now accepts only the exact
  organizer bytes (size plus SHA-256), replays the completed run for that import and returns 422/413
  for unrelated or oversized data instead of showing a false result. Four Node tests protect the
  multipart parser and replay boundary. The same browser session verified both POST requests, the
  rendered 17/17 result and an error-free console. A second visual failure showed that route
  overlays waited for every remote CARTO tile; initialization now uses `style.load`, so calculated
  geometry is visible as soon as the style graph exists. A Vitest lifecycle regression protects it.
- A responsive browser pass covered 1024×768, 900×700, 640×800 and 390×844. At laptop widths the
  result island now uses a readable 2×2 metric grid; the longitudinal profile reserves that island's
  height without overlap. At 900 px and below profile mode removes the redundant overall-results
  island to keep the engineering chart usable, while map mode retains it. The mobile variant picker
  stays compact and becomes horizontally scrollable on phone widths instead of hiding the map or
  clipping route names.
- A fresh official-file browser journey then covered upload, automatic calculation, all route
  variants, structured no-route diagnostics, the depth profile, warning details, navigation collapse
  and the system-information screen. It exposed an accessibility defect in the warning dialog:
  keyboard focus remained on the map behind the overlay. The shared dialog primitive now provides
  initial focus, wraparound focus trapping, Escape handling, focus restoration and background-scroll
  locking. Variant tabs now support arrow/Home/End navigation with a single tab stop, while the
  map/schematic/profile switch publishes its selected state. Focus behavior is protected by Vitest
  and was rechecked in Chromium; the full journey produced no console errors or warnings.
- A browser fault-injection pass returned `503` for the CARTO style and proved that the previous
  map became completely blank, including calculated routes. MapLibre now starts from an inline
  engineering style, installs source/route overlays immediately and adopts the external vector
  style only after its document is available. A failed or three-second style request keeps the
  complete interactive route/network geometry visible and shows a non-blocking fallback notice;
  online mode still upgrades to the styled CARTO map. Unit coverage protects both paths, and
  desktop plus 640 px browser screenshots verified that fallback status does not collide with the
  inspector, controls or result island.

- R0 — complete: official gap audit, Java decision and team roadmap.
- R1 — complete for current single-process foundation: Java runtime, PostGIS readiness, Liquibase,
  Swagger, durable PostgreSQL job state, claim/lease/cancel/recovery, Docker and CI.
- R2 — complete for the appendix and official contest dataset contracts, including deterministic
  contract+SHA replay/deduplication, published schemas and the exact 3 GiB streaming boundary.
- R3 — functionally complete: topology validation, chamber rule, deterministic candidates, line
  splitting and adaptive dense-constraint lookup. Selected tie-in target IDs are part of every
  immutable variant and persisted in the run JSON; the full 2× scale gate passes.
- R4 — complete for the project-owned acceptance profile: immutable all-demand runs, independent/shared/diverse
  strategies, actual polyline search, simplification, partial no-route, an independent validator
  and GIS/result viewer. Dense constraint lookup is indexed and full 2× end-to-end performance is
  measured; only organizer approval of the hidden maximum profile remains external.
- R5 — functionally complete for contract-complete input: bottom-up flow/DU sizing, automatic
  continuous-length promotion, upstream propagation, partial/common-section reconstruction and
  chamber reconstruction are covered by focused tests. The supplied organizer file cannot produce
  reconstruction because its existing-network baseline and direction fields are absent.
- R6 — complete for the published mandatory 2D table: dynamic OKS buffers, hard forbidden zones,
  special crossings and final validation have exact-value and positive/boundary/negative coverage.
  The supplied `railway` value uses the complete `tram_tracks` rule; vertical
  depth rules belong to optional R8.
- R7 — complete for mandatory 2D: component costing, length, score/rank, all seven output types,
  independent validation, incremental download and official-output map rendering are integrated.
  The section 10.8 illustrative-number discrepancy is documented and the normative arithmetic is
  locked by a golden test.
- R8 — functionally complete for the published depth rules: utility crossings are projected to route chainage;
  the Java optimizer selects above/below passage on the official 0.5 m grid, creates 4 m
  plateaus and 0.10 m/m ramps, and an independent validator checks depth, slope and clearance.
  Endpoint-adjacent crossings can retain a legal selected depth at a chamber, nearby crossings
  at the same depth share one continuous profile, and tie-in egress along the connected utility
  is not misclassified as an independent crossing. The official 17-demand dataset now produces
  zero depth issues across every edge of all three variants.
  Every sized edge carries a depth profile; cost is integrated between profile breakpoints, strict
  GeoJSON exports technical nodes, `depth_start`/`depth_end` and exact XYZ axis coordinates, and
  the web workspace has a dedicated longitudinal-profile view. An impossible passage starts a
  separate XY detour and repeats sizing/profile validation; if no detour exists, the result remains
  explicitly partial with a manual-resolution issue.
- R9 — substantially closed: exact 3 GiB input and 500 MiB valid-output boundaries pass on Ubuntu
  22 / Java 11 with a 512 MiB heap; 50 concurrent API users and clean Compose 1.29.2 deployment are
  measured in CI. A full 2× supplied-geometry calculation also passes locally with 288 features,
  34 demands, 408 candidates, 116.141 seconds and 249,833,520 bytes used heap. Organizer approval of
  the maximum profile and a production-like Ubuntu 22 host rehearsal remain external acceptance
  items; the current VPS is intentionally not changed.
- The same 2× full calculation passes on clean Ubuntu 22 / Temurin 11 in run `35119722470`:
  213.308 seconds calculation time, 481,092 KiB peak RSS, 34/34 connected and three valid variants.

## Next change

Continue profiling and optimizing the 17-demand routing calculation before any further cosmetic UI
work. On 17 September the real `baseline_input` import completed successfully (144 features, 17
connection points, no blocking errors), but the background calculation remained `running` after a
12-minute manual timeout. The first optimization pass now builds each visibility graph once, limits
navigation obstacles by actual distance to the route corridor and caps the simplified convex hull
at 12 navigation vertices. Three bounded 55-second manual probes still did not complete, so the
performance gate remains open and completion time must not be claimed yet.

The next optimization pass adds admissible Euclidean lower bounds before expensive obstacle
searches. Direct assignments skip a farther candidate only after an already valid route proves it
cannot win; shared-pair candidates are skipped when even their obstacle-free lower bound cannot
beat the two independent routes or the best pair already found. These bounds do not weaken any
crossing rule or final validation and preserve deterministic tie-breaking. Final runtime measurement
is pending an explicitly requested verification run.
The routing environment now emits bounded phase diagnostics for visibility-search count, accumulated
navigation nodes and candidate edge pairs (first search, every 25 searches and each completed
variant phase). A first measured run completed in 220.4 seconds with 402 visibility searches and
40,223,610 candidate pairs, but naive vertex sampling caused unacceptable route loss. Replacing it
with a circumscribed 12-sided navigation hull restored connected boundary traversal. The final real
run `74a18b63-726a-4548-97fe-f862dfb1a9ad` completed in 118.9 seconds with 287 searches and
17,288,729 pairs. The focused obstacle-router suite passes 12/12, including a detailed 48-vertex
convex obstacle.

The two correctness gaps found by that probe are fixed. Mandatory egress rebuilding now applies
only to terminal `demand_connection` nodes, and shared junction candidates are built between the
already completed OKS egresses and rejected while they remain inside an OKS. Final supplied-file run
`cc9b8cf6-fef0-43a5-a01a-382a7093cfca` completed in 137.1 seconds. Independent/shared/diverse are
all valid and ranked, connect 14/16/9 demands, and shared is the preferred rank-1 variant. Explicit
economic exclusions remain separated from geometric no-route results. The same run exports HTTP
200 `application/geo+json`: a 169,412-byte `FeatureCollection` with 487 features across all three
variants. Baseline tie-ins without `existing_diameter` are omitted from reconstruction fields rather
than fabricated; extended strict profile retains the completeness failure. Focused suites passed
during correction (17/17 planner/export); after the final search-limit change the shared-OKS
regression passed 1/1 on the final state.

Cancellation is now cooperative inside visibility-graph construction and route search. A running
real calculation reached terminal `cancelled` state for both job and run in 0.5–0.7 seconds. Stale
requested cancellations are finalized after restart. The full-screen web state has its own cancel
button and no longer interprets a failed/missing persisted run request as an infinite calculation;
the browser returns to the workspace and exposes the actual import/API error.

The local Compose stack is now healthy at configurable host ports and builds from a checkout whose
path contains non-ASCII characters. `scripts/dev.ps1` creates an environment-relative ASCII
junction for Docker build context when required; tool caches remain checkout-local by default.
Web verification is green: 16 Vitest tests, 4 local API tests, lint and typecheck. Runtime packaging
is separated from the explicit `test` image target, so starting the service does not silently claim
that the long test gate passed.

Route-result quality follow-up on 18 September removed three misleading behaviours found in the
interactive map. Generated `technical_node` points remain selectable but are rendered as small
neutral markers instead of physical chambers. Exported intermediate IDs now use `:geometry:` for
polyline/section boundaries and reserve `:depth:` for actual depth-profile breakpoints. Failed
connections include bounded diagnostics: candidate/attempt counts, attempted target IDs, direct
blocking constraints and the maximum search corridor.

Coverage is now prioritized during generation and ranking. Candidate search falls back beyond the
nearest four only when none of them is routable; a failed first pass is retried with the failed
demands first; feasible exclusive spurs are no longer deleted merely because their construction
cost exceeds the unconnected penalty. Variant rank compares connected-demand count before the
published economic score. Numeric demand IDs use natural order (`1, 2, ... 10`) rather than
lexicographic order. The final Java 11 gate passes 136 tests with the supplied 17-demand dataset
selecting a preferred 17/17-connected variant; three explicitly gated scale tests remain skipped.
Frontend verification passes 17 Vitest and 4 local API tests, plus lint and typecheck. The processing
screen exposes cancellation immediately after `job_id` is returned, even before the first polling
response.

The 18 September building/cost correction removes the former endpoint loophole that could drop an
entire `oks` constraint whenever a route endpoint fell inside its clearance. The footprint now
remains a hard obstacle; a demand may enter only its own OKS through the terminal normal-egress
leg. Concave footprints use the first valid clearance exit and extend to the last buffered-boundary
intersection only when the minimum exit is still blocked. Shared junctions are rejected inside any
forbidden clearance, and every edge is checked and, when possible, rerouted after bottom-up sizing
with its final DU.

The focused obstacle/planner suites pass 29/29, and the five supplied-data egress regressions pass
1/1 as one grouped test. The final untouched supplied-file test passes in 468.506 seconds. All three
variants are valid: independent connects 15/17 (7,442.981 m, score 64.318630863), shared connects
17/17 (6,409.452 m, score 48.672753143) and diverse connects 13/17 (9,808.406 m, score
94.580208806). Shared is therefore the preferred full-coverage economic variant. This is a bounded
deterministic search over the implemented topologies, not a proof of the global optimum. The next
algorithmic task is reuse/caching of visibility graphs and a true multi-demand tree optimizer; the
current full-dataset runtime is not suitable for an interactive loading screen.

The next routing stage replaces greedy pair acceptance with a monetary constrained-tree search.
Shared branches are compared against their independent baseline using full marginal construction
cost (pipe diameter, branch chamber and tie-in), not geometric length alone. Candidate junctions
include the three-terminal geometric median, and a deterministic beam of up to 96 states selects
compatible branch combinations while enforcing demand exclusivity, existing-chamber capacity and
the route validator after every addition. The algorithm version is now `cost-tree-2`. This removes
the known greedy-choice defect; it remains a bounded constrained-Steiner approximation rather than
an unsupported claim of a proven global optimum. Focused and full-dataset verification is pending
because it was not requested in this implementation turn.

The following `cost-tree-3` stage removes the remaining pair-only topology limitation. For each
unassigned demand, `shared` now evaluates both a separate tie-in and attachment to an existing
branch chamber or an interior point of a built route edge. An interior attachment splits the edge
and its `RouteSection` metadata at a new chamber, adds one branch, validates the complete tree and
then performs bottom-up flow/DU sizing before comparing total new-network construction cost. This
allows third and subsequent demands to reuse one upstream trunk instead of creating parallel rays.
Only restrictions present in the imported dataset participate in routing; background-map roads are
not synthesized as constraints. A focused three-demand regression was added. Tests, full-dataset
runtime and live Compose behaviour remain unverified in this implementation turn.

`cost-tree-4` adds the local improvement pass required to reduce order-dependent parallel routes.
After the initial shared tree is built, every remaining independent root ray is temporarily removed
and evaluated as a branch of the rest of the network. The replacement is accepted only when the
complete bottom-up-sized network is cheaper, or when the rounded monetary result is equal and the
number of independent tie-in rays decreases. Every candidate still passes the tree, chamber-degree,
cycle and outside-node intersection validator. This keeps the official monetary objective primary
while preferring one reusable trunk over equivalent parallel rays. Verification remains pending.

`cost-tree-5` generalizes that pass from independent root rays to every terminal demand in the
shared forest. Each demand is detached, orphan chambers are pruned, degree-two generated chambers
are contracted with their section metadata preserved, and the demand is rerouted against the whole
remaining network. A candidate is accepted only by a strictly decreasing lexicographic objective:
full sized construction cost, tie-in ray count, total route length, then generated chamber count.
The search is deterministically bounded to two accepted relocations and, during this improvement
pass, the four nearest chambers plus the nearest projection on three route edges per demand;
it is a whole-tree local improvement, not a claim of an exact local or global Steiner optimum.
Focused and supplied-file verification remain pending.

Older `m1-evidence.md` … `m6-engineering-evidence.md` are historical prototype records only.
The current cross-check against all three organizer artifacts is in `OFFICIAL_ALIGNMENT_AUDIT.md`.

## Workstation note

Docker Desktop and the local Compose stack are operational. The default ports 5173 and 8000 were
occupied by unrelated local processes, so the verified instance uses `WEB_HOST_PORT=5174` and
`API_HOST_PORT=8080` without stopping those processes.
# 2026-09-21 — amended organizer documents and global-tree-7 (local)

- The corrected organizer GeoJSON replaced the tracked sample without normalizing IDs or geometry.
- `railway` is again a forbidden restriction with a 1 m clearance; only `tram_tracks` uses the
  special crossing rule.
- Arbitrary turns from 0 to 90 degrees no longer receive an invented 1.5 cost multiplier.
- Existing-network and existing-chamber reconstruction is excluded from calculation and export.
- Official export is reduced to `heat_network`, `heat_chamber`, `technical_node` and
  `variant_summary`; the summary now reports the existing-chamber tie-in count and cost.
- The shared-tree search is bounded before obstacle routing and publishes up to three meaningful
  balance, cost and length oriented variants.
- This contract checkpoint was subsequently covered by the focused performance result below;
  full test, lint, typecheck and smoke suites were not run.
# 2026-09-21 — routing performance recovery (local)

- Repeated empty-context route searches, including failed searches, are cached per calculation.
- Shared-pair exploration is bounded to the four closest candidates; assignment, beam and graft
  candidate counts are capped before obstacle routing rather than after it.
- Grafts are evaluated at the nearest projection and midpoint of the closest tree edges. The full
  geometry validator runs on the selected final variant instead of every simulated attachment.
- Incomplete independent/alternative drafts are no longer depth-profiled and published; the
  contract permits one to three meaningful variants.
- The focused official-dataset method completed successfully in 57.417 s with 263 visibility
  searches and 1,902,450 evaluated pairs. Before the recovery, CI exceeded 20 minutes with 1,475
  searches and about 78.8 million pairs.
- The resulting balanced variant connected 17/17 objects, measured 2,098.903 m and scored
  14.937905995. This restores the CI time budget, but route quality still needs improvement against
  the external 1.83 km / 286.2 million reference.

# 2026-09-21 — global-tree-8 geometry correction (local, recheck required)

- The planner can reuse the nearest eligible generated branch chamber instead of creating a new
  chamber for every graft; the degree-four invariant remains enforced by the structural validator.
- A failed independent ray can attach to the already built forest, so length-oriented and
  alternative-target drafts are no longer discarded solely because one separate ray crosses the
  accepted geometry.
- A connection point inside its own OKS chooses a nearby boundary side facing the candidate
  network when that side is no more than 10 m farther than the nearest boundary. The final own-OKS
  leg no longer adds the foreign-building DU clearance; all other route segments retain it.
- CI failure `#87` was diagnosed: the timed official routing method passed, while backend, web and
  integration checks still asserted the superseded reconstruction, bend, railway, export and
  variant-label contracts. The web label and integration complete-export expectations were aligned;
  remaining backend expectation updates are not claimed complete.
- One focused Java 11 run was performed after the first implementation. It failed because the final
  validator still resolved the own-OKS exemption using the old nearest-only exit, and the wider
  chamber search raised the official calculation to 148.9 s. Both causes were then changed: the
  validator uses the actual approach direction and chamber reuse is limited to the nearest chamber.
  Per the project check policy, the modified final state has not been rerun without a new explicit
  verification request.

# 2026-09-21 — amended-contract test alignment (local, verified)

- Restored the established UI variant labels: `Раздельные трассы`, `Общая сеть` and
  `Альтернативные врезки`; the internal strategies remain `shortest`, `balanced` and `cheapest`.
- Backend expectations now cover the amended contract: forbidden `railway`, no arbitrary bend
  multiplier, no existing-asset reconstruction, four exported object types, construction cost
  including chambers/tie-ins, and valid partial results with explicit `no_route` connections.
- The CI integration assertions use the same contract: one to three valid variants and a preferred
  partial result whose `no_route` count exactly accounts for every unconnected demand.
- Invalid route drafts are no longer published as official variants. A valid balanced partial
  result is retained when a higher-coverage draft violates the no-crossing invariant.
- Final Java 11 `mvn verify`: 143 tests, 0 failures, 0 errors, 3 opt-in scale tests skipped;
  `OfficialDatasetRoutingTest` completed in 152.707 s. Final web Vitest: 8 files and 18 tests
  passed. No lint, typecheck, Compose smoke or deployment was run in this checkpoint.

# 2026-09-21 — global-tree-9 coverage and constructability pass (local calculation verified)

- Every OKS keeps its nearest short boundary exit first. If that route is physically blocked even
  without already accepted branches, the planner retries with a nearby target-facing side for the
  concrete tie-in or tree junction. This restored OKS 2 and 5 without restoring a route through the
  full building footprint; already-good terminal routes retain their original short exit.
- Equal-coverage drafts are compared by the complete official score, including `no_route`
  penalties. Construction cost alone can no longer select a partial tree with a worse official
  result merely because its connected objects happen to be cheaper to build.
- A selected tree attachment is checked after bottom-up diameter sizing before it is accepted, so
  a shared trunk cannot become invalid only after the added flow increases its required clearance.
- Visibility search uses a small bounded constructability preference: straight and right-angle
  paths are preferred to chains of arbitrary oblique bends when their lengths are close. This is
  a route-search preference only and does not alter any official construction tariff.
- An expensive third full search is skipped when two distinct candidates have already been built;
  after final validation only meaningful valid variants remain publishable.
- The final local Compose calculation `6e0b759c-24ed-42a0-a2ac-04d0021b6c16` completed in 239.861 s.
  Its valid preferred `balanced` tree connects 17/17 OKS, is 2,061.412 m long, costs
  305,521,285.79 RUB, scores 14.738832002, contains 14 chambers and 27 edges, and has zero geometry
  validation issues. This improves the previous valid 17/17 checkpoint by 37.491 m, about
  3.09 million RUB and 0.199073993 score, but runtime remains above the desired target.
- The Compose build compiled main and test sources with Java 11 and skipped test execution. The
  full automated suite, lint and typecheck were not run by explicit request.

# 2026-09-21 — global-tree-10 angle and detour pass (local calculation verified)

- Visibility search now treats straight, 45-degree and 90-degree turns as the preferred
  constructible set. A conservative post-pass replaces an arbitrary elbow only when both
  replacement segments remain legal and the local length grows by no more than five percent;
  this preference does not change the official tariff.
- Whole-tree optimization expands its graft search only for a terminal branch whose routed/direct
  length ratio exceeds 1.35. The ordinary candidate limits remain unchanged, avoiding a global
  expansion for every demand.
- The existing-network tie-in rule was rechecked and fixed in regression expectations: an existing
  `heat_chamber` is reused when it is at most 10 m from the selected point on the existing network
  and will retain at most four incident linear sections; beyond 10 m the tie-in requires a new
  chamber on the existing network.
- Final local run `b969b8ed-6892-47a1-a3c6-639ef09df780` completed in 373.076 s. Its only
  meaningful `balanced` variant is valid, connects 17/17 OKS, is 2,072.949 m long, costs
  283,934,192.11 RUB, scores 14.169004379, contains 12 chambers and 26 edges, and has zero
  validation issues.
- All 42 internal route bends are within three degrees of the straight/45/90 set (zero mean
  deviation after snapping). Compared with `global-tree-9`, this removes two chambers and reduces
  cost by 21,587,093.68 RUB, but adds 11.537 m and 133.215 s.
- Two excessive detours remain (ratios 2.30 and 1.77). They are explicitly not accepted as solved:
  a future pass must optimize the selected trunk/tie-in topology rather than widen visibility
  search again. The local UI was refreshed to this run.
- Compose packaging compiled main and test sources with Java 11 and skipped test execution. The
  full automated suite, lint and typecheck were not run by explicit request.

# 2026-09-23 — global-tree-40 geometry-first selection without roads (local diagnostic)

- Engineering portfolio selection now orders candidates by expert-rule compliance before bend
  count: out-of-range 90–135-degree bends, sub-2 m bend spacing and total range deviation are
  considered before canonical 90/135-degree preference, chamber count, final construction cost
  and length. Cost and length remain bounded by the existing 5% corridor with one relaxation to
  10%, so constructability cannot select an unbounded detour.
- The evaluator now includes angles where separate route edges meet at the same topology node.
  Degree-two nodes use the expert bend rule; branch chambers receive a separate soft diagnostic
  for deviation from 45/90/135/180-degree ray relationships. This closes the previous blind spot
  where visually irregular chamber stars did not affect portfolio ranking.
- Focused Java tests were added for canonical-angle preference and multi-edge junction evaluation,
  but were not executed by explicit request. Compose packaging compiled main and test sources with
  Java 11 and skipped test execution.
- The ordinary corrected dataset was calculated without road polygons in run
  `d944ea70-aeb3-4503-bf07-36f663c61298`. All three official-valid variants connect 17/17 OKS:
  engineering 1,791.020 m / 269,936,197.31 RUB, shortest 1,785.250 m / 274,070,570.37 RUB, and
  cheapest 1,762.700 m / 266,995,620.48 RUB. Engineering and shortest still expose five
  out-of-range bends; the result is numerically unchanged from the preceding no-road checkpoint.
- This proves ranking alone is insufficient because the bounded portfolio does not yet contain a
  better legal geometry for the four affected branches. The 1,361-second runtime also misses the
  ten-minute iteration target: 1,640 visibility searches and about 12.33 million evaluated pairs
  complete before final engineering repair. The next gate is bounded branch/egress candidate
  generation for those four edges plus early termination of non-improving global repairs.

# 2026-09-23 — road-corridor experiment restored on current planner (`global-tree-39`, local)

- The unchanged `global-tree-38` planner was rerun against the opt-in corrected dataset enriched
  with 94 OSM road polygons. Run `14b07065-698d-44aa-8ac5-e1d7874ebf8a` spent 1,471 seconds in
  3,454 visibility searches / roughly 58.1 million evaluated pairs and published zero variants.
- The failure was structural: a shallow road crossing is rejected by the official 45-degree rule,
  while the current visibility graph only supplied navigation vertices for forbidden polygons.
  Therefore it had no nodes from which to construct a legal alternative road crossing.
- `global-tree-39` restores two bounded navigation portals immediately outside an intersected
  polygonal road/tram object, aligned with the normal of its minimum rectangle. Roads remain an
  opt-in experiment and retain the official crossing validation and special-section economics.
- A focused regression case was added for a shallow direct road crossing that must be replaced by
  a perpendicular crossing. Road-enriched run `d9cd8766-1ce8-4ad7-a973-931fcb1cbca8` then
  published three valid 17/17 variants after 1,441 seconds. Engineering/shortest are identical at
  2,099.278 m / 316,418,078.53 RUB with eleven expert-angle findings; cheapest is 2,008.104 m /
  307,270,096.16 RUB. All variants contain 12 road special sections and one fewer chamber than the
  no-road control, but are materially longer, costlier and less constructible. The polygon-road
  visibility experiment is therefore diagnostic only and does not replace the corrected-dataset
  default. Automated tests were not run by request; Docker packaging compiled main and test
  sources with test execution skipped.

# 2026-09-23 — global-tree-38 bounded engineering repair and honest diagnostics

- Engineering regularization is now applied to the selected engineering and shortest drafts in
  the normal portfolio path, rather than only when objective selection returns no draft. On the
  corrected dataset this reduced the expert-angle findings from eight to five while preserving
  three official-valid 17/17 alternatives.
- Directional OKS egress is capped relative to the nearest legal wall exit. A target on the
  opposite side can no longer make a connection traverse the full building merely because the
  globally configured alternate-egress allowance is large.
- A broader rotated-dogleg experiment reduced five angle findings to four but took 1,348.5 s in
  the focused official-dataset scenario and still failed the zero-warning criterion. It was
  removed from the active algorithm instead of shipping a slower incomplete repair.
- Focused verification passes 35/35 tests across `EngineeringRouteEvaluatorTest`,
  `OfficialObstacleRouterTest` and `OfficialRoutePlannerTest`. The official-dataset regression
  now requires exactly three variants and 17/17 connections; the attempted zero-warning assertion
  correctly failed and remains an open topology-rebuild gate rather than being hidden.
- The last published comparison run before removing the rejected dogleg is
  `3ece314a-651c-4e8b-9231-dead14021fca` (`global-tree-36`): all three variants are official-valid
  and connect 17/17, but engineering/shortest still report five out-of-range bends. Runtime was
  960.410 s, so both expert-angle completion and the approximate ten-minute iteration target
  remain open. Full tests, lint and typecheck were not run by explicit request.

# 2026-09-23 — global-tree-34 variant publication and calculation-summary correction

- All official-valid `engineering`, `shortest` and `cheapest` representatives are published and
  ranked. Expert 90–135-degree and 2 m bend findings remain attached to engineering/shortest as
  `engineering_issues` warnings instead of deleting those alternatives from the response.
- The web workspace localizes and exposes those warnings in the inspector and validation dialog.
  The bottom result cards now describe the selected variant itself: actual network length,
  connected OKS count and cost; they no longer substitute absent sibling strategies with zeros.
- Focused web verification passed: `RouteVisualization.test.tsx`, 3/3 tests. Focused Java
  verification passed 18/18 tests in `EngineeringRouteEvaluatorTest` and
  `OfficialRoutePlannerTest`; stale index-based expectations were aligned with the three-strategy
  contract. Full tests, lint and typecheck were not run by the user's explicit scope limitation.
- This restores comparison visibility but does not declare the expert geometry gate complete.
  Corrected-dataset engineering/shortest warnings still identify the subtrees that need topology-
  level rebuilding; the official validator remains the blocking publication boundary.
- Live run `dcfcfe45-3c2a-4ad1-a8a7-b58b5dffba4d` completed in 781.996 s on the corrected import.
  All three variants are official-valid and connect 17/17 OKS. Engineering: 1,704.185 m,
  260,010,070.50 RUB, score 12.392836974, eight expert-angle findings. Shortest after the required
  engineering normalization: 1,698.415 m, 264,144,443.56 RUB, score 12.491289420, the same eight
  expert-angle findings. Cheapest under the official-TZ-only geometry rules: 1,689.315 m,
  258,272,510.65 RUB, score 12.299575298, and no engineering-warning contract applies to it.
- The rebuilt local Compose stack is healthy on `http://127.0.0.1:5174/`; the latest calculation
  action visibly loads all three tabs and the selected-variant drawer reports 1.69 km / 17 of 17
  instead of `0 m / 0 OKS`.

# 2026-09-22 — global-tree-33 expert geometry and bounded objective portfolio

- The calculation keeps independent, shared-tree and cheapest-with-existing-chamber-reuse drafts
  as a bounded portfolio instead of deriving all three UI choices from one winner. The shortest
  representative is selected by total routed length, the cheapest by the official construction
  economics, and the engineering representative inside a five-percent cost/length corridor that
  is relaxed once to ten percent when necessary.
- The domain expert's additional constructability rule is enforced on the finished geometry for
  the engineering and shortest strategies: internal bend angles must be 90–135 degrees and two
  consecutive bends must be at least 2 m apart. The cheapest strategy deliberately retains only
  the official-TZ geometry rules. A final bounded normalization pass removes safe shallow kinks or
  replaces them with legal 90/135-degree elbows after tree grafting, chamber contraction, sizing
  and mandatory OKS egress have finished.
- Building polygons remain mandatory obstacles with the official 5 m clearance. A 1.5 m roadway
  offset is enforced when roadway geometry is supplied in the official input. The corrected
  dataset contains no roadway features, so the algorithm does not infer legal engineering
  corridors from the visual basemap.
- UI strategy names are now `Инженерная трасса`, `Самый короткий` and `Самый дешёвый`; the
  explanatory copy states the different optimization priorities.
- Docker packaging compiled Java main and test sources with test execution skipped. The focused
  evaluator tests were added but were not run, and the full automated suite, lint, typecheck and
  browser smoke remain deferred by explicit request.
- Final corrected-dataset run `d6a8b187-a14f-4638-a39f-5212a6473166` completed in 725.214 s.
  The cheapest official-TZ representative is valid at 17/17 OKS, 1,689.315 m,
  258,272,510.65 RUB, score 12.299575298, 29 edges and 33 nodes. The two strict representatives
  were intentionally withheld: final local normalization reduced their non-compliant bends from
  14 to 8, with zero sub-2 m bend pairs, but could not remove the remaining bends without entering
  an official clearance zone. Their next gate is bounded subtree rebuilding rather than another
  point-level corner adjustment.

# 2026-09-22 — objective-specific variants and optional existing-chamber reuse (`global-tree-29`, local)

- The expensive valid merged-chamber candidates are now routed once and used to select separate
  length and construction-cost winners. The shortest draft is compared against the independent
  topology, while the cheapest draft is compared against the balanced topology by fully sized
  construction cost; a longer draft is therefore allowed to win the cheapest objective.
- The cheapest-only post-pass can replace a single-ray new tie-in chamber with a connected
  existing chamber up to 60 m away. It considers the bounded incident network directions at a
  junction chamber, preserves the maximum four sections, and accepts a replacement only after
  full geometry validation and a strict reduction of the final construction cost.
- Corrected-dataset run `0e3853ec-8af9-41e5-8a5a-e9adfe323e09` completed in 710.374 seconds.
  All three variants are valid and connect 17/17 OKS: balanced is 1,761.086 m / 271,453,650.54
  RUB, shortest is 1,759.014 m / 268,589,256.36 RUB, and cheapest is 1,765.990 m /
  264,238,637.23 RUB. The cheapest route is 6.976 m longer than the shortest but 4,350,619.13
  RUB cheaper.
- The cheapest route reuses existing chamber `106` instead of constructing a new chamber on
  segment `126`, reducing generated chamber cost from 53 million RUB in the shortest variant to
  48 million RUB. Chamber `107` was evaluated through each incident network direction but no
  fully valid cheaper route was found, so the upper-left building remains on its segment tie-in.
- Docker packaging compiled main and test sources with Java 11 and skipped test execution. Full
  automated tests, lint and typecheck were not run; two corrected-dataset calculations verified
  the intermediate and final local states. The remaining runtime target is approximately ten
  minutes, with the base shared-tree visibility phase now the dominant optimization target.

# 2026-09-22 — final-tree tie-in relocation (`global-tree-28`, local)

- A new tie-in selected for the initial shared pair is no longer frozen after the rest of the
  tree is grafted. For every single-ray `new_tie_in_chamber`, the planner projects the completed
  adjacent trunk back onto the same existing-network feature and evaluates that nearer point.
- The replacement keeps the mandatory 10 m existing-chamber reuse zone, a perpendicular four-
  metre entry relative to the existing-network tangent, and a 45/90/135/180-degree final ray at
  the generated tree chamber. It is accepted only after complete geometry validation and when
  final construction cost, or equal-cost length, improves.
- On corrected-dataset run `6a9fe026-ca3b-4cbf-8c74-bbcf6834604d`, target segment `126` moved
  from `(414445.482, 6173320.717)` to `(414430.807, 6173282.893)`. Its root edge fell from
  48.886 m to 18.090 m and retained three base legs: 4.000 m perpendicular entry, 10.090 m
  connecting leg and 4.000 m constructible chamber approach.
- All three published variants remain valid and connect 17/17 OKS. Balance is 1,761.086 m /
  271,453,650.54 RUB / score 12.883960215; shortest is 1,759.014 m / 268,589,256.36 RUB /
  score 12.797541178; cheapest is 1,759.191 m / 268,218,637.65 RUB / score 12.787694854.
  Against `global-tree-26`, each variant is 30.796 m shorter and 4,620,077.51 RUB cheaper.
- The full run took about 830.963 seconds. Docker packaging compiled main and test sources with
  Java 11 and skipped test execution; automated tests, lint and typecheck were not run.

# 2026-09-22 — adjacent-chamber group merge experiment (local, no preferred-route change)

- `global-tree-20`–`global-tree-23` add a generic whole-tree operator that finds two short-linked
  degree-three generated chambers, proposes one degree-four chamber at their endpoints, midpoint
  or outer-branch line intersections, and reroutes all four external branches as one group.
- Demand branches select the side of their OKS facing the proposed street junction. A non-demand
  trunk may leave an existing topology node without treating the other old edges incident to that
  same node as obstacles; the final crossing, clearance, tree and economics checks still run on
  the complete rebuilt draft.
- The corrected-dataset run `f7b5dc2b-b582-44dc-9e3a-377535585988` completed valid at 17/17 OKS,
  1,864.356 m, 288,191,073.55 RUB, score 13.662418059, 13 generated chambers and 30 edges. No merge
  candidate improved the complete official score, so the preferred route is geometrically and
  economically unchanged from `global-tree-19`.
- The requested vertical common trunk cannot be obtained reliably by contracting two chambers
  after the surrounding branches have already been routed. The next gate must generate this
  intersection-centred trunk topology before terminal grafting, keep it as a competing complete
  draft, and compare it with the accepted economical control after sizing and final validation.
- Docker packaging compiled main and test sources with tests skipped. Three live calculations were
  used to isolate the rejection stage and measure the final variant; full tests, lint and typecheck
  were not run by explicit request.

# 2026-09-22 — competing early topology seeds (`global-tree-24`, local experiment)

- The shared-tree constructor now keeps up to four different pair-based common-trunk seeds,
  completes every seed to a full 17-demand tree, compares complete official economics, and runs
  the expensive whole-tree improvement only for the winning completed draft.
- Corrected-dataset run `a94db9b1-bf67-453e-beba-ac7bcf57303f` remained valid at 17/17 but
  selected exactly the previous geometry: 1,864.356 m, 288,191,073.55 RUB, score 13.662418059,
  13 generated chambers, 30 edges and zero validation issues.
- Runtime increased to 1,315.076 seconds without a quality gain. Pair-seed diversity alone cannot
  produce the requested intersection-centred vertical trunk; that topology needs an explicit
  multi-terminal trunk seed rather than another ordering of the same pair-and-graft operations.
- Docker packaging compiled main and test sources with tests skipped. One live corrected-dataset
  calculation and the junction diagnostic were run; full tests, lint and typecheck were not run.

# 2026-09-22 — three valid objectives and merged chambers (`global-tree-26`, local)

- The planner again publishes up to three distinct valid variants: 70/30 balance, shortest, and
  cheapest. A valid chamber-group replacement is retained for the latter objectives even when it
  would not replace the current control during a local score-improvement pass.
- Replacement branches that terminate at the same proposed chamber no longer treat one another
  as obstacles at that common endpoint. The completed draft is still rejected for any overlap,
  crossing, forbidden clearance, degree, tree, sizing or economics violation elsewhere.
- Corrected-dataset run `7cba3213-4d79-44f2-b6e5-995fc7fb5806` produced three valid 17/17 trees:
  balance at 1,791.882 m / 276,073,728.05 RUB / score 13.105710385 / 12 generated chambers;
  shortest at 1,789.810 m / 273,209,333.87 RUB / score 13.019291348 / 11 chambers; and cheapest
  at 1,789.987 m / 272,838,715.16 RUB / score 13.009445024 / 11 chambers. The latter two contain
  two merged chamber nodes each; every variant has zero validation issues.
- Runtime fell from 1,315.076 to 1,143.256 seconds after removing four full early-tree builds, but
  remains unacceptable. Shortest and cheapest currently rerun the same local merge candidates;
  caching one evaluated candidate set for both objectives is the next bounded performance step.
- Direct Docker builds compiled Java main/test sources and the TypeScript production bundle;
  backend tests were skipped and full tests, lint and standalone typecheck were not run.

# 2026-09-21 — experimental OSM road-corridor routing (local verified)

- OpenStreetMap road centrelines in the active network/OKS window are converted to official
  `restriction_type=road` polygons in an ignored local experiment dataset. Service drives and
  roads outside the routing window are excluded; the current experiment adds 94 road polygons.
- The unchanged router completed the road-enriched run but produced no complete variant (12/17
  connected). It could validate a crossing angle but had no navigation vertices from which to
  construct a legal alternative crossing.
- The visibility graph now adds two points immediately outside an intersected road polygon on a
  line normal to the polygon's main axis. This supplies an explicit 90-degree crossing candidate
  while retaining the published 45-degree minimum as the hard rule.
- The repeated run `26bfc22b-40fb-42db-86ff-951c36288ac0` is valid and connects 17/17 at
  2,071.836 m, 305,170,558.59 RUB and score 14.760283641, with 11 chambers, 25 edges and zero
  validation issues. Runtime was 691 s. This is 131.216 m and 37,788,333.24 RUB worse than the
  no-road `global-tree-12` checkpoint, so the road layer remains an opt-in experiment rather than
  the default official calculation.
- Compose packaging compiled main and test sources with Java 11 and skipped test execution.
  Automated tests, lint and typecheck remain deferred by explicit request.

# 2026-09-21 — global-tree-13 perpendicular graft approach (locally verified, ineffective)

- The OSM road enrichment was rejected as the default: although it produced a valid 17/17 tree,
  it was 131.216 m longer, 37,788,333.24 RUB costlier and slower than the corrected-dataset
  checkpoint. Road polygons and crossing portals are not used by the default calculation.
- A branch grafted into the generated tree now tries a four-metre perpendicular final approach to
  the supporting trunk. The constructible branch replaces the shortest branch only when the full
  path remains legal and its local length grows by no more than five percent.
- This stage targets acute and arbitrary junction entries without hard-coding coordinates or
  changing the official cost model. Compose compilation succeeded with tests skipped. Corrected-
  dataset run `26294ae1-ad99-4aa9-aa31-92a4a12d47ca` completed valid at 17/17 OKS,
  1,940.620 m, 267,382,225.35 RUB, score 13.30856231, 11 chambers and 25 edges in about
  509.896 seconds.
- Exact comparison with the preceding no-road run found 0 changed geometries out of 25 edges and
  identical metrics. The four-metre local suffix therefore does not improve this dataset: the next
  optimization gate must relocate junctions or reconnect whole subtrees instead of adding a bend
  immediately before a fixed junction. Automated tests, lint and typecheck remain deferred by
  explicit request.

# 2026-09-21 — bounded junction relocation experiment (rejected)

- Experimental `global-tree-14` through `global-tree-16` moved terminal graft chambers by bounded
  offsets along their supporting trunk and evaluated candidate drafts with the official score.
- The approach remained valid at 17/17 OKS but regressed the corrected dataset. The two material
  results were 1,951.141 m / 272,437,673.30 RUB / score 13.481677852 and 1,952.772 m /
  273,803,205.00 RUB / score 13.524805740, versus the accepted `global-tree-13` checkpoint at
  1,940.620 m / 267,382,225.35 RUB / score 13.308562310.
- The exact final-economics guard exposed that comparing against the raw pre-optimization tree is
  insufficient: the correct control is the completed legacy whole-tree pass. The guarded run also
  took about 659.931 seconds. The experiment was removed from the default algorithm rather than
  shipping a slower and worse route.
- The next topology gate must compare a jointly rebuilt branch group against the completed control
  tree, not relocate one terminal attachment at a time. No automated tests, lint or typecheck were
  run; Compose compilation and live corrected-dataset calculations were used for this experiment.

# 2026-09-22 — fourth-ray constructability experiment (local, rejected as preferred route)

- The highlighted `junction:6:8` had four incident routes. One late tree attachment entered at
  non-constructible directions (about 35/124/159 degrees relative to the existing rays), while one
  of its incident branches had a routed/direct ratio of 1.53.
- `global-tree-17` first preferred a perpendicular three-terminal seed and rejected a later fourth
  ray unless all new incident angles were within 7.5 degrees of 45/90/135/180. It reduced the
  diagnostic count of non-preferred chamber-angle pairs from 26 to 8, but produced 1,943.662 m,
  291,830,984.20 RUB, score 14.002253558 and 12 generated chambers. The cost regression is not
  acceptable for the preferred 70/30 objective.
- `global-tree-18` removed the forced seed preference; `global-tree-19` further limited the rule to
  the fourth ray only. Both final calculations were identical: valid 17/17 at 1,864.356 m,
  288,191,073.55 RUB, score 13.662418059, 13 generated chambers and 30 edges. They are 76.264 m
  shorter than the accepted `global-tree-13` checkpoint but 20,808,848.20 RUB more expensive and
  therefore worse by the official score.
- The local UI was refreshed to `global-tree-19` for visual comparison. This experiment is not an
  accepted replacement for `global-tree-13` and must not be pushed as the preferred algorithm.
  The next implementation must preserve both complete candidates and expose the shorter,
  constructible tree separately, or rebuild the complete four-terminal branch group and accept it
  only after final economics rather than forcing a local attachment decision.
- Docker packaging compiled main and test sources with Java 11 and skipped test execution. Three
  live corrected-dataset calculations were performed; full tests, lint and typecheck were not run.

# 2026-09-21 — global-tree-12 alternative tie-in and chamber approach pass (local verified)

- A segment whose nearest tie-in is within 10 m of an existing chamber now contributes a bounded
  alternative point outside that 10 m reuse zone. This permits a new chamber farther along the
  same existing-network segment when the mandatory existing chamber would force a long obstacle
  detour; the point is selected by the optimizer and is not dataset-specific.
- A route into an existing or new tie-in chamber first attempts a four-metre final leg
  perpendicular to the local existing-network tangent. This is a constructability preference:
  the reviewed public rules require a safe sealed wall penetration but do not establish a general
  statutory 90-degree angle for the point-only chamber model. The ordinary legal approach remains
  a fallback when a perpendicular leg is blocked.
- A terminal route with routed/direct ratio above 1.35 may retry the nearby target-facing side of
  its own OKS. The alternate egress is accepted only when it is legal and strictly shorter; this
  removed the 60.230 m hairpin whose endpoints were only 14.744 m apart.
- Final local run `0c84f5b4-1e40-4493-bc66-7e43e76d957c` completed in 486.077 s. Its valid
  `balanced` variant connects 17/17 OKS, is 1,940.620 m long, costs 267,382,225.35 RUB, scores
  13.308562310, contains 11 chambers and 25 edges, and has zero validation issues.
- Compared with `global-tree-10`, the network is 132.329 m shorter, 16,551,966.76 RUB cheaper,
  and uses one fewer chamber and edge. Every generated chamber has only straight/right-angle
  incident directions in the point topology check.
- The previously highlighted round-building branch is shorter, but still has a 1.89 detour ratio;
  it is improved, not closed. Runtime also regressed to 486.077 s. The next gate is a strict-budget
  reuse/cache of alternative tie-in searches followed by a topology-level repair of this remaining
  branch.
- Compose packaging compiled main and test sources with Java 11 and skipped test execution. The
  full automated suite, lint and typecheck were not run by explicit request.
