# ADR-009: honest outcomes for bounded network refinement

- Status: accepted for staged implementation; not promoted to `stable`
- Date: 2026-09-28
- Related specification: [`CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md`](../../CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md)

## Context

The native master only proves statements about its finite model. The exact evaluator may reject a
candidate, request canonical sizing, lack enough catalog context or fail technically. Repeating an
UNKNOWN assignment forever is useless, while storing it as a conflict would be unsound.

## Decision

- The first refinement coordinator uses one monotonic deadline and withholds an explicit reserve
  from native search for candidate assembly and mandatory exact admission.
- Only `PROVEN_REJECTED` and canonical-sizing assessments carrying explanations applicable to the
  current source/rule/checker/catalog identity may enter the durable in-memory conflict store.
- Proof batches are validated before insertion and added atomically. A duplicate batch returns a
  stalled outcome; capacity exhaustion and invalid scope are technical errors.
- UNKNOWN returns `SEARCH_LIMIT_REACHED` without adding a cut. Native UNKNOWN is treated the same
  way. Only an actually infeasible master built from validated catalog constraints and proof cuts
  returns `INFEASIBLE_IN_CATALOG`.
- Java-thread interruption propagates cancellation instead of becoming an error or infeasibility.

## Consequences

The coordinator can safely find a first validated incumbent after excluding proven bad candidates.
It does not yet implement accepted-solution archive, three-role portfolio, adaptive expansion or
the production mapping between CP-SAT assignments and `FrozenNetworkCandidate`; those are required
before the new engine can replace `stable`.
