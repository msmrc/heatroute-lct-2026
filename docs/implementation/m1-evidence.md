# M1 evidence — projects, versions, raw data and import

Status: complete locally on 2026-09-08.

## Delivered boundary

M1 now includes workspace-scoped projects, users, server-side sessions and roles; immutable
dataset and mapping-profile versions; content-addressed raw artifacts; durable inspected layer
versions; Raw → Staging → Canonical processing; asynchronous inspection, validation and atomic
publication; GeoJSON, GeoPackage and declared-column CSV inputs; safe typed transforms; explicit
CRS confirmation; canonical per-kind contracts; quarantine; topology references; coverage
findings; provenance APIs; and semantic cross-version diffs.

The published snapshot is append-only. A later delivery never mutates the previous version.
No geometry repair is currently offered: invalid geometry is quarantined, `MakeValid` is not
called, and `buffer(0)` is never used. Any future repair feature must add explicit before/after
evidence first.

## Acceptance matrix

| Check | Evidence |
|---|---|
| ING-01 | Live GeoJSON upload persists SHA-256, validates EPSG:4326 geometry and publishes one WGS84 canonical feature. |
| ING-02 | Live two-layer GeoPackage test maps and publishes `nodes`; `other_points` remains durable with `not_selected` status and appears in layer provenance. |
| ING-03 | A source without declared CRS cannot save its mapping until `source_crs_confirmed=true`. A declared conflicting CRS is rejected. |
| ING-04 | Geographic coordinates outside the confirmed working CRS area produce `GEOGRAPHIC_EXTENT_OUTSIDE_WORKING_CRS_AREA`; axes are never silently swapped. |
| ING-05 | Self-intersection produces a geometry issue and quarantine. Automatic repair is unavailable, so no unrecorded mutation can occur. |
| ING-06 | Every occurrence of a duplicate source ID is deterministically quarantined and reported. |
| ING-07 | A second delivery creates version 2, computes semantic added/changed/deleted/unchanged output and leaves version 1 byte-for-byte unchanged through its API. |
| ING-08 | Alternate source fields plus exact `12000 mm → 12 m` mapping normalize to an unchanged canonical feature; missing values follow policy and never become zero. |
| ING-09 | Published data without a matching `coverage_area` returns `COVERAGE_UNKNOWN`, not verified-free. |
| ING-10 | A quarantined building cannot publish without confirmation plus a persisted coverage-limitations statement. |
| ING-11 | Pyproj network access and ballpark operations are disabled; `only_best` is required, and an unavailable required operation raises a blocking validation error. |
| ING-12 | Repeated publish returns the same published version and does not duplicate canonical rows. |
| SEC-01 | Authenticated viewer sessions can read but receive 403 on project creation and dataset publication. |
| SEC-02 | Every project, import, feature and run query is filtered by the authenticated workspace; a live two-workspace test receives 404 across the boundary. |
| SEC-03 | Raw artifacts have no public download route and are resolved only from a verified workspace-scoped content address. |
| SEC-04 | Unsupported archives are rejected, uploads are size-limited, storage traversal/symlinks are rejected, and per-feature/total geometry limits are enforced. |
| SEC-05 | Mapping accepts seven declarative transforms only; Pydantic rejects executable operations and no eval/SQL/shell path exists. |
| SEC-06 | GDAL receives only a resolved local artifact path; plugin/Python VRT/SQLite extension loading is disabled and temporary files remain inside the configured artifact root. |

## Verification

- `pytest -q` — 69 passed: 57 unit and 12 live integration tests.
- `ruff check ...` — passed for application code, tests and M1 migrations.
- `mypy apps/api/src` — strict mode passed for 38 source files.
- `alembic upgrade head --sql` — complete offline PostgreSQL DDL generated through
  `20260908_0011`.
- Live PostgreSQL reports Alembic `20260908_0011`; API, PostGIS, Redis and Celery worker are
  healthy; worker smoke passed.
- OpenAPI regenerated; runtime capabilities advertise `geojson`, `gpkg` and `csv` imports.
- Web lint, typecheck, 2 tests and production build passed. The existing MapLibre bundle-size
  warning remains a later frontend optimization.

The only environment evidence not available locally is the first remote CI run after the
repository is published.
