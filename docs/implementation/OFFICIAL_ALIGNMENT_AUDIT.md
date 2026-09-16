# Official requirements alignment audit

**Audit date:** 2026-09-16
**Sources:** organizer task PDF, organizer technical appendix DOCX, supplied GeoJSON, active Java
source tree, database migrations, Compose and CI configuration.

## Executive conclusion

The selected production technology matches most mandatory platform requirements and the active
internal specification now describes the correct contest problem. The application is not yet a
contest solution end to end: it imports the supplied data and constructs independently validated
new-network variants, but it does not yet reconstruct, rank or export the official result.

Estimated readiness for the mandatory submission is **about 50% overall**. Platform, import,
topology and new-network routing are ahead of reconstruction/export. Readiness of the mandatory
calculation itself is **about 50%**: obstacle-aware R4/R6 routing and new-network sizing exist, but
existing-network reconstruction and the R7 calculation/export pipeline are still absent.

## Sole official dataset

The only tracked geodata file is `datasets/official/lct-2026.geojson`.

- original organizer filename: `!!!_Датасет.geojson`;
- SHA-256: `07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0`;
- size: 233,277 bytes;
- root: GeoJSON `FeatureCollection`, CRS84/WGS 84 longitude-latitude;
- 144 features and 6,288 coordinate vertices;
- 17 demand connection points, 88 restrictions, 29 network sections, 9 chambers and 1 source.

All synthetic GeoJSON files and the old demo pack were removed. Invalid-input unit cases are built
inline, while the real organizer file is loaded as the compatibility regression resource.
The production database was backed up and reduced to one valid import with the same official hash;
the old eight-feature fixture import and obsolete invalid attempt were removed.

## Official contract versus supplied data

| Requirement in the appendix | Supplied GeoJSON | Consequence |
|---|---|---|
| Seven input types | Five types; no `oks_future` or `oks_existing` | The published strict profile cannot describe the file literally |
| `id` is a string | All 144 IDs are integers | IDs are normalized to decimal strings with warnings |
| `oks_future` polygon has `flow_tph` and `heat_load` | Demand is stored directly on 17 `oks_connection_point` points; no heat load | Points are treated as demand objects; heat load cannot be recovered |
| Connection point has `oks_id` | `oks_id` is absent | No building-to-point reference can be validated |
| Existing network has `diameter`, `flow_tph`, `upstream_object_id` | 29 sections contain only diameter | Reconstruction load and authoritative direction to source are unavailable |
| Existing chamber has `diameter`, `upstream_object_id` | 9 chambers contain neither | Chamber reconstruction baseline and authoritative direction are unavailable |
| Existing buildings are `oks_existing` | 85 polygons are restrictions with `restriction_type=oks` | Treated as a compatibility alias; DU-dependent 5/7/9 m clearance must be applied during routing |
| Restriction catalog contains `tram_tracks`, not `railway` | One polygon has `restriction_type=railway` | Current conservative rule forbids crossing at 1.5 m; organizer clarification is required |

The file is accepted only through the named `provided_dataset_compatibility` profile. Its 323
warnings are deterministic: 144 numeric IDs, 17 direct-demand points, 86 restriction aliases,
29 missing existing-network flows, 38 missing upstream links and 9 missing chamber diameters. No
missing engineering values are fabricated.

## Technology alignment

| Official requirement | Current implementation | Status |
|---|---|---|
| Java 11 | Maven compiler release 11; CI runs Temurin 11 | Meets |
| Spring Boot 2.6.3 | Exact parent version 2.6.3 | Meets |
| springdoc-openapi-ui 1.7.0 | Exact dependency and generated OpenAPI snapshot | Meets |
| PostgreSQL up to 18 or OpenSearch up to 2.18 | PostgreSQL 17 with PostGIS 3.5 | Meets |
| Ubuntu Server 22 | Demo VPS uses Ubuntu 26.04 | Does not meet acceptance environment yet |
| docker-compose 1.29.2 | Compose files are exercised only by a modern Compose implementation | Unproven; syntax must be rehearsed with 1.29.2 |
| Upload to 3 GB and output to 500 MB | 3 GB multipart limits and streaming feature parsing exist; output is absent | Partial, no boundary evidence |
| Up to 50 users | Hikari pool and durable jobs exist | Unproven; no load test and worker shares API JVM |
| One combined GeoJSON input | Implemented, persisted in WGS84 and EPSG:32637 | Meets for the supplied 233 KB file |

JTS, Proj4J and PostGIS are appropriate supporting libraries for the required geometry. The
PostgreSQL lease-based job model executes both `topology_analysis` and immutable `calculation`
jobs; the calculation result now contains route geometry and new-network sizing.

## Internal specification alignment

The active `TECH_SPEC.md`, `DATA_CONTRACTS.md`, `ACCEPTANCE.md` and R0-R9 roadmap correctly set
the organizer PDF/DOCX above internal assumptions. They correctly require all-OKS processing,
shared trunks, tree invariants, sizing, reconstruction, exact costs, partial no-route behavior and
strict seven-type export.

Remaining documentation drift:

- historical M-stage evidence describes a removed Python prototype and is not acceptance proof;
- old progress text understated the implemented catalog/economics primitives;
- the internal strict input model is faithful to the appendix but not to the supplied file;
- the exact official output fields are described in the appendix but are not yet encoded as JSON
  Schema/golden export tests;
- optional hydraulics, MVT, Shapefile and GeoParquet work from the old prototype is outside the
  mandatory contest path and must not displace R4-R7.

## Functional readiness

| Area | Evidence in the active Java project | Readiness |
|---|---|---:|
| Platform, CI and VPS | Java-only Compose, PostGIS, Caddy, health checks, green CI and public HTTPS | 80% |
| Input and persistence | Streaming inspector/loader, contract profiles, PostGIS dual CRS, real dataset regression | 75% |
| Existing topology and tie-in screening | Geometric/upstream validation, segment/chamber candidates, 204 candidates on supplied data | 55% |
| Multi-OKS routing and tree construction | Immutable runs, three obstacle-aware strategies, normalization, partial no-route and independent validator | 70% |
| Flow, DU and continuous length | Tree sizer aggregates flow and automatically selects DU for all 18 flow/length boundaries | 90% |
| Reconstruction | Upstream propagation, partial/common sections and chamber reconstruction pass strict fixtures; supplied data lacks baseline fields | 80% |
| Restrictions and special passages | Dynamic buffers and base/special sections are integrated into search/final validation; full row matrix remains | 65% |
| Cost, penalty and score | Exact component totals, length, score and rank are integrated; incomplete reconstruction withholds final score | 85% |
| Official output | No seven-type result model, serializer, streaming download or schema validator | 5% |
| UI | Map-first viewer renders all route variants, source context, details and diagnostics | 70% |
| Depth bonus | Pipe dimensions/depth multiplier exist; no vertical search or Z output | 10% |
| Scale and acceptance | Small real file and CI/VPS smoke pass; 3 GB/500 MB/50-user/Ubuntu 22 gates absent | 20% |

## What is already proven on the supplied data

- import state `valid`, 144 features, zero blocking errors;
- compatibility warnings are explicit and reproducible;
- source, 29 network sections and 9 chambers are geometrically connected;
- durable topology job completes on attempt 1 with zero topology issues;
- 204 deterministic tie-in candidates are produced for 17 demand points;
- Java, web and live Compose CI gates pass.

The local Java regression additionally proves that the supplied file produces three valid route
strategies, that the preferred variant connects all 17 demand points and that every accepted edge
has real polyline sections, flow and DU. Strict inline fixtures prove partial/common-section and
chamber reconstruction. The supplied file itself cannot prove reconstruction because its existing
network omits flow and upstream direction. Exact component costing is integrated. A strict
seven-type adapter and validator pass on contract-complete inline fixtures, while the supplied file
correctly receives `OFFICIAL_EXPORT_INCOMPLETE`. Export generation now uses a feature-by-feature
preflight plus incremental Jackson writer. Official-output UI, appendix golden example and required
scale limits remain open.

## Critical path to a valid submission

1. **R4/R9 performance:** profile the integrated search on denser geometry and record reproducible
   time/memory evidence; the published 2D R6 boundary matrix is now complete.
2. **R7 complete variant:** drive the map from the validated seven-type model and reproduce the
   organizer appendix arithmetic example as a golden test.
3. **R9 acceptance:** test docker-compose 1.29.2 on Ubuntu 22, separate worker if required, then
   produce measured 3 GB input, 500 MB output and 50-user evidence.

The depth task is optional and should start only after the complete two-dimensional P0 pipeline
passes on both a strict appendix-shaped fixture constructed in code and the supplied organizer file.

## Organizer decisions still required

1. Confirm that the supplied GeoJSON, not the published seven-type table, is the judging input.
2. Confirm that `oks_connection_point.flow_tph` replaces `oks_future` plus `oks_id` for this case.
3. Define the `railway` rule: forbidden area or special passage, including clearance, angle,
   extension, depth and multiplier.
4. Provide `flow_tph`, `upstream_object_id` and chamber `diameter`, or explicitly waive existing
   network/chamber reconstruction for the supplied file.
5. Confirm whether evaluation requires literal Ubuntu Server 22 and docker-compose 1.29.2.
