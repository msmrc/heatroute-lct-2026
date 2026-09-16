# Official requirements alignment audit

**Audit date:** 2026-09-16
**Sources:** organizer task PDF, organizer technical appendix DOCX, supplied GeoJSON, active Java
source tree, database migrations, Compose and CI configuration.

## Executive conclusion

The selected production technology matches the mandatory platform requirements and the active
internal specification describes the contest problem. For contract-complete input, the mandatory
2D calculation is implemented end to end: import, topology, multi-OKS routing, sizing,
reconstruction, restrictions, cost/rank, strict seven-type output and map rendering. Exact 3 GiB
input and 500 MiB output byte boundaries pass on clean Ubuntu 22 / Java 11, and the complete stack
is rehearsed there with docker-compose 1.29.2; 50 concurrent public API sessions also pass.
Representative maximum-topology acceptance evidence remains. The supplied organizer file itself cannot prove
reconstruction or official export because it omits the required existing-network baseline and
direction fields; the product exposes that incompleteness instead of fabricating values.

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
| Ubuntu Server 22 | Clean `ubuntu-22.04` CI builds and runs the complete stack; demo VPS remains 26.04 and unchanged | Meets in CI; production-like host rehearsal remains |
| docker-compose 1.29.2 | Checksum-pinned v1.29.2 validates, builds, starts and stops the integration stack | Meets in clean CI |
| Upload to 3 GB and output to 500 MB | Streaming parser and incremental validated writer pass exact 3 GiB / ≥500 MiB probes with `-Xmx512m` on Java 11 | Byte boundary meets; representative full-calculation topology scale remains |
| Up to 50 users | 50 concurrent imports pass on clean stack; Hikari, bounded 1–16 workers and lease heartbeat protect calculations | API-session gate meets; 50 heavy queued calculations need organizer interpretation |
| One combined GeoJSON input | Implemented, persisted in WGS84 and EPSG:32637 | Meets for the supplied 233 KB file |

JTS, Proj4J and PostGIS are appropriate supporting libraries for the required geometry. The
PostgreSQL lease-based job model executes both `topology_analysis` and immutable `calculation`
jobs; the calculation result now contains route geometry and new-network sizing.

## Internal specification alignment

The active `TECH_SPEC.md`, `DATA_CONTRACTS.md`, `ACCEPTANCE.md` and R0-R9 roadmap correctly set
the organizer PDF/DOCX above internal assumptions. They correctly require all-OKS processing,
shared trunks, tree invariants, sizing, reconstruction, exact costs, partial no-route behavior and
strict seven-type export.

Remaining documentation/acceptance drift:

- historical M-stage evidence describes a removed Python prototype and is not acceptance proof;
- the internal strict input model is faithful to the appendix but not to the supplied file;
- the output contract is enforced by an independent exact whitelist/type/reference validator and
  golden all-seven-type fixtures; a separately published JSON Schema remains a submission-kit task;
- optional hydraulics, MVT, Shapefile and GeoParquet work from the old prototype is outside the
  mandatory contest path and must not displace R4-R7.

## Functional readiness

| Area | Evidence in the active Java project | Readiness |
|---|---|---:|
| Platform, CI and VPS | Java-only Compose, PostGIS, Caddy, health checks, green CI and public HTTPS | 85% |
| Input and persistence | Streaming inspector/loader, contract profiles, contract+SHA replay/dedup, PostGIS dual CRS | 85% |
| Existing topology and tie-in screening | Geometric/upstream validation, segment/chamber candidates, 204 candidates on supplied data | 80% |
| Multi-OKS routing and tree construction | Immutable runs, three obstacle-aware strategies, normalization, partial no-route and independent validator | 85% |
| Flow, DU and continuous length | Tree sizer aggregates flow and automatically selects DU for all 18 flow/length boundaries | 90% |
| Reconstruction | Upstream propagation, partial/common sections and chamber reconstruction pass strict fixtures; supplied data lacks baseline fields | 80% |
| Restrictions and special passages | Dynamic buffers, base/special sections and the full published 2D boundary matrix are integrated | 90% |
| Cost, penalty and score | Exact component totals, length, score and rank are integrated; incomplete reconstruction withholds final score | 95% |
| Official output | Strict seven-type adapter, independent validator, incremental download and map consumer | 90% |
| UI | Map-first viewer renders official output when available and an explicit internal preview for incomplete input | 85% |
| Depth bonus | Geometry-based search, independent validation, separate XY detour, piecewise cost, technical nodes, exact XYZ and longitudinal UI are integrated | 95% |
| Scale and acceptance | Clean Ubuntu 22 / Compose 1.29.2 and restart gate, exact 3 GiB input, ≥500 MiB valid output and 50 concurrent API users pass | 80% |

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
preflight plus incremental Jackson writer. Complete variants are rendered from that same official
output contract. The normative appendix arithmetic is locked by a golden test; only the required
scale/environment evidence remains open.

## Critical path to a valid submission

### Appendix 10.8 arithmetic note

The appendix explicitly calls the coordinates and numeric values in its shortened GeoJSON example
illustrative. Applying the normative tables to its dimensions gives 27,942,288 RUB for 145.2 m of
DU 200 road crossing and 15,152,250 RUB for 75 m of DU 250 reconstruction, not the illustrative
27,942,307 and 15,152,283. The normative total is 51,094,538 RUB and score 2.091247064 (2.091 at
three decimals). A golden test locks these table/formula results and also records the -19/-33 RUB
differences, so the implementation cannot silently drift toward the inconsistent example numbers.

1. **R4/R9 performance:** adaptive JTS STRtree lookup is covered by a deterministic fixture with
   1,001 constraints and 20,000 bounded segment checks. Byte-size boundaries are measured, and a
   full 2× supplied-geometry run passes on Ubuntu 22 / Java 11 with 34/34 demands and 481,092 KiB
   peak RSS. Organizer approval of the actual maximum profile remains external; the published 2D
   R6 boundary matrix is complete.
2. **R7 complete variant:** closed against the normative appendix tables/formulas with the section
   10.8 illustrative-value discrepancy documented; serialization, validation, download and map
   consumption are implemented. Remaining scale proof belongs to R9.
3. **R9 acceptance:** preserve the passing Ubuntu 22 / docker-compose 1.29.2, byte-boundary,
   50-user API and full 2× topology gates, then obtain organizer approval of the maximum profile.

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
