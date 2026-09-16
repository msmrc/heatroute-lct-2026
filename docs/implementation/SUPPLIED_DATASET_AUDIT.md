# Supplied dataset audit

**Dataset received:** 2026-09-16  
**Source file:** `!!!_Датасет.geojson`  
**Handling rule:** the organizer file stays outside Git; only a compact synthetic compatibility
fixture is committed.

## Observed shape

The file is a valid GeoJSON `FeatureCollection` in CRS84/EPSG:4326. It contains 144 features and
6,288 coordinate vertices inside the approximate WGS84 bounds 37.6262685–37.6581657 longitude
and 55.6909153–55.7055466 latitude.

| Input type | Count | Geometry | Observed attributes |
|---|---:|---|---|
| `oks_connection_point` | 17 | Point | numeric `id`, positive `flow_tph` |
| `restriction` | 88 | MultiPolygon | numeric `id`, `restriction_type`, optional `address` |
| `heat_chamber` | 9 | Point | numeric `id` |
| `heat_network` | 29 | LineString | numeric `id`, `diameter` (300/400/500) |
| `source` | 1 | Point | numeric `id`, `name` |

Restriction values are `oks` (85), `water` (2) and `railway` (1).

## Difference from the published technical appendix

The published contract declares string IDs, separate `oks_future` polygons, `oks_id` on every
connection point, `flow_tph` and `upstream_object_id` on existing network sections, and
`diameter`/`upstream_object_id` on chambers. None of those linking fields are present in this
dataset. Therefore the file cannot support the published upstream reconstruction calculation
without inferred topology and unavailable existing flows.

The pre-change strict importer correctly reported the mismatch but made the supplied file
unusable: 409 validation errors (`MISSING_FIELD`, `INVALID_NUMBER`, `INVALID_DIAMETER`, and
unsupported restriction/geometry diagnostics).

## Implemented compatibility profile

Input contract v2 keeps the complete strict profile and adds
`provided_dataset_compatibility`:

- integral IDs are normalized to decimal strings;
- a connection point with `flow_tph` and no `oks_id` is treated as the demand object itself;
- missing existing-network flow/upstream fields and chamber diameter become explicit warnings;
- `oks` and `railway` restriction aliases are accepted and reported;
- `railway` is conservatively treated as forbidden until an organizer rule is confirmed;
- `oks` is treated as a forbidden building area, with the final clearance still determined by DU.

The profile does not fabricate source direction, existing flows or reconstruction results. R3 now
verifies geometric connectivity from the source when every upstream link is absent, including
disconnected sections and chambers off the network. R4 may use that connected geometry for
routing; R5 reconstruction remains blocked for this profile until the missing attributes are
supplied or a documented inference policy is approved.

## PM clarification required

Ask the organizer which artifact is authoritative: the published attribute table or the supplied
dataset. Specifically request rules for `railway`, confirmation that `oks_connection_point`
contains the demand directly, and either the missing existing-network attributes or permission to
derive topology and omit reconstruction where existing flow is unknown.
