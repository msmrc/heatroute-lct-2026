# ADR-009: honest outcomes for bounded network refinement

- Status: accepted for staged implementation; not promoted to `stable`
- Date: 2026-09-28
- Related specification: [`NETWORK_SOLVER_SPEC.md`](../../NETWORK_SOLVER_SPEC.md)

## Context

The native master only proves statements about its finite model. The exact evaluator may reject a
candidate, request canonical sizing, lack enough catalog context or fail technically. Repeating an
UNKNOWN assignment forever is useless, while storing it as a conflict would be unsound.

## Decision

- The first refinement coordinator uses one monotonic deadline and withholds an explicit reserve
  from native search for candidate assembly and mandatory exact admission.
- Each refinement iteration asks CP-SAT for the first feasible incumbent and stops native search
  from its solution callback. This starts exact engineering admission immediately instead of
  spending the whole iteration proving the surrogate objective optimum. The ordinary optimizer
  API still performs bounded optimization for later portfolio/final-ranking stages.
- Only `PROVEN_REJECTED` and canonical-sizing assessments carrying explanations applicable to the
  current source/rule/checker/catalog identity may enter the durable in-memory conflict store.
- A proven engineering rejection may exclude the complete current Boolean assignment. Canonical
  sizing uses a distinct same-catalog scope: all topology/root Booleans are fixed, current diameter
  choices are omitted and a false literal for the required diameter expresses the implication.
  The scope is inactive after catalog/topology expansion. If the required diameter is not present,
  no proof is stored and the coordinator reports incomplete catalog context.
- Proof batches are validated before insertion and added atomically. A duplicate batch returns a
  stalled outcome; capacity exhaustion and invalid scope are technical errors.
- UNKNOWN returns `SEARCH_LIMIT_REACHED` without adding a cut. Native UNKNOWN is treated the same
  way. Only an actually infeasible master built from validated catalog constraints and proof cuts
  returns `INFEASIBLE_IN_CATALOG`.
- Java-thread interruption propagates cancellation instead of becoming an error or infeasibility.

## Consequences

The coordinator can safely find a first validated incumbent after excluding proven bad candidates,
without confusing its internal callback stop with user cancellation. The production frozen mapping
and bounded accepted archive are implemented. Three-role portfolio, adaptive expansion and the
remaining catalog/section wiring are still required before the new engine can replace `stable`.
