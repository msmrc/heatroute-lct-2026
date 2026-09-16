# Current implementation progress

**Updated:** 2026-09-16

## Active baseline

The production path is Java-only. `apps/api` contains Java 11 / Spring Boot 2.6.3; default,
offline and VPS Compose use that image. Python application code, dependencies, migrations, tests
and runtime services were removed. The frontend calls only the current official Java endpoints.

## Verified in this cutover

- Maven verifies 39 Java tests on the pinned Java 11 runtime.
- Web ESLint, TypeScript, Vitest (8 tests) and production Vite build pass.
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
  This remains an R4 evidence viewer, not the still-missing official seven-type R7 export.
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

- R0 — complete: official gap audit, Java decision and team roadmap.
- R1 — complete for current single-process foundation: Java runtime, PostGIS readiness, Liquibase,
  Swagger, durable PostgreSQL job state, claim/lease/cancel/recovery, Docker and CI.
- R2 — functionally implemented for the current contract fixture; large-file memory measurement,
  replay/deduplication policy and broader official-like fixtures remain acceptance work.
- R3 — functional vertical slice: topology validation, chamber rule, deterministic candidates and
  line splitting. Indexed large-network search and persistence of selected tie-ins remain.
- R4 — first vertical slice implemented: immutable all-demand runs, deterministic independent and
  shared-trunk strategies, partial no-route, an independent tree/crossing/chamber validator and an
  interactive GIS/result viewer. Obstacle-aware search, normalization/local improvement and
  three-way diversity remain.
- R5 — partial: pure sizing/DU/continuous-length rules exist; existing-network flow propagation
  and reconstruction are not implemented.
- R6 — partial: catalog and crossing geometry exist; route-search and final-validator integration
  are not implemented.
- R7 — primitives only: exact segment/reconstruction rates, depth multiplier, unconnected penalty
  and score exist in Java, but full variant costing, ranking, diversity and strict output export
  are not implemented.
- R8 — not implemented; optional after mandatory 2D.
- R9 — not complete: no 3 GB/500 MB/50-user evidence and current VPS OS is not the required
  Ubuntu Server 22 acceptance target.

## Next change

Finish R4/R6 geometry integration: route around forbidden buffers, construct valid special
passages, normalize the resulting paths and re-run the independent validator. Then connect the
existing R5 sizing primitive to valid R4 trees. Do not start depth, MVT or additional file formats.

Older `m1-evidence.md` … `m6-engineering-evidence.md` are historical prototype records only.
The current cross-check against all three organizer artifacts is in `OFFICIAL_ALIGNMENT_AUDIT.md`.

## Workstation note

After the latest Windows reboot, local Docker Desktop fails during startup on a stale internal
AF_UNIX socket. No project volume or Docker data was reset or deleted. CI and the VPS deployment
are green, so this is a workstation repair item rather than an application blocker. Diagnose it
separately before relying on local Compose; do not use factory reset or relocate Docker data from
`E:`.
