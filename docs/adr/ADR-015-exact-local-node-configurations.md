# ADR-015: Exact local node configurations in the network master

## Status

Accepted for the unpromoted next-generation solver.

## Context

An arborescence and a degree limit do not prove that a selected junction is physically buildable.
The master must not combine individually valid arcs into a branch with duplicated or oblique
chamber rays, turn a demand endpoint into a transit junction, or reuse an occupied existing-root
ray. Deferring every such combination to the final evaluator creates avoidable solve/cut cycles.

## Decision

Every node in an executable catalog stage is configuration-managed. A configuration identifies the
exact set of incident directed arcs. CP-SAT selects exactly one configuration when the node is used
and equates every incident arc variable to the sum of configurations that contain it. The selected
configuration is a first-class decision literal included in catalog identity, proof scope,
assignment hashing and additive-expansion checks.

The bounded compiler generates endpoint configurations, degree-two technical transitions and
degree-three/four branch chambers. Branch and existing-root configurations reuse the active
`ExpertChamberGeometryRules.compatibleRays` oracle; root capacity includes immutable existing
directions. Enumeration and retained configurations have explicit per-node and global limits.
Exceeding a limit yields catalog-incomplete preparation rather than a false no-route proof.

The frozen assembler realizes the selected branch as `new_branch_chamber` and preserves exact
master incidence. The independent evaluator still checks full approach spacing, final DU, depth,
obstacles and economics.

## Consequences

- Shared trunks with an orthogonal T-junction can pass the full CP-SAT-to-evaluator path.
- Oblique/duplicate rays and transit demand nodes are excluded before exact evaluation.
- Proofs and canonical-sizing implications cannot silently ignore a configuration decision.
- Alternative chamber positions, relocation helpers and DU-dependent approach variants remain
  targeted catalog-expansion work; local fixed-coordinate configurations do not close N03/N05.
