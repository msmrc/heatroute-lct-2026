# HeatRoute web interface

## Current P0 surface

The web app is a truthful client of the Java API. The current home screen provides:

- Java/PostGIS readiness state;
- official GeoJSON upload;
- import counts and localized contract errors;
- durable topology-job start, progress, cancellation and result;
- Swagger access and light/dark themes.

Legacy project/scenario/run screens were removed during cutover because their endpoints did not
exist in the official Java API. Do not restore a screen before its Java contract and tests exist.

## Planned R4–R7 surface

1. Run list/detail for one immutable official import.
2. Map of all future OKS, candidates, new trees, reconstruction and restrictions.
3. Comparison of up to three materially different variants.
4. Structured no-route list and independent validation findings.
5. Flow, DU, length, chamber, cost and score drill-down.
6. Download of the strict official GeoJSON.

The UI never calculates authoritative routes, costs or engineering validity. It visualizes server
results and preserves explicit unknown/partial/error states.

## Design and accessibility

React/TypeScript/Vite, TanStack Query, Lucide and local UI primitives. Use the tokens in
`docs/ui/design-system.md`. Base body text is 14 px; secondary text is at least 12 px. Controls
have visible keyboard focus and accessible names. Motion respects `prefers-reduced-motion`.
Desktop is primary, but upload, status, errors and job actions remain usable at 320 px.
