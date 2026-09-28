# ADR-012: Bounded root-to-demand seed catalog

## Status

Accepted for the unpromoted next-generation solver.

## Context

The finite-catalog master needs a useful first geometry set without paying for the complete
42–73-minute legacy planner. A new shortest-path implementation would duplicate mature obstacle,
clearance, terminal-egress, crossing and route-cache behavior while still not solving the joint
network/chamber problem. A bounded seed is useful only if budget exhaustion cannot be reported as
geographic infeasibility.

## Decision

`BoundedRootDemandCatalogGenerator` reuses one prepared `OfficialRoutingEnvironment` and the
existing `OfficialObstacleRouter`. It first probes the nearest root for every demand in
deterministic round-robin order, using one engineering route per pair. Only after terminal
coverage does it spend the remaining budget on alternatives. Uncovered terminals receive a
bounded richer retry, so one difficult pair cannot starve all later demands.

The generator also adds bounded root-normal seeds. These reuse the existing chamber-approach and
terminal-egress primitives and fix both endpoint rays before obstacle routing; arbitrary oblique
root contact is not presented as a selectable chamber configuration. Millimetre geometry is
deterministic, duplicate paths are collapsed, and collinear overlap is compiled by
`PhysicalAssetCompiler`. Each option records source/rule/window provenance plus the probe-DU
admission result.

Pair, route-call, egress, path-count and wall-clock budgets are explicit. Truncation and unrouted
pairs remain `CATALOG_INCOMPLETE`. The generator always declares shared-network seeds and chamber
configurations uncovered; it is a fast initial star/overlap graph, not a proof-complete city graph.
Flow beyond the pipe catalog is also remaining work rather than a false no-route result.

## Consequences

- The new master can receive real obstacle-aware geometry without invoking the full old planner.
- Existing spatial preparation, visibility caches and engineering rules are reused instead of
  introducing a second routing rule implementation.
- Coincident path segments become shared physical assets and can form a lower-cost common trunk.
- Counters distinguish unique pair attempts, recovery attempts, covered demands and demands with
  a root-normal seed, making time-budget regressions visible.
- Exact frozen admission remains mandatory; the seed certificate does not replace final sizing,
  chamber, depth, economics or whole-network validation.
- Targeted shared-spine/chamber expansion, exact section assembly and mapped asset-split rebasing
  are still required before algorithm registration or performance claims.

## Official-dataset diagnostic (2026-09-28)

With a 30-second catalog budget, coverage-first generation represented all 17 official demands;
the earlier root-major order left one demand uncovered. The same run found strict root-normal
seeds for 14 demands, produced an optimal feasible base flow graph, and then correctly reported
the configured master as incomplete/infeasible. This is evidence that the next required stage is
shared-network/corridor seed generation for the three difficult terminals, not weaker chamber
rules or a larger blind root-to-demand sweep.
