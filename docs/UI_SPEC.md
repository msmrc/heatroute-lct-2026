# HeatRoute web interface

## Current P0 surface

The web app is a truthful client of the calculation API. The current home screen provides:

- official GeoJSON upload;
- import counts and localized contract errors;
- durable topology-job start, progress, cancellation and result;
- completed-run demo in a map-first workspace with an OpenLayers/OpenStreetMap view of calculated
  routes and source layers, floating layer controls, a route-variant switcher, an object inspector
  and a compact calculation-results drawer;
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

React/TypeScript/Vite, TanStack Query, OpenLayers, Proj4js, Lucide and local UI primitives. The
standard OpenStreetMap raster layer is a best-effort demo basemap with visible attribution; its
URL is configurable through `VITE_OSM_TILE_URL`. Use the tokens in
`docs/ui/design-system.md`. Base body text is 14 px; secondary text is at least 12 px. Controls
have visible keyboard focus and accessible names. Motion respects `prefers-reduced-motion`.
Desktop is primary. At narrower widths the inspector and result drawer move below the map, while
upload, status, errors and job actions remain usable at 320 px.
