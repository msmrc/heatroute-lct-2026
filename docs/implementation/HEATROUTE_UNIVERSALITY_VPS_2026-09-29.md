# HeatRoute v6 universality check on the production VPS

Date: 2026-09-29 (Europe/Moscow)

Production build recorded during the run: 6

Production release commit: `789e3f7`

Current production algorithm: `heatroute-network-6`. The seven non-control dataset outcomes below
were measured on commit `8d9d2fc` under the initial internal build label; the control case
was rerun after the public rename. The rename/refactor did not change the v6 planning core, but
historical run identifiers and timings remain explicitly attributed to the build that produced them.

Production health after deployment: `UP`

## What was verified

Eight supplied GeoJSON files were imported or re-used on the production VPS and processed sequentially without competing calculation jobs. After promotion to `heatroute-network-6`, the accepted control case was run twice again with distinct run and job identifiers. The repeated result is therefore a second calculation, not an HTTP response-cache hit.

Universality is interpreted strictly: the service accepts inputs of different size and layer composition, publishes a network only when every mandatory demand is connected and the engineering validator accepts the result, and otherwise returns a typed diagnostic. Version 6 adds a structural preflight which detects a mandatory terminal that is inside a forbidden clearance and has no legal normal egress before expensive path and master-problem searches begin.

## Production results

| Supplied dataset | Input size | Demand | VPS duration | Outcome | Diagnostic |
|---|---:|---:|---:|---|---|
| `Датасет скорректированный.geojson` | 144 features | 17 | 19.137 s cold; 15.381 s repeated | completed, 17/17 | balanced variant valid; 0 validation, engineering or sizing issues; 2,170.113 m |
| `official-plus-likhacheva-network.geojson` | 160 features | 17 | 107.735 s | rejected | `proven_master_infeasible`; exact master found no compatible complete combination in the generated catalogue |
| `heatroute-competition-roads-kindergarten.geojson` | 239 features | 17 | 0.296 s | rejected at preflight | `structurally_unroutable_demands:11` |
| `neighbor-kozhukhovo.geojson` | 411 features | 188 | 0.307 s | rejected at preflight | API returned the complete deterministic list of structurally isolated demands |
| `neighbor-kozhukhovo-with-roads-social.geojson` | 505 features | 188 | 2.536 s | rejected at preflight | API returned the complete deterministic list of structurally isolated demands |
| `neighbor-zil-north-with-roads-social.geojson` | 113 features | 17 | 0.236 s | rejected at preflight | 16 structurally isolated demands listed |
| `official-plus-likhacheva-network-with-roads-social.geojson` | 250 features | 17 | 0.675 s | rejected at preflight | `structurally_unroutable_demands:11` |
| `1.geojson` | 181 features | n/a | rejected before calculation | invalid import | 111 errors and 76 warnings |

The accepted control runs used different identifiers:

- cold run `6615e7de-492d-41da-94a5-df3796de1b9e`, job `2843b0fd-9913-4b19-b3e4-1f97aa107d42`;
- repeated run `ef342726-448f-4bbb-bd9b-3ade16589e1f`, job `07092b39-479a-4451-b84f-89e209b01e1b`.

The repeated run started at `2026-09-29T18:16:25.742523Z` and completed at `2026-09-29T18:16:41.123330Z`. The result contains all 17 mandatory connections, is marked valid, has zero validation, engineering and sizing issues, and has total network length 2,170.113 m.
Canonical sorted result JSON is byte-identical between the cold and repeated promoted runs:
SHA-256 `1793e0857d306f0b1592e85d760c0a7f9a73d4c91100144c3b95fc66526470d43`.

## Performance change from v5

| Dataset | v5 | v6 | Effect |
|---|---:|---:|---:|
| Control, repeated | 16.105 s | 15.381 s | accepted result preserved |
| Likhacheva network | 127.666 s | 107.735 s | 15.6% less time; exact rejection preserved |
| Roads + kindergarten | 113.956 s | 0.296 s | about 385x faster diagnosis |
| Kozhukhovo | 130.054 s | 0.307 s | about 424x faster diagnosis |
| Kozhukhovo + roads | 110.311 s | 2.536 s | about 43.5x faster diagnosis |
| ZIL North + roads | 11.881 s | 0.236 s | about 50x faster diagnosis |
| Likhacheva + roads | 118.073 s | 0.675 s | about 175x faster diagnosis |

The large speedups are not produced by weakening validation or accepting partial networks. They come from proving a structural terminal violation before running searches that cannot repair it.

## Regression verification

The complete Maven test suite passed on the final v6 code before production deployment:

- tests run: 2,488;
- failures: 0;
- errors: 0;
- skipped: 3;
- total time: 19 min 32 s.

Focused regression tests cover the new structural fail-fast path, deterministic demand reporting, root-aware clustering, root capacity accounting across accepted cluster groups, and the accepted 17/17 control result.

## Honest boundary and next work

The production evidence proves a 15.381-second repeated end-to-end calculation for the accepted 17-demand control dataset and sub-three-second classification for five structurally incompatible datasets. It does not prove that every arbitrary municipal GeoJSON is solvable. The extended Likhacheva case still requires 107.735 seconds to prove that the exact master problem has no complete compatible selection in the current catalogue; broader candidate generation remains the main universality frontier.

The supplied DWG file is outside the current production import contract, which accepts GeoJSON. Native CAD intake should be implemented as a deterministic, versioned DWG-to-normalized-GeoJSON conversion and validation stage instead of embedding an opaque CAD parser in the routing core.

Recommended continuation order:

1. produce a user-facing preflight report with geometry and layer references for every isolated demand;
2. broaden candidate generation around mixed existing-network, road, and social-object constraints;
3. retain clustered generation for large districts, with exact final connectivity and capacity validation;
4. add a versioned DWG conversion pipeline while preserving GeoJSON as the normalized internal contract;
5. promote every supplied dataset into a reproducible regression corpus with an explicit expected outcome and performance budget.

## Evidence artifacts

- accepted-route screenshot with production metrics;
- universality matrix with all supplied GeoJSON outcomes and timings;
- readable structural-diagnostic screenshot for the roads-and-kindergarten case;
- `output/playwright/universality/v6-data/` — downloaded production API responses and exported accepted GeoJSON (ignored build evidence, not source-controlled).
