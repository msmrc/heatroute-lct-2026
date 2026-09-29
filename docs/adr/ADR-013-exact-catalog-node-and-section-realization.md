# ADR-013: Exact catalog node and section realization

## Status

Accepted for the HeatRoute network solver.

## Context

The finite master operates on bookkeeping nodes and physical atoms, while the engineering
evaluator requires exact `RouteNode` semantics and complete base/special construction sections.
Guessing a root type or free chamber from coordinates would change chamber incidence and cost.
Likewise, treating a failed section reconstruction as a technical crash would prevent targeted
catalog expansion and could discard an otherwise useful verified incumbent.

## Decision

Exact root node metadata (node type, chamber flag, base incidence, target and optional existing
diameter) is part of `RoutingProblemSnapshot` and its semantic hash. The production endpoint
resolver maps only declared root/demand ports; it rejects unresolved roots and never invents a
branch chamber.

`CatalogEdgeSectionAssemblerFactory` freezes and prepares one feature environment per stage. It
reconstructs sections for the actual selected diameter and upstream target by reusing
`completeCheckedCorridorAssembly`. A hybrid combination that cannot form a legal complete special
crossing keeps its frozen coordinates with neutral base sections, so the independent validator
rejects the exact assignment. Assembly calls and fallbacks are counted.

Missing chamber configuration or node realization raises a typed candidate-incomplete outcome.
The refinement coordinator returns a catalog-expansion request without a proof cut; cancellation
and real technical failures retain their existing meanings.

## Consequences

- Published root/chamber semantics cannot differ from the immutable input identity.
- Special crossing economics and validation use sections reconstructed at canonical selected DU.
- Missing chamber options do not become free nodes, false no-route proofs or terminal errors.
- The snapshot builder still must populate exact root metadata, and N03 must generate explicit
  branch chamber configurations before shared-tree candidates can be admitted.
