# M4 web workspace evidence

Date: 2026-09-08

## Result

M4 is complete locally. The web application is a real project workspace backed by the
versioned API; route search, validation and costing are not implemented in the browser.
The workspace uses React Router deep links, TanStack Query for server state, Zustand for
local UI state, MapLibre GL JS for the map and an `openapi-typescript`/`openapi-fetch`
client generated from the checked-in OpenAPI contract.

The implemented user surface covers projects, optimistic project settings, scenario
creation and immutable revisions, dataset import/mapping/validation/publication,
data/quality views, the route editor and layers, alternatives, findings, calculation
passport, history, jobs, rule profiles and cost catalogs. Runs expose live SSE progress,
polling fallback, reconnect, cancellation, stale-revision indication and stable deep links.

## Acceptance walkthroughs

| Check | Result | Local evidence |
|---|---|---|
| E2E-01 demo | PASS | Project `a27b0cf5-08f7-4c1e-88c9-10bf8fd00ee4`; run `003529c4-7286-41ab-a5c5-4d5ac74ab1a6` returned three backend alternatives around the obstacle. Findings, cost, GeoJSON/HTML/JSON/CSV exports and the full passport are reachable from the inspector. Reload restored the selected run. |
| E2E-02 import | PASS | Browser workflow imported and published `examples/raw_buildings.demo.geojson` as import `7277e88c-bc69-4db5-a938-2c4df7f97d6b`, then selected its immutable version for a run. A generated two-layer GeoPackage was mapped, validated and published as import `eb3472b3-da0d-404a-886a-8b45d190b1d5`; the report retained selected and unselected layer provenance. |
| E2E-03 change | PASS | The map tool added a third forbidden polygon to the current route, save created scenario revision 5, and run `afc347b7-3beb-4111-9275-fc45cb11dd94` recomputed the route from 2,264.9 m to 2,380.9 m. Older successful and cancelled runs remain selectable in history. |
| E2E-04 quality | PASS | Quality and inspector views explicitly report unknown coverage, missing hydraulic inputs and missing depth/elevation as insufficient data. The live cost catalog version is missing a bend rate, so all three alternatives correctly show `Частичный результат` instead of claiming completeness. |
| E2E-06 themes/screen | PASS | Verified at 1440×900, 1280×800 and 640×800; light/dark themes persist, desktop panels collapse, narrow layouts expose mutually exclusive scenario/inspector drawers, and keyboard Tab focus lands on an interactive control with a visible focus treatment. |

Cancellation was also exercised through the UI: run
`94298591-a980-4a7a-880a-e7e2c93e103d` reached terminal `cancelled` and appeared in
history immediately. During the final browser session there were zero console errors and
no HTTP 4xx/5xx requests.

## Verification

- `ruff check apps/api/src tests` — passed.
- `mypy apps/api/src` — passed for 49 source files.
- Unit suite — 113 passed.
- Live integration suite — 18 passed.
- Web `typecheck`, `lint`, generated-client drift check and production build — passed.
- Web Vitest suite — 3 files / 6 tests passed.
- Compose services `api`, `db`, `redis`, `worker`, `scheduler` and `web` are running;
  API readiness returned `ready` with PostGIS and Redis checks `ok`.
- The only build note is Vite's non-blocking size warning for the lazy-loaded MapLibre
  chunk; the map is excluded from non-map route entry chunks.

## Visual evidence

- Final light desktop workspace:
  `output/playwright/.playwright-cli/page-2026-09-08T13-07-25-858Z.png`.
- Final dark desktop workspace:
  `output/playwright/.playwright-cli/page-2026-09-08T13-07-00-474Z.png`.
- Narrow scenario drawer:
  `output/playwright/.playwright-cli/page-2026-09-08T13-03-26-677Z.png`.
- Narrow inspector drawer:
  `output/playwright/.playwright-cli/page-2026-09-08T13-03-45-394Z.png`.

The screenshots are test artifacts and intentionally remain outside the production web
bundle.
