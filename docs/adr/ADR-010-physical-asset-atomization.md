# ADR-010: Physical asset atomization and catalog-to-master compilation

## Status

Accepted for the next-generation solver branch; not promoted to `stable`.

## Context

Several route options may share all or part of one future pipe. Treating every path as a separate
edge double-counts cost and fails to aggregate flow. Conversely, applying a generic line union to
all geometry would create free topology nodes at ordinary XY crossings and could merge different
levels or construction realizations. A zero-flow demand must also remain a mandatory connectivity
terminal even though it contributes no hydraulic flow.

JTS 1.20 already provides robust segment intersection and indexed spatial candidate lookup. It is
used for numeric geometry primitives, while HeatRoute retains the domain decision about which
segments are allowed to represent the same physical asset.

## Decision

- `PhysicalAssetCompiler` compares only paths with the same explicit physical context and
  construction mode. It uses an `STRtree` and `RobustLineIntersector` to find collinear interval
  overlaps and splits them at overlap endpoints.
- A point crossing or endpoint touch alone is not a shared asset and does not create a chamber.
  Different levels/realizations never share an identity. Opposite traversal in the same context
  reuses one canonical physical identity but retains a separate directed binding.
- `RoutingCatalogSnapshot` can declare a strict physical-asset set. In that form every option must
  reference a known, continuous asset chain which reconstructs its polyline and total length.
- `CatalogNetworkProblemCompiler` derives topology by unioning only explicit ports and adjacent
  endpoints in declared chains. It never joins nodes merely because coordinates are equal.
- `CatalogFrozenCandidateAssembler` maps a feasible assignment back to immutable route objects.
  Compatible degree-2 accounting atoms are collapsed into one continuous edge; a branch, change of
  physical context/construction mode/ДУ or another retained boundary requires an explicit port and
  node realization. Sections are supplied through an exact assembly boundary rather than inferred
  from an XY coincidence.
- The compiler uses `OfficialPipeCatalog` as the sole capacity/rate source. Its integer objective is
  a documented linearized catalog surrogate in milli-rubles; official sizing, continuous-length
  rules, rounding and final economics remain the authority of `FrozenNetworkEvaluator`.
- `NetworkConstraintProblem.Node` has an explicit mandatory-terminal flag. The master permits zero
  physical flow on a selected connection and requires every active root to serve at least one arc.

## Consequences

Shared trunks are selected and costed once in the finite master and receive aggregate flow. A
technical split preserves physical coverage, while false planar intersections remain disconnected.
Unverified diameter admission stays in the relaxation; a diameter is removed only when all source
options for that directed asset prove it forbidden. The assembled candidate uses canonical
`demand:<id>` sizing nodes and preserves source connection IDs separately. The exact evaluator and
refinement loop are still required before acceptance, and the catalog compiler is not yet the
production `stable` adapter.
