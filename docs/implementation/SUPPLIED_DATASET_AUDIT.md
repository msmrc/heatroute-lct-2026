# Supplied dataset audit

**Dataset received:** 2026-09-16  
**Source file:** `!!!_Датасет.geojson`  
**Tracked canonical copy:** `datasets/official/lct-2026.geojson`
**SHA-256:** `07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0`

The tracked copy is byte-identical to the organizer file and is the only geodata file retained in
the repository. Synthetic compatibility-era and demo datasets were removed.

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

## Implemented official contest profile

Input contract v2 keeps the complete strict profile and adds
`official_contest_dataset`:

- integral IDs are accepted and normalized internally without a warning;
- a connection point with `flow_tph` and no `oks_id` is the confirmed demand object;
- missing existing-network flow/upstream fields and chamber diameter become explicit warnings;
- `oks` is accepted as the confirmed existing-building restriction type;
- `railway` is the dataset alias for `tram_tracks` and uses the published tram crossing rule;
- `oks` is treated as a forbidden building area, with the final clearance still determined by DU.

The profile does not fabricate source direction, existing flows or reconstruction results. R3 now
verifies geometric connectivity from the source when every upstream link is absent, including
disconnected sections and chambers off the network. R4 may use that connected geometry for
routing; R5 reconstruction remains blocked for this profile until the missing attributes are
supplied or a documented inference policy is approved.

## PM clarification required

The supplied dataset and direct demand on `oks_connection_point` are confirmed. Reconstruction is
not mandatory for this profile. Remaining written questions concern the reduced output contract,
continuous-length branching and disputed depth rules.

## Production verification

Commit `3d41730` was deployed to the VPS and the untouched supplied file was uploaded through the
public HTTPS API:

- import state: `valid`;
- contract/profile: `lct-2026-official-input-v2` / `official_contest_dataset`;
- features: 144; blocking errors: 0; actionable warnings: 76;
- topology job: `completed`, attempt 1;
- topology result: one source, 29 network sections, 9 chambers, zero issues and 204 deterministic
  tie-in candidates for 17 demand points.

This proves ingestion and R3 screening of the supplied file. It does not prove R4 route
construction, R5 reconstruction or final official export.
