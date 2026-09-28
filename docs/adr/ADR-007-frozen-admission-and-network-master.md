# ADR-007: frozen admission boundary and finite-catalog network master

- Status: accepted for staged implementation; not promoted to `stable`
- Date: 2026-09-28
- Related specification: [`CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md`](../../CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md)

## Context

The next-generation engine must combine topology, shared physical assets, flow and diameter
choices without allowing a discrete optimum to bypass the existing engineering calculations.
Geometry objects and imported attributes are mutable, while the legacy planner may repair a draft
during finishing. A master solution therefore cannot itself be treated as an exportable network.

## Decision

- A `FrozenNetworkCandidate` owns copies of mutable imported geometry/attributes and records a
  deterministic hash of node/edge topology and XY coordinates.
- `FrozenNetworkEvaluator` is the admission boundary. It invokes the existing canonical network
  sizer, optional depth planner, obstacle, chamber and bend validators, and economics calculator.
  It may add derived flow, diameter and depth data, but any topology/XY change is an internal error.
- Only the evaluator can construct an `AcceptedNetworkSolution`. Canonical sizing feedback,
  proven rejection, UNKNOWN and technical ERROR are separate outcomes; only a proven conflict may
  become a later refinement cut.
- `CpSatNetworkOptimizer` is an exact optimizer only for an immutable finite catalog. It models
  mandatory demand coverage, acyclic directed topology, multiple allowed roots, conserved integer
  flow, one capacity/diameter choice per selected physical asset, shared cost once and declared
  pair/hyper-conflicts.
- The new components remain outside `stable` until certified catalog generation, refinement,
  lifecycle, official dataset, performance, resource and release gates are complete.

## Consequences

The architecture makes the safety boundary explicit: a fast CP-SAT answer is a proposal, not an
engineering result. Exact official services remain the owners of normative sizing, geometry,
depth and cost. Catalog completeness limits the meaning of optimality, so reports must say
“optimal in catalog” rather than “globally optimal route”. The extra immutable copies and exact
evaluation cost memory and CPU; later work must bound the catalog and feature windows and measure
them on the target Linux image.
