# ADR-011: additive adaptive catalog expansion under one deadline

- Status: accepted for staged implementation; not promoted to `stable`
- Date: 2026-09-28
- Related specification: [`NETWORK_SOLVER_SPEC.md`](../../NETWORK_SOLVER_SPEC.md)

## Context

The fast master must start from a compact catalog. A missing canonical diameter, UNKNOWN exact
context or infeasibility of an incomplete finite catalog cannot become a geographic `no_route`.
At the same time, replacing asset/model semantics during expansion would make incumbents and
stored conflict proofs unsound.

## Decision

- `AdaptiveCatalogNetworkSearch` owns one monotonic deadline, a final-work reserve, per-catalog
  refinement caps and a bounded expansion count.
- An incomplete stage may invoke an addressable `AdaptiveCatalogExpander`. Exhausted expansion
  returns `CATALOG_INCOMPLETE`; a complete model alone may return catalog infeasibility.
- The first implementation accepts only strictly additive expansion. Source/rules stay fixed;
  existing physical assets and path options retain fingerprints; old nodes, assets, capacities,
  costs, demands and validated static conflicts retain semantics; old decision keys remain present.
- `ConflictStore` and `AcceptedSolutionArchive` survive stage changes. Version scopes decide which
  cuts remain active, and a previously verified archive incumbent is returned even when a later
  stage ends incomplete or on a limit.
- Cancellation propagates. Expansion exceptions and non-monotonic replacements are typed errors,
  never infeasibility proofs.

## Consequences

The solver can add a missing DU or alternative without rebuilding an unbounded city graph up front,
while preserving auditable state. Production window/terminal/chamber expanders are still required.
Asset splitting at a new junction needs an explicit old→new reproducibility mapping and is rejected
by this additive-only foundation until that mapping is implemented and verified.
