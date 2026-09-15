# M6/M7 requested engineering and geodata slice

Date: 2026-09-08

## Delivered

- ZIP Shapefile import validates member paths, symlinks, duplicate names, entry count,
  uncompressed size, compression ratio and required `.shp/.shx/.dbf` sidecars before extracting
  one dataset into a server-owned temporary directory. Archives are not passed to GDAL VSI.
- GeoParquet import uses GeoPandas 1.1.4 and PyArrow 25.0.1, checks a single geometry column,
  preserves CRS metadata and enters the same mapping/validation/publication pipeline.
- Source Z ordinates survive horizontal CRS transformation; vertical datum remains an explicit
  field rather than being inferred from the horizontal CRS.
- Published dataset layers have a workspace-scoped MVT endpoint backed by PostGIS
  `ST_TileEnvelope`, `ST_AsMVTGeom` and `ST_AsMVT`. Cache identity includes workspace, immutable
  layer/version and z/x/y; responses use private cache control and ETag/304.
- MapLibre consumes those MVT tiles directly for published project layers instead of materializing
  a project-wide GeoJSON payload in the browser.
- Route postprocessing greedily tests farther shortcuts, preserves start/goal/waypoints and calls
  the complete corridor validator on every accepted candidate. The passport records candidate and
  accepted shortcut counts plus removed vertices.
- Scenario revisions can carry a strictly ordered vertical profile, crossings, vertical datum,
  route diameter and explicit clearance/grade thresholds. Reports distinguish `passed`, `failed`,
  `insufficient_data` and `not_performed`.
- Construction methods are no longer a free-form demo string. The catalog includes open trench,
  HDD, microtunneling, pipe jacking, bridge attachment and existing duct, with applicability and
  required input contracts.
- Hydraulic calculation uses pandapipes 0.14.0 for water networks. It requires explicit nodes,
  pipes, roughness, local losses, elevations, pressure/temperature boundaries, demands and
  thresholds; returns convergence, node pressures, pipe velocity/flow/losses, mass balance,
  threshold findings and solver version.
- Hydraulic requests are durable Jobs delivered through the transactional outbox to the Celery
  compute queue. The HTTP API returns 202 and a scoped status URL; results are persisted in the
  Job row.

## Verification

- `ruff check apps/api/src tests` — passed.
- `mypy apps/api/src` — passed for 56 modules.
- Full unit suite — 129 passed, 22 integration tests deselected.
- Full live integration suite — 22 passed, 129 unit tests deselected, on the final rebuilt stack.
- Live MVT integration — passed against PostGIS 3.5, including non-empty protobuf bytes and ETag
  replay returning 304.
- Live hydraulic worker integration — passed with a two-node 200 m water network; pandapipes
  converged, mass-balance error was zero and the persisted result status was `passed`.
- Web `lint`, `typecheck` and 6 tests — passed after adding the MapLibre vector sources.
- Docker API, worker, web, PostGIS and Redis returned healthy after rebuilding the pinned images.

## Explicit limits

- The included fixture validates software integration, not a district-heating design method.
  Project pressure/velocity/clearance/grade thresholds must come from an agreed rule source.
- Hydraulic mode does not claim heat loss or energy balance; `energy_balance` is
  `not_performed`. Supply/return coupled thermal calculation, pump selection and automatic
  diameter sizing remain separate work.
- Real construction suitability still needs geology, owner approvals, shaft/portal geometry,
  bend radius, casing and temporal restrictions from the organizer or asset owner.
- Production activation remains blocked until a real delivery passes the organizer-data
  readiness playbook and an engineering reviewer approves units, topology, boundary conditions
  and comparison fixtures.
