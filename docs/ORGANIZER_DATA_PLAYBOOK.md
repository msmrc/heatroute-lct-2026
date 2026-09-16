# Organizer data playbook

The official input is one GeoJSON FeatureCollection in EPSG:4326. Do not build a second generic
mapping pipeline into P0.

## Before importing

- preserve the original file read-only outside Git;
- record filename, byte size, SHA-256, delivery date and permission to process/display;
- confirm it is the organizer contract version expected by the current build;
- never upload organizer data to public services or commit it to the repository.

## Inspection

Use `POST /api/v1/official/imports/inspect` for a non-persisting check or the web upload for a
durable import. Review counts for all seven types, duplicate IDs, geometry/type combinations,
typed references, required technical fields and CRS assumptions. Unknown values are errors or
explicit unknowns, never silently mapped defaults.

## Acceptance fixture

Before the full delivery, maintain a sanitized official-like fixture with:

- one source and a directed existing network;
- chambers and at least one interior line tie-in;
- nearby and distant future OKS with connection points;
- every restriction type and boundary cases;
- shared-trunk, separate-route and no-route outcomes.

Do not tune coordinates, IDs, route thresholds or catalog values to one delivery. A second fixture
of the same contract must run without code or manual-route changes.

## If the delivery disagrees with the appendix

Stop at import, keep the original file unchanged, record a minimal reproducible example and ask the
PM for an organizer clarification. Contract changes require versioned DTO/schema tests and a
roadmap decision; they are not handled through ad-hoc field guessing.
