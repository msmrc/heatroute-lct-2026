# ADR-004: contract clarifications for P0

- Status: accepted for initial implementation
- Date: 2026-09-07

## Decision

- `source_namespace` is a required canonical feature property. The demo namespace is
  `heatroute-demo`; uniqueness is `(dataset_version_id, source_namespace, source_id)`.
- `NetworkEdge.status` is named `operational_status`; it is distinct from the shared temporal
  `lifecycle_status` and may be `active`, `inactive`, `unknown` or `not_applicable`.
- Headings use the string enum `n`, `ne`, `e`, `se`, `s`, `sw`, `w`, `nw`; entry approach
  constraints use the same enum. Numeric grid indices are internal only.
- `search_settings.budget` contains `max_wall_time_s`, `max_expanded_states`,
  `max_memory_mb`, `max_candidates` and `max_alternatives`. No duplicate limit fields exist.
- Rule period and message template are nullable only for `demo` profiles; production draft
  and reviewed profiles require them or explicit non-applicability.
- Quantity models are immutable versioned inputs. `paired_corridor_v1` counts two pipe metres
  per corridor metre while trench and crossing quantities remain corridor/event based.

The complete DTOs and generated OpenAPI are authoritative over prose lists once implemented.
Fixtures must be migrated and checked by those DTOs before M1 is complete.

## Milestone acceptance correction

M2 completes the geometry predicates and validator portions of GEO/TOP/CRS. Solver-, run- and
cache-dependent cases close in M3. M4 closes the UI portion of E2E-01 through E2E-04; export
steps close in M5. This preserves the final P0 acceptance set without impossible intermediate
gates.

