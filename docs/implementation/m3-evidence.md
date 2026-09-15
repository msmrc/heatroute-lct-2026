# M3 evidence — routing, costs and durable jobs

Date: 2026-09-08

## Outcome

M3 is complete locally. A calculation is now an immutable, replayable pipeline rather than a
single demo line: preflight pins dataset/rule/cost versions, a lazy `GraphProvider` runs A* or
Dijkstra over `(cell, heading, construction mode, waypoint index)`, each accepted centerline is
independently validated, and quantities/costs are recomputed only from that validated geometry.
Results, events, progress, assumptions, runtime versions and optimality scope are persisted.

The implementation deliberately keeps the implicit graph custom. NetworkX provides proven
shortest-path algorithms for materialized graphs and OR-Tools provides routing dimensions and
limits, but neither replaces HeatRoute's lazy swept-corridor predicates and compound state without
duplicating the domain model. Existing Shapely, PyProj, SQLAlchemy, FastAPI and Celery mechanisms
remain the integration boundary; no new runtime dependency was added.

## Acceptance mapping

| Cases | Evidence |
|---|---|
| RTE-01…03 | Fixture and seeded random comparisons prove A*/Dijkstra cost equality; unknown cost lower bound uses `h=0`; negative rates/edges are rejected. |
| RTE-04…05 | Heading, construction mode and ordered waypoint progress are state dimensions; ordered visits and final direction changes are covered. |
| RTE-06…08 | Cost zones select a longer cheaper route; identical objective geometries merge their tags; the two-corridor fixture returns two hashes and stops before a third. |
| RTE-09…10 | Exhausted bounded graph returns `no_route_in_model` with cause/resolution/AOI metadata; state/time/memory limits return `budget_exceeded`, never no-route. |
| RTE-11…13 | Cache key includes scenario, algorithm and hashed dataset/rule/cost manifest; changed cost revision misses cache; search penalty never becomes a catalog line; replay is deterministic. |
| RTE-14…15 | Candidate subset is recorded separately from non-additive `candidate_reranking`; coarse failure can run the configured refinement and records both resolutions. |
| CST-01…03 | A 100 m paired corridor stays a 100 m route, emits 200 m of explicit pipe quantity, counts one crossing event and has contiguous non-overlapping method segments. |
| CST-04…05 | Missing rates produce `partial` plus `unpriced_items`; all quantities and money use `Decimal` with exact half-up fixtures. |
| CST-06 | Synthetic status is visible in API data and catalog exports in JSON, CSV and HTML. |
| CST-07…08 | Different catalog models are non-comparable; zero baseline never divides by zero or invents a percentage. |
| CST-09…10 | Replacing geometry rebuilds quantities/cost without stale keys; fixed event/item rates set `optimality_scope=candidate_reranking`. |
| JOB-01…04 | HTTP idempotency and body conflict tests pass; transactional outbox recovers a broker outage; terminal duplicate delivery creates no duplicate alternatives. |
| JOB-05…06 | Durable lease/attempt recovery creates a retry outbox event; cooperative cancellation retains event history and ends `cancelled`. |
| JOB-07…08 | `GET /runs/{id}` restores persisted progress after refresh; ordered polling and SSE `Last-Event-ID` reconnect return all later significant events. |
| JOB-09…10 | Configured queue capacity returns HTTP 429 with `Retry-After`; state/time/memory budgets are diagnostic and only independently validated partial routes can persist. |

The detailed regressions are in `tests/unit/test_routing.py`,
`tests/unit/test_m3_routing_costing.py`, `tests/integration/test_scenario_workflow.py`,
`tests/integration/test_m3_workflow.py` and `tests/integration/test_versions_snapshot.py`.

## Live artifacts

- Alembic head: `20260908_0014`.
- CLI-generated passport: `artifacts/m3/run-passport.json`, generated from persisted run
  `a81f199c-80f2-414b-af38-6dc31a694c47`.
- CLI `start-run <revision-id> --wait 30` completed persisted run
  `d193a5a8-b620-4acd-8e9a-1cf047053492` through the outbox/worker path.
- Benchmark JSON: `artifacts/benchmarks/m3-routing.json`.
- OpenAPI: `packages/api-client/openapi.json`.

## First benchmark

This is a correctness-oriented local microbenchmark on the current Windows host under Python
`tracemalloc`; it is not a city-scale performance claim.

| Fixture | Algorithm | Cost | Expanded | Time, ms | Python peak, MiB |
|---|---:|---:|---:|---:|---:|
| 20 × 20 m | A* | 28.9705627485 | 602 | 519.809 | 0.898 |
| 20 × 20 m | Dijkstra | 28.9705627485 | 1,744 | 1,495.296 | 2.091 |
| 40 × 40 m | A* | 60.1837661841 | 3,137 | 6,155.935 | 4.242 |
| 40 × 40 m | Dijkstra | 60.1837661841 | 8,766 | 12,069.062 | 11.814 |

Both algorithms returned the same optimum. The measurement confirms that the compound heading
state is expensive enough to justify the existing budgets and lazy expansion; it does not justify
extrapolating these numbers to a full city grid.

## Deliberate boundaries

- Hydraulics, elevations and construction suitability remain explicitly `not_performed`.
- Cost catalogs are preliminary models with required provenance/status; the bundled catalog is
  visibly synthetic and cannot be presented as a commercial estimate.
- `selected_candidates` is not a global network optimum, and fixed/non-additive rates are reported
  as candidate reranking rather than an exact additive optimum.
- Full result export packaging remains M5; M3 includes the required catalog JSON/CSV/HTML evidence
  and the CLI run passport.
