# Official data contracts

For the active supplied-dataset interpretation, read
`implementation/ORGANIZER_VIDEO_CLARIFICATIONS.md`. Exact PDF/DOCX fields remain the strict
profile, while the later organizer Q&A permits a reduced result without existing-network
reconstruction. The reduced output whitelist still requires written confirmation; until then the
implementation must not fabricate missing reconstruction fields.

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

The machine-readable Draft 2020-12 contracts are versioned in `docs/contracts`. The strict input,
the explicitly separate supplied-dataset compatibility profile and the strict output are compiled
by a standards-compliant validator in CI. JSON Schema covers per-feature shape and scalar rules;
the streaming Java validators additionally enforce uniqueness, references, topology and totals.

## Current Java API

- `GET /api/v1/health/live`
- `GET /api/v1/health/ready`
- `POST /api/v1/official/imports`
- `GET /api/v1/official/imports/{id}`
- `GET /api/v1/official/imports/{id}/topology`
- `GET /api/v1/official/imports/{id}/map`
- `POST /api/v1/official/imports/{id}/jobs/topology`
- `POST /api/v1/official/imports/{id}/runs`
- `GET /api/v1/official/jobs/{id}`
- `DELETE /api/v1/official/jobs/{id}`
- `GET /api/v1/official/runs/latest`

Run creation accepts an optional JSON body with `minimum_depth_m` and `maximum_depth_m`. Missing
values become 0.7 and 10.0 m. The validated pair is stored in `official_runs.parameters` and
returned with every run, so a queued calculation is reproducible across worker restarts. The
application rejects a search maximum above 50.0 m to keep the 0.5 m candidate grid bounded.
- `GET /api/v1/official/runs/{id}`
- `GET /api/v1/official/runs/{id}/export`
- `GET /api/v1/official/contracts/input.schema.json`
- `GET /api/v1/official/contracts/provided-dataset.schema.json`
- `GET /api/v1/official/contracts/output.schema.json`
- `/api/v1/swagger-ui.html` and `/api/v1/openapi`

## Required output

One GeoJSON FeatureCollection per result containing only these seven types:

1. `heat_network`;
2. `tie_in`;
3. `heat_network_reconstruction`;
4. `heat_chamber`;
5. `heat_chamber_reconstruction`;
6. `technical_node`;
7. `variant_summary` — exactly one non-spatial summary per variant.

No unrelated fields with `null` are permitted. The 2D adapter emits the exact per-type field
whitelists from the organizer appendix. `OfficialOutputContractValidator` independently checks
allowed/required fields, scalar types, WGS84 geometry, globally unique IDs, network-node references
and exactly one summary per variant. Contract tests also prove component-sum equality and ID
scoping across multiple alternatives. Optional R8 output now adds numeric `depth_start` and
`depth_end` to new heat-network sections and writes a third coordinate: the negative elevation of
the calculated pair-envelope axis. Two-dimensional consumers remain compatible with these valid
GeoJSON positions.

Only valid variants with complete economics and an integer rank are exportable. If the supplied
compatibility-profile file cannot establish reconstruction baselines, the endpoint returns
`409 OFFICIAL_EXPORT_INCOMPLETE`; it never publishes a plausible-looking partial official result.
The optional `variant_id` query limits the same validated contract to one alternative for the map;
omitting it downloads every ranked alternative in one FeatureCollection.

## Coordinate and size rules

- API geometry: EPSG:4326; metric calculations: EPSG:32637.
- Length, clearance, split and angle operations are performed in the projected CRS.
- Upload limit: 3 GB; output limit: 500 MB.
- Input inspection is streaming. Export performs a feature-by-feature preflight contract pass and
  then writes the FeatureCollection incrementally with Jackson `JsonGenerator`; the complete output
  tree is not retained. Clean Ubuntu 22 / Java 11 probes reached exactly 3 GiB input and at least
  500 MiB valid output under a 512 MiB heap cap. A full doubled supplied-geometry calculation also
  passes on clean Ubuntu 22 / Java 11 with 34/34 demands connected.
- Every result records input SHA-256, contract/catalog/algorithm versions and assumptions.
