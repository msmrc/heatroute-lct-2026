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
- strict official-output layers for complete/ranked variants, fetched per `variant_id`; incomplete
  organizer data stays on an explicitly non-exportable calculation preview;
- conditional download of the validated seven-type GeoJSON;
- a separate system-information page for product version, team, stack and API documentation.

Runtime/framework labels, readiness badges, Swagger links and theme controls do not belong on the
planning workspace. Technical details stay on `/system`; the route screen uses domain language.

Legacy project/scenario/run screens were removed during cutover because their endpoints did not
exist in the official Java API. Do not restore a screen before its Java contract and tests exist.

## Current coverage and deferred surface

- All calculated variants are compared in the floating selector and result island; partial variants
  keep a structured no-route explanation instead of hiding failed objects.
- Selecting calculated or reconstructed geometry exposes flow, DU, length, chamber/tie-in and cost
  attributes. Validation, input compatibility, depth and reconstruction findings are grouped in a
  dedicated dialog.
- The strict official-output download is available only when the complete result passes the Java
  adapter and validator. Appendix arithmetic remains executable backend evidence rather than a
  second client-side calculation.
- A run-history screen is intentionally deferred until the Java API publishes a bounded list
  contract. MVT/paged result delivery remains outside P0 unless organizer-approved maximum-profile
  evidence shows that the current bounded context endpoint and lazy result view are insufficient.

The UI never calculates authoritative routes, costs or engineering validity. It visualizes server
results and preserves explicit unknown/partial/error states.

## Design and accessibility

React/TypeScript/Vite, TanStack Query, MapLibre GL, Proj4js, Lucide and local UI primitives. The
CARTO Positron vector basemap uses the same renderer and cartographic treatment as the team's
GdeBenzin project, keeps required attribution visible and is configurable through
`VITE_BASEMAP_STYLE_URL`. Use the tokens in
`docs/ui/design-system.md`. Base body text is 14 px; secondary text is at least 12 px. Controls
have visible keyboard focus and accessible names. Motion respects `prefers-reduced-motion`.
Route variants use the WAI-ARIA tab keyboard model: one tab stop, arrow navigation with wraparound,
and Home/End jumps. Validation details are a true modal dialog: focus enters the dialog, stays
inside while it is open, returns to the triggering metric on close, Escape closes it and background
page scrolling is suspended. Map/schematic/profile controls expose their pressed state to assistive
technology.
Desktop is primary. At 1180 px and below the result island switches to a 2 × 2 metric grid and the
inspector becomes scroll-contained. At 900 px and below the profile becomes a focused view without
the redundant result island; below 680 px the inspector is hidden to preserve the map task. On
phone widths the variant selector becomes a horizontally scrollable tab strip instead of covering
the map. Upload, status, errors and job actions remain usable at 320 px.
