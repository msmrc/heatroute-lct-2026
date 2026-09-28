# ADR-008: versioned path certificates and sound conflict scopes

- Status: accepted for staged implementation; not promoted to `stable`
- Date: 2026-09-28
- Related specification: [`CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md`](../../CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md)

## Context

A bounded router can fail to find a path without proving that no path exists. Likewise, a
geometric conflict found in one finite catalog may cease to describe a full assignment after a new
junction or alternative is added. Treating either case as a permanent positive-only no-good could
remove valid networks and produce a false `no_route` result.

## Decision

- Compact problem snapshots reject missing demand flow, retain explicit zero-flow terminals and
  identify the immutable source, rules, cost catalog, parameters and feature-window version.
- Directed path geometry is stored with millimetre coordinates, endpoint context, ordered physical
  assets, section ranges and provenance. Mutable JTS/JSON objects are not exposed.
- Path admission is certified separately for every direction/context/ДУ and source/rule version.
  Absence or mismatch returns `UNCHECKED`, never `PROVEN_FORBIDDEN`.
- Conflict explanations contain positive or negative asset, root and diameter literals plus
  source/rule/checker/catalog scope and evidence references. A full-catalog assignment is reusable only
  when its literals cover exactly the current decision identities. Stable local conflicts may be
  reused after expansion only while every referenced identity remains.
- The conflict store is bounded. Reaching its capacity is a typed resource failure; it does not
  silently discard engineering constraints or reinterpret UNKNOWN as infeasibility.

## Consequences

The master can learn useful conflicts across repeated solves without inheriting stale full
assignments. Catalog expansion must create a new catalog hash and retain or remap physical
identities explicitly. The contracts do not yet generate paths or run the complete refinement
loop; those remain required before integration with `stable`.
