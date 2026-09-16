# HeatRoute web interface

## Current P0 surface

The web app is a truthful client of the calculation API. The current home screen provides:

- official GeoJSON upload;
- import counts and localized contract errors;
- durable topology-job start, progress, cancellation and result;
- completed-run demo in a map-first workspace with a MapLibre/CARTO vector view of calculated
  routes and source layers, a route-variant switcher, floating object-inspector/results islands and
  a collapsible navigation rail;
- a deliberately muted basemap that keeps route geometry visually dominant; optional source
  layers live in one compact layer menu and restrictions are hidden by default;
- a switchable EPSG:32637 engineering schematic;
- a separate system-information page for product version, team, stack and API documentation.

Runtime/framework labels, readiness badges, Swagger links and theme controls do not belong on the
planning workspace. Technical details stay on `/system`; the route screen uses domain language.

Legacy project/scenario/run screens were removed during cutover because their endpoints did not
exist in the official Java API. Do not restore a screen before its Java contract and tests exist.

## Planned R4–R7 surface

1. Run list/detail for one immutable official import.
2. Extend the current route/source-layer map with candidates, reconstruction and the strict R7
   output layers.
3. Comparison of up to three materially different variants.
4. Structured no-route list and independent validation findings.
5. Flow, DU, length, chamber, cost and score drill-down.
6. Download of the strict official GeoJSON.

The UI never calculates authoritative routes, costs or engineering validity. It visualizes server
results and preserves explicit unknown/partial/error states.

## Design and accessibility

React/TypeScript/Vite, TanStack Query, MapLibre GL, Proj4js, Lucide and local UI primitives. The
CARTO Positron vector basemap uses the same renderer and cartographic treatment as the team's
GdeBenzin project, keeps required attribution visible and is configurable through
`VITE_BASEMAP_STYLE_URL`. Use the tokens in
`docs/ui/design-system.md`. Base body text is 14 px; secondary text is at least 12 px. Controls
have visible keyboard focus and accessible names. Motion respects `prefers-reduced-motion`.
Desktop is primary. At narrower widths the result island switches to a 2 × 2 metric grid and the
inspector becomes scroll-contained; below 680 px the inspector is hidden to preserve the map task.
Upload, status, errors and job actions remain usable at 320 px.
