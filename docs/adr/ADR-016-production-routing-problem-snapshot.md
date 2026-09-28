# ADR-016: Production construction of the routing problem snapshot

## Status

Accepted for the unpromoted next-generation solver.

## Context

The finite-catalog pipeline previously received `RoutingProblemSnapshot` only from tests and direct
callers. The normal calculation adapter did not pass import identity to a routing algorithm, and the
bounded calculation-core query omitted `oks_future`. Consequently an extended input whose flow was
stored on the linked future OKS could be interpreted as zero by the legacy resolver and could not be
represented honestly by the new solver.

## Decision

`OfficialCalculationService` now passes an immutable `RoutingExecutionContext` containing import ID,
source SHA-256, input contract version and profile. The stable adapter consumes only the profile, so
this contract change does not promote or invoke the next-generation solver.

`RoutingProblemFactory` converts the bounded production core into a deterministic snapshot. It keeps
connection points as distinct demands, resolves flow first on the connection and then on its linked
`oks_future`, preserves explicit zero and rejects a missing mandatory flow. It derives unique roots
from topology tie-in candidates and reuses `ExistingNetworkSupportIndex` for exact existing rays,
incidence and support diameter. Snapshot rule, cost and schema versions are explicit constants.

The calculation-core repository query includes `oks_future`; restrictions and large existing OKS
geometries remain lazy window reads.

## Consequences

- A production import can be transformed into the same immutable contract used by catalog tests.
- Changes in import bytes, profile, parameters, roots, flows or active versions change snapshot
  identity.
- Missing demand flow is input incompleteness, not an invented zero-flow demand.
- Promotion remains blocked: a production `NextGenerationRoutePlanner`, portfolio integration and
  the N00-N11 gates are still required before the stable adapter may delegate to this snapshot path.
