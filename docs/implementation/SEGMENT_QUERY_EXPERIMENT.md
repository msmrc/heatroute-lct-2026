# HeatRoute: reuse of segment-query preparation, 29 September 2026

## Decision and scope

Local, behavior-preserving experiment only. Reuse the current segment's endpoints and envelope
across forbidden-obstacle predicates during one `segmentAllowed` call. No global cache, geometry
mutation, approximation, changed engineering rule or increased search budget. Unsupported query
or obstacle geometry still uses the original JTS fallback; cancellation remains per predicate call.
The holder is lazy, call-local and not thread-shared. A fresh call observes changed input geometry.

Implementation: `PreparedSegmentIntersection.Query` / `intersectsPrepared` and the narrow
`OfficialRouteGeometryRules.segmentAllowed` / `Constraint.intersectsBlocked` integration.
This is shared geometry code used by HeatRoute, not a replacement planner or public switch.
Source104/105 route-shape changes and the postponed wall-frame prototype are excluded from the
isolated experiment. At measurement time nothing was committed, pushed, deployed or restarted.
The user later authorized publishing this optimization together with lazy visibility to master,
after withdrawing the subsequent preflight reachability/repair experiment. No new tests or
measurements accompany that publication; no VPS deployment is included.

## Authorized final-state checks

One focused Java 11 test gate followed by one HeatRoute execute; no retry or extra execute.
The disposable container overlays only the two production classes above, the new test class and
the unchanged official probe on immutable R15 image
`sha256:93f7c195352d5aa9f3a922217b8d144f6b1d0d2ffd9a791beec795695dff720d`.
Thus this evidence is not a test of the entire dirty checkout or source104/105 integration.

**55 tests, zero failures, errors or skips:** PreparedSegmentIntersectionTest (29),
PreparedSegmentQueryTest (6), OfficialRouteGeometryRulesTest (8), HeatRoutePlannerTest (2),
BoundedRootDemandCatalogGeneratorTest (5), FrozenNetworkEvaluatorTest (5).
New cases cover JTS/legacy equivalence, holes/multipart/tangencies/extremes, exactly one endpoint
preparation across indexed targets, fresh preparation after mutation, fallback, cancellation and
multiple nonblocking forbidden constraints. No source edits followed this successful gate.
No complete project test/build, lint/typecheck, API or browser smoke was performed.

## Single official-run comparison

Both use the same 633,402-byte official dataset, SHA256
`cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`,
144 features (56 core, 88 restriction window), 17 demand points and the same snapshot hash.
The then-current `NextGenerationRoutePlanner` is invoked directly with `Settings.initial()`, 90 s overall
and 30 s catalog budgets, depth disabled, seed 2026. The DTO's stable label does not select the
planner in this direct harness. Limits: 2 CPU, 3 GiB container, Java heap 128–2048 MiB, JFR profile.
Both are cold isolated planner calls, not API job duration or time to a successfully built network.

| Metric | R15 before | Segment query |
| --- | ---: | ---: |
| Execute wall time, s | 32.637858776 | 32.859478364 |
| Execute process CPU, s | 59.25 | 62.09 |
| Catalog time, ms | 31,062 | 31,077 |
| Route calls | 149 | 153 |
| Normal-seed attempts | 69 | 77 |
| Pairs attempted / total | 72 / 816 | 72 / 816 |
| Directed options / physical assets | 29 / 98 | 29 / 98 |
| Shared seed networks | 0 | 0 |

Both return `CATALOG_INCOMPLETE`, `budgetLimited=true`, reason
`catalog_expansion_limit:INFEASIBLE_IN_CATALOG:proven_master_infeasible`, with `result=null`.
The generated catalog hash is identical:
`ec2ac7c5f660b99590d309c807895c8b1e77d47d9ea18aaa90a39ddc9c78c984`.
All four generator-coverage flags remain false; truncations remain
`normal_seed_budget_reserved` and `shared_seed_deadline`. Both have one refinement iteration,
zero conflicts and an empty archive. `demands_covered=17` describes candidate catalog coverage,
**not 17 connected points in an accepted route**. There is no final network to compare by length
or cost, and no claim that the physical routing problem is globally infeasible.

The new wall time is 0.221619588 s higher (+0.68%), CPU 2.84 s higher (+4.79%). More attempted
searches produced no additional accepted catalog options. Therefore **no end-to-end speed gain
is established**; one deadline-limited pair is insufficient for a repeatability verdict.
Other local servers remained running; a pre-run Docker load snapshot is retained. No queued
Kozhukhovo calculations were resumed. Parallel/background host load and JIT/JFR variability
remain confounders despite identical container limits.

## Allocation evidence from the same recordings

Read-only analysis of already captured JFR files; not another benchmark. Within the main thread's
execute window, cumulative TLAB reservation was 14,680,698,608 → 11,130,529,656 bytes (−24.18%),
with TLAB-refill events 41,673 → 31,547. This is a useful allocation-pressure signal, **not peak
RAM, exact bytes attributed to each object class, or a causal speed-up percentage**. The query
unit test separately establishes four XY reads total across three indexed targets. Work attempted
and JIT behavior differ between runs, so total allocation deltas cannot all be assigned to this edit.

## Evidence and limitations

- Baseline: `.tooling/nextgen-official-20260929/evidence/`.
- This run: `.tooling/nextgen-segment-query-20260929/evidence/`, including `run.log`,
  `surefire-reports`, `result.json`, `snapshot-hashes.json`, `run-metadata.json`,
  `container-state.json`, JFR and `allocations.json`.
- Container: `heatroute-nextgen-segment-query-r15-20260929`, exit 0, no OOM.
- Recorded execute UTC: 09:19:10.577591–09:19:43.436388, 2026-09-29; elapsed above uses the
  probe's monotonic timer (not subtraction of separate UTC sampling calls).
- Code checked on HeatRoute at level Verification using source fallback; graph unavailable,
  generation N/A. Two gpt-5.6-terra helpers owned focused tests and isolated runbook/result analysis.

This small optimization was retained for the user-authorized master publication. How to improve catalog
completeness/useful shared seeds within budget, not to treat an incomplete 33-second exit as a
completed competition calculation. No N/R acceptance gate, full routing equivalence or production
readiness is closed by this experiment.
