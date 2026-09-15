# ADR-002: coordinate systems

- Status: accepted
- Date: 2026-09-07

## Decision

GeoJSON request and response geometry is RFC 7946 longitude/latitude in EPSG:4326. Every
project records one confirmed metric working CRS and the exact pyproj transformation used.
Computational geometry uses that CRS. EPSG:3857 is display-only.

Canonical persistence starts with a WGS84 PostGIS geometry column. Metric derivatives are
reproducible caches keyed by dataset version, project CRS and transform metadata; M1 may add
materialized metric geometry after measured query profiling.

## Consequences

`ST_SetSRID` is never used as reprojection. Missing grids or an unconfirmed source CRS block
publication instead of silently selecting a ballpark transform.

