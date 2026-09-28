# ADR-017: Unpromoted next-generation planner orchestration

## Status

Accepted for development; not promoted to the stable routing adapter.

## Context

The catalog, CP-SAT master, proof loop and frozen evaluator could be executed in tests, but there
was no production-shaped owner for their shared deadline, feature window, result diagnostics or
conversion to the existing calculation-result contract. Registering an incomplete second
`RoutingAlgorithm` would also violate the single-adapter and promotion gates.

## Decision

`NextGenerationRoutePlanner` is an internal Spring component, not a registered routing algorithm.
It builds the immutable problem, loads one deterministic routing-feature window, prepares the
bounded catalog stage, runs CP-SAT/refinement and publishes only evaluator-accepted solutions.
Window loading, catalog generation, native search and final evaluation share one monotonic time
budget. Exhausted or incomplete work remains a typed non-result and never becomes `no_route`.

The initial route generator now exempts only the selected existing-network target while reaching
the exact tie-in point. This restores the legal endpoint contact required by production features;
all other obstacles and the final exact validation remain active.

The accepted archive is passed through the existing `FinishedRouteVariantSelector`. Equal
topology/XY results are collapsed before ranking, and the output uses the existing
`OfficialCalculationResult` contract with a preview engine version. The initial planner has no
catalog expander yet, so it reports `CATALOG_INCOMPLETE` when the seed cannot be admitted.
The expansion-limit diagnostic retains the final refinement outcome and reason (for example,
proven master infeasibility) instead of hiding it behind a generic limit message.

The initial catalog now reserves a bounded tail for legal shared-corridor seeds. On the official
fixture this closes individual root-normal coverage to 17/17, but does not by itself make the
global master feasible: independently legal paths can still form an illegal combined chamber or
multiple-parent topology. This distinction is preserved in diagnostics and is not reported as an
accepted result.

## Consequences

- The full new stack can execute production-shaped data without invoking the legacy planner.
- A valid seed produces an ordinary, fully sized and costed result; failure remains diagnostic.
- The active `stable` implementation and API/job selection are unchanged.
- Targeted expansion, three-role enumeration, official-dataset/API/export gates and performance
  evidence are still required before registration or promotion.
