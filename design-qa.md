# Design QA — map-first route workspace

- Date: 2026-09-16
- Reference: `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-f80ae746-e118-4687-b9d4-a638eb80f121.png`
- Implementation: `http://127.0.0.1:5174/`
- Browser: Codex in-app browser
- Verified viewport: 1098 × 900
- Dataset state: official GeoJSON, 144 objects, completed two-variant calculation

## Comparison

The implementation preserves the selected reference's primary hierarchy: persistent navigation,
a compact dataset toolbar, a dominant map, floating route/layer controls, a right-hand contextual
inspector, and a bottom calculation summary. It intentionally removes the reference's runtime
status, dark-theme control and work-screen Swagger action. Product version, stack, team and API
documentation are available on `/system`.

At the verified narrower desktop width, the central success badge is hidden to preserve room for
the dataset identity and upload action. The status remains represented by the completed result
workspace and green validation state in the result drawer.

## Interaction checks

- Shared and independent route tabs update the geometry, totals and inspector.
- Independent mode exposes the unconnected-object reason in Russian instead of an internal code.
- Map/schematic switching works and retains the selected route variant.
- Layer controls remain operable over the OpenStreetMap surface.
- System information is reachable from the primary navigation and contains no workflow actions.
- No obsolete theme, runtime-ready, framework-version or workspace labels remain in the route UI.

## Automated checks

- TypeScript: passed
- ESLint: passed
- Vitest: 7 passed
- Production Vite build: passed
- Known non-blocking build note: the existing main JavaScript chunk exceeds Vite's 500 kB advisory
  threshold; this redesign does not introduce a runtime failure.

final result: passed
