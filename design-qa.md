# Design QA — GdeBenzin map engine and styling

- Date: 2026-09-16
- Source visual truth: `https://pinggi.ru/` and the user-owned implementation at
  `E:\job\tanos\benzstatus\web\src\islands\MapShell.svelte`
- Implementation: `http://192.168.1.158:5173/` (local HeatRoute preview)
- Browser-rendered evidence: Codex in-app browser captures of source and implementation
- Source pixels: 1280 × 720
- Implementation pixels: 1280 × 720
- CSS viewport: 1280 × 720; density normalization: identical browser viewport and capture density
- State: desktop, completed shared-network calculation, map mode, base/network/route enabled,
  restrictions disabled

## Findings

No actionable P0, P1 or P2 visual differences remain in the cartographic surface. HeatRoute now
uses the same MapLibre 5.24 renderer, CARTO Positron vector style and GdeBenzin palette logic as the
source instead of approximating it with a filtered raster tile layer.

## Full-view comparison evidence

The source and implementation were captured at the same 1280 × 720 viewport and inspected
together. Both show the same warm off-white ground, amber road hierarchy, pale blue water, muted
green land use, beige buildings, crisp vector labels and smooth WebGL rendering. HeatRoute keeps
its own necessary product chrome — route variants, engineering layers, inspector and results —
while preserving the source map's visual hierarchy.

## Focused region comparison evidence

The central map regions were compared at native capture size. Road casings/fills, building tone,
water tone, Russian labels and line sharpness follow the source treatment. HeatRoute route lines
and engineering nodes remain clearly separated from the amber road network. No separate asset
crop was necessary because all fidelity-critical content is vector-rendered by the same engine.

## Required fidelity surfaces

- Fonts and typography: application typography is unchanged; basemap symbols use the source
  vector style's glyph stack with `name:ru → name → name:latin` fallback. Labels are sharp and do
  not compete with the engineering result.
- Spacing and layout rhythm: the map fills the workspace without gutters or clipping. Layers,
  zoom, attribution and scale controls remain separated from variant tabs and the right inspector.
- Colors and tokens: background `#f6f5ef`, water `#cfe2ea`, parks `#d9eac6`, residential land
  `#efebe1` and the amber road hierarchy are ported from GdeBenzin's basemap styling.
- Image quality and asset fidelity: no raster approximation, placeholder or handcrafted map art is
  used. CARTO vector tiles are rendered directly by MapLibre GL; linework remains sharp during
  zoom and pan.
- Copy and content: Russian place/street labels are preferred. Product layer names remain domain
  specific: `Карта`, `Теплосеть`, `Ограничения`, `Маршруты`.
- Accessibility and interactions: the layer menu has an accessible name and checked state; zoom
  controls are labelled by MapLibre. A calculated route can be selected and exposes its length in
  the inspector.

## Comparison history

1. Initial pass retained OpenLayers and copied only the palette. User feedback correctly identified
   this as a P1 mismatch in both implementation and performance intent.
2. The map was replaced with MapLibre GL 5.24 and the same CARTO Positron source/styling approach
   used by GdeBenzin. OpenLayers, raster OSM and the compatibility bridge were removed.
3. Post-fix browser evidence shows the vector basemap, route overlays and object selection working
   together at 1280 × 720 with no console errors or warnings.

## Implementation checklist

- [x] Same MapLibre major/minor version as GdeBenzin
- [x] Same CARTO Positron vector source
- [x] Ported warm palette, road hierarchy and Russian labels
- [x] Preserved route, network, restriction and node layers
- [x] Layer visibility changes without map recreation
- [x] Route object selection updates the inspector
- [x] Attribution remains visible

## Follow-up polish

- No blocking or follow-up visual work remains. MapLibre is isolated in a lazy route-view chunk,
  so the upload screen does not download the cartographic engine before it is needed.

## Automated and interaction checks

- TypeScript: passed
- ESLint: passed
- Production build: passed; initial JavaScript reduced to 368.65 kB and MapLibre loads on demand
- Layer menu open/close: passed
- Basemap visibility toggle: passed
- Route/network persistence without basemap: passed
- Route-object selection and 478 m inspector value: passed
- Browser console errors/warnings: none

final result: passed
