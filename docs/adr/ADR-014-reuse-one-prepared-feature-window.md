# ADR-014: Reuse one prepared feature window per catalog stage

## Status

Accepted for the unpromoted next-generation solver.

## Context

Catalog generation, special-section assembly and frozen admission read the same immutable feature
window. Preparing obstacle constraints and route caches independently for every phase/candidate
duplicates geometry, spatial indexes and visibility work on the critical path.

## Decision

`PreparedRoutingFeatureWindow` owns one deep-copied, deterministically ordered feature snapshot and
one `OfficialRoutingEnvironment`. `BoundedCatalogNetworkStageFactory` creates it once and passes it
to the bounded path generator, section assembler and compiled candidate factory. The frozen
candidate still owns its own feature copy for mutation isolation, but carries the prepared
environment for exact evaluation; the evaluator falls back to its old local preparation only for
legacy/directly constructed candidates.

An empty bounded catalog remains an incomplete preparation without constructing an invalid empty
master. The single-window path is opt-in to the next-generation stage and does not change the
stable planner.

## Consequences

- Obstacle/crossing indexes and route caches are prepared once rather than once per phase and
  once again per evaluated assignment.
- Generator, section reconstruction and exact validator operate on the same source/rule snapshot.
- Existing tests and direct candidate construction retain the isolated fallback path.
- Per-candidate feature copies remain a measurable cost; removing them would require an immutable
  feature DTO that cannot expose mutable JTS/Jackson objects.
