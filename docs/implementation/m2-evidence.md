# M2 implementation evidence

Updated: 2026-09-08

M2 is complete locally. This note records the verified rule/topology/geometry slice and the
live vertical proof.

## Implemented and verified

- `NetworkGraph` uses explicit `from_node_id` / `to_node_id` references. XY intersections and
  nearby endpoints never create hidden junctions. Ambiguous XY intersections are reported while
  legitimate cycles remain valid.
- Topology diagnostics cover duplicate IDs, missing endpoints, zero-length edges, endpoint
  geometry mismatch, isolated nodes, connected components and circuit mismatch.
- Candidate screening retains every rejection reason, orders only eligible explicit candidates,
  distinguishes net from gross capacity, never converts unknown capacity to zero/infinity, and
  requires a named assumption for unknown permissions in exploratory mode.
- The typed `RuleProfile` registry rejects unknown rule types and unsafe message placeholders.
  Every P0 rule type has an explicitly registered evaluator.
- Immutable RuleProfile versions are stored with a canonical SHA-256. Create/revise/read/list API
  operations are workspace/project scoped and revisions use optimistic `If-Match` checks.
- Scenario revisions pin a RuleProfile version ID. Preflight checks scope, schema and stored hash;
  the worker independently evaluates the final swept corridor and persists the selected
  definition ID/version in `validation_report`.
- Scenario revisions also pin published DatasetVersion IDs and a planning date. Preflight rejects
  missing, foreign, duplicate, unpublished and working-CRS-mismatched selections, then materializes
  their canonical features and surfaces topology errors before a run is accepted.
- CalculationRun persists a hashed `versions_snapshot` containing the project revision, scenario
  input hash, dataset raw/transform/mapping identity, RuleProfile hash and complete candidate
  screening decisions. Rejected candidates and their reasons are retained.
- Canonical hard exclusions are used directly by grid neighbor expansion with exact Shapely
  geometries; their bounds expand the modeled AOI before the configured search buffer is applied.
  The result is independently checked again against canonical features, portals and coverage.
- Exact Shapely predicates cover corridor width, polygon holes, MultiPolygon gaps, named road
  portals, building clearance, pre-buffered clearance, bounded entry gates/connectors and
  unrelated obstacles along the connector.
- Coverage is never inferred from an empty layer. Partial/unknown coverage remains unverified;
  strict mode blocks it, while exploratory mode requires an explicit named assumption. Overlapping
  gaps are unioned before measuring `unverified_length_m`.
- A 2D utility crossing with unknown depth is `insufficient_data`; it is never treated as proven
  vertical separation. Temporal rules reject features unavailable on the planning date.
- `building_to_network` is a validated scenario mode. The worker resolves the selected eligible
  candidate to its explicit network node, resolves the named gate to the target building, routes
  to the gate approach, validates the bounded connector and includes that connector in the final
  centerline, corridor and route length.
- `POST /scenario-revisions/{id}/validate-route` independently revalidates user-edited GeoJSON.
  It returns an explicit geometry status and structured findings with rule/feature/source IDs,
  measurements, missing fields, assumptions and conflict geometry in WGS84.
- Valid named portal crossings produce a single `portal_crossing` quantity event per
  rule/feature/portal tuple, irrespective of how many grid steps lie inside the footprint.
- Touch behavior is profile-controlled and tolerance-aware. The demo profile forbids boundary
  contact except for its named entry exception; `allow_boundary_touch` is separately tested.

## Acceptance coverage in this slice

| Area | Covered cases |
|---|---|
| Topology/candidates | TOP-01 through TOP-10 |
| Corridor geometry | GEO-01 through GEO-14 |
| Crossings/coverage | CRS-01 through CRS-08 |
| Rule persistence | immutable version history, stale-write rejection, invalid-type rejection, scenario pinning, worker report provenance |

The executable mapping is direct: algorithm fixtures cover GEO-01/02/03/11; focused routing and
constraint tests cover GEO-04/05/06/07/08/09/10; live scenario tests cover GEO-12/13; metric
materialization covers GEO-14. `test_network_graph.py` covers TOP-01…10 and
`test_constraints.py` covers CRS-01…08, including the one-event portal quantity hook.

## Live evidence

- Alembic is at `20260908_0013` in the Compose PostgreSQL/PostGIS database.
- Full Python suite: 111 passed (96 unit and 15 live integration tests).
- Rule profile live integration creates revision 1, creates revision 2 with `If-Match`, rejects a
  stale write, pins revision 1 in a scenario, passes preflight and completes a Celery calculation
  with the pinned rule identity in the persisted validation report.
- Worker smoke confirms PostgreSQL, PostGIS 3.5 and Redis connectivity.
- Ruff, strict mypy, OpenAPI generation, web lint/typecheck/tests/build all pass. Vite still reports
  the pre-existing large MapLibre bundle warning.
- The live building-to-network fixture routes from candidate `C1` / node `N1`, detours around a
  canonical building, reaches gate `G1`, includes the bounded connector to the entry point and
  persists an independently valid alternative. A straight manual edit through the blocker is
  rejected and returns the exact conflicting polygon through the API.

Map rendering of finding geometries is tracked under M4; the M2 server contract already exposes
the complete geometries. Applying a monetary catalog rate to crossing events remains M3/CST work.
