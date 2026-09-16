# R4 multi-demand routing — first vertical slice evidence

**Date:** 2026-09-16
**Algorithm version:** `r4-heuristic-1`

## Implemented

- Immutable `official_runs` aggregate tied to the exact import SHA-256 and algorithm version.
- Durable `calculation` jobs use the existing PostgreSQL claim/lease/cancel mechanism.
- Every demand connection point from one import is handled in one run.
- Deterministic independent strategy and a greedy shared-trunk strategy for nearby demands with a
  common feasible tie-in.
- Existing-chamber capacity accounts for pre-existing incident sections; new line tie-ins create a
  chamber root.
- A demand without candidates is returned as `no_route` without discarding connected demands.
- Normalized nodes, directed edges, connection outcomes and total metric length are byte-stable for
  the same input and algorithm version.
- A separate validator rechecks duplicate IDs, unknown endpoints, multiple upstream edges, cycles,
  incomplete upstream paths, branches without chambers, chamber degree above four and crossings
  outside a common node.
- Candidate selection tries farther R3 tie-ins when the nearest straight connection would cross an
  already accepted new section; if every candidate conflicts, only that demand becomes no-route.
- The official UI can queue the full run and display its immutable run/job provenance and result.
- Completed runs render on an OpenLayers/OpenStreetMap GIS view. Route nodes are transformed from
  EPSG:32637, while a bounded Java/PostGIS endpoint returns intersecting official WGS84 restrictions,
  heat-network sections, chambers, sources and connection points. Each source/result layer can be
  toggled and map objects can be inspected; the previous metric schematic remains available.

## Automated evidence

- Nearby demands choose a shorter shared trunk.
- Distant demands with disjoint tie-ins stay separate.
- One impossible demand produces a partial result.
- Repeated execution produces identical serialized topology.
- Negative validator cases cover cycle, multiple upstreams, branch without chamber, degree overflow
  and an interior crossing.
- A positive case proves that a farther non-crossing tie-in is selected instead of the nearest
  crossing candidate.
- CI uploads the sole official dataset, creates a run, waits for the durable worker and asserts 17
  demands, at least one produced variant and a non-null independently valid preferred variant.

## Production evidence

Run `660c203d-287a-4fb7-bc8a-eb3debe5f1c0` on the untouched organizer import completed on worker
attempt 1. The independent strategy connected 16 demands and preserved demand `9` as explicit
`NO_NON_CROSSING_ROUTE`. The preferred shared strategy connected all 17 demands with 21 sections,
3,448.671 m total length and zero structural validation issues.

## Not yet an R4 completion claim

The first slice uses straight metric sections between demand, shared junction and selected tie-in.
It does not yet search around official forbidden buffers or build special passages; those tasks are
shared with R6. It currently produces the independent baseline plus one shared heuristic variant,
not three fully diverse alternatives. A variant rejected by the independent validator is retained
for diagnostics but cannot become preferred.

The GIS view proves coordinate placement and source/result comparison only. It does not make the
straight sections obstacle-aware and is not the strict seven-type R7 deliverable. Standard OSM tiles
are a best-effort external basemap; the route and official vector layers remain usable independently.

R4 closes only after obstacle-aware route search, route normalization/local improvement and the
remaining alternative-diversity gate pass on official-like boundary cases.
