# NextGen v6 universality check on the production VPS

Date: 2026-09-29 (Europe/Moscow)

Production algorithm: `nextgen-network-6`

Code commit: `8d9d2fc`

Production health after deployment: `UP`

## What was verified

Eight supplied GeoJSON files were imported or re-used on the production VPS and processed sequentially through the production API without competing calculation jobs. The accepted control case was run twice with distinct run and job identifiers. The repeated result is therefore a second calculation, not an HTTP response-cache hit.

Universality is interpreted strictly: the service accepts inputs of different size and layer composition, publishes a network only when every mandatory demand is connected and the engineering validator accepts the result, and otherwise returns a typed diagnostic. Version 6 adds a structural preflight which detects a mandatory terminal that is inside a forbidden clearance and has no legal normal egress before expensive path and master-problem searches begin.

## Production results

| Supplied dataset | Input size | Demand | VPS duration | Outcome | Diagnostic |
|---|---:|---:|---:|---|---|
| `Датасет скорректированный.geojson` | 144 features | 17 | 19.017 s cold; 15.918 s repeated | completed, 17/17 | balanced variant valid; 0 validation issues; 2,170.113 m |
| `official-plus-likhacheva-network.geojson` | 160 features | 17 | 107.735 s | rejected | `proven_master_infeasible`; exact master found no compatible complete combination in the generated catalogue |
| `heatroute-competition-roads-kindergarten.geojson` | 239 features | 17 | 0.296 s | rejected at preflight | `structurally_unroutable_demands:11` |
| `neighbor-kozhukhovo.geojson` | 411 features | 188 | 0.307 s | rejected at preflight | API returned the complete deterministic list of structurally isolated demands |
| `neighbor-kozhukhovo-with-roads-social.geojson` | 505 features | 188 | 2.536 s | rejected at preflight | API returned the complete deterministic list of structurally isolated demands |
| `neighbor-zil-north-with-roads-social.geojson` | 113 features | 17 | 0.236 s | rejected at preflight | 16 structurally isolated demands listed |
| `official-plus-likhacheva-network-with-roads-social.geojson` | 250 features | 17 | 0.675 s | rejected at preflight | `structurally_unroutable_demands:11` |
| `1.geojson` | 181 features | n/a | rejected before calculation | invalid import | 111 errors and 76 warnings |

The accepted control runs used different identifiers:

- cold run `9609f9ed-a455-42f8-8cbb-8adf8d0ea992`, job `d9da9a22-6f93-4495-93f8-f8613e3f69df`;
- repeated run `32df0407-22a4-42b2-8426-2bb88be8d46e`, job `08d100ab-031a-4919-8481-ef44a3aa414e`.

The repeated run started at `2026-09-29T17:34:37.131866Z` and completed at `2026-09-29T17:34:53.050040Z`. The result contains all 17 mandatory connections, is marked valid, has zero validation issues, and has total network length 2,170.113 m.

## Performance change from v5

| Dataset | v5 | v6 | Effect |
|---|---:|---:|---:|
| Control, repeated | 16.105 s | 15.918 s | accepted result preserved |
| Likhacheva network | 127.666 s | 107.735 s | 15.6% less time; exact rejection preserved |
| Roads + kindergarten | 113.956 s | 0.296 s | about 385x faster diagnosis |
| Kozhukhovo | 130.054 s | 0.307 s | about 424x faster diagnosis |
| Kozhukhovo + roads | 110.311 s | 2.536 s | about 43.5x faster diagnosis |
| ZIL North + roads | 11.881 s | 0.236 s | about 50x faster diagnosis |
| Likhacheva + roads | 118.073 s | 0.675 s | about 175x faster diagnosis |

The large speedups are not produced by weakening validation or accepting partial networks. They come from proving a structural terminal violation before running searches that cannot repair it.

## Regression verification

The complete Maven test suite passed on the final v6 code before production deployment:

- tests run: 2,485;
- failures: 0;
- errors: 0;
- skipped: 3;
- total time: 19 min 32 s.

Focused regression tests cover the new structural fail-fast path, deterministic demand reporting, root-aware clustering, root capacity accounting across accepted cluster groups, and the accepted 17/17 control result.

## Honest boundary and next work

The production evidence proves a 15.918-second repeated end-to-end calculation for the accepted 17-demand control dataset and sub-three-second classification for five structurally incompatible datasets. It does not prove that every arbitrary municipal GeoJSON is solvable. The extended Likhacheva case still requires 107.735 seconds to prove that the exact master problem has no complete compatible selection in the current catalogue; broader candidate generation remains the main universality frontier.

The supplied DWG file is outside the current production import contract, which accepts GeoJSON. Native CAD intake should be implemented as a deterministic, versioned DWG-to-normalized-GeoJSON conversion and validation stage instead of embedding an opaque CAD parser in the routing core.

Recommended continuation order:

1. produce a user-facing preflight report with geometry and layer references for every isolated demand;
2. broaden candidate generation around mixed existing-network, road, and social-object constraints;
3. retain clustered generation for large districts, with exact final connectivity and capacity validation;
4. add a versioned DWG conversion pipeline while preserving GeoJSON as the normalized internal contract;
5. promote every supplied dataset into a reproducible regression corpus with an explicit expected outcome and performance budget.

## Evidence artifacts

- `output/playwright/universality/vps-nextgen6-corrected-17-of-17.png` — accepted route map and production metrics;
- `output/playwright/universality/vps-nextgen6-universality-matrix.png` — all supplied GeoJSON outcomes and timings;
- `output/playwright/universality/vps-nextgen6-roads-kindergarten-fast-rejection.png` — readable structural diagnostic;
- `output/playwright/universality/v6-data/` — downloaded production API responses and exported accepted GeoJSON (ignored build evidence, not source-controlled).
