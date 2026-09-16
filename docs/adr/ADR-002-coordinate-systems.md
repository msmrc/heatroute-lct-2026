# ADR-002: coordinate systems

- Status: amended for the official contract
- Date: 2026-09-15

GeoJSON request and response geometry is RFC 7946 longitude/latitude in EPSG:4326. All metric
geometry uses fixed EPSG:32637 as required by the task. Import persists both SRIDs and indexes both
geometry columns. Java transforms through Proj4J; PostGIS may verify but must not substitute
`ST_SetSRID` for reprojection. Lengths, buffers, splits, distances and crossing angles are never
computed in degrees or display-only EPSG:3857.
