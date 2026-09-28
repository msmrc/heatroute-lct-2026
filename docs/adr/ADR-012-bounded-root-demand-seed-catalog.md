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
existing `OfficialObstacleRouter`. For each bounded root/demand pair it requests engineering,
left and right alternatives, including the existing normal terminal-egress and regularization
path. Millimetre geometry is deterministic, collinear overlap is compiled by
`PhysicalAssetCompiler`, and each option records source/rule/window provenance plus the probe-DU
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
- Exact frozen admission remains mandatory; the seed certificate does not replace final sizing,
  chamber, depth, economics or whole-network validation.
- Targeted shared-spine/chamber expansion, exact section assembly and mapped asset-split rebasing
  are still required before algorithm registration or performance claims.
