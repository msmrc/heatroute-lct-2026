# Official data contracts

## Input

`POST /api/v1/official/imports` accepts one multipart field `file` containing a GeoJSON
`FeatureCollection` in EPSG:4326. Current Java inspection streams features and rejects duplicate
IDs, invalid geometry/property combinations and broken typed references. Contract v2 reports an
`input_profile` and separates blocking `errors` from non-blocking `warnings`.

| `object_type` | Geometry | Role |
|---|---|---|
| `source` | Point | Heat source |
| `heat_network` | LineString | Existing network section |
| `heat_chamber` | Point | Existing chamber |
| `oks_future` | Polygon/MultiPolygon | Future building |
| `oks_connection_point` | Point | Required OKS connection point |
| `oks_existing` | Polygon/MultiPolygon | Existing building / exclusion |
| `restriction` | type-dependent | Spatial restriction |

The official technical fields are `id`, `object_type`, `diameter`, `flow_tph`, `heat_load`,
`oks_id`, `restriction_type` and `upstream_object_id`, required according to object type.
Unknown or missing required values must produce localized errors with feature index/ID and field.

The supplied 2026-09-16 dataset does not match that published table. It is accepted through the
explicit `provided_dataset_compatibility` profile: numeric IDs are normalized, connection points
carry their own demand, and missing existing-network reconstruction fields are warnings. No
missing engineering value is silently invented. See `implementation/SUPPLIED_DATASET_AUDIT.md`.

## Current Java API

- `GET /api/v1/health/live`
- `GET /api/v1/health/ready`
- `POST /api/v1/official/imports`
- `GET /api/v1/official/imports/{id}`
- `GET /api/v1/official/imports/{id}/topology`
- `POST /api/v1/official/imports/{id}/jobs/topology`
- `GET /api/v1/official/jobs/{id}`
- `DELETE /api/v1/official/jobs/{id}`
- `/swagger-ui.html` and `/v3/api-docs`

## Required output

One GeoJSON FeatureCollection per result containing only these seven types:

1. `heat_network`;
2. `tie_in`;
3. `heat_network_reconstruction`;
4. `heat_chamber`;
5. `heat_chamber_reconstruction`;
6. `technical_node`;
7. `variant_summary` — exactly one non-spatial summary per variant.

No unrelated fields with `null` are permitted. The exact per-type schema, component costs,
variant/rank references and Z fields for the optional depth task must be copied from the organizer
technical appendix into JSON Schema tests before R7 is marked complete.

## Coordinate and size rules

- API geometry: EPSG:4326; metric calculations: EPSG:32637.
- Length, clearance, split and angle operations are performed in the projected CRS.
- Upload limit: 3 GB; output limit: 500 MB; processing and export are streaming.
- Every result records input SHA-256, contract/catalog/algorithm versions and assumptions.
