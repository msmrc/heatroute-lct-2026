# Design QA — simplified planning map

- Date: 2026-09-16
- Source visual truth: `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-1ec18399-8409-40b5-b0c3-ec2eaf21eb9b.png`
- Source pixels: 897 × 686
- Implementation: `http://127.0.0.1:5174/`
- Browser-rendered evidence: Codex in-app browser capture, 1280 × 720 viewport
- Focused map region: 738 × 512 CSS pixels
- Density normalization: CSS pixel comparison at device scale 1; the source is a cropped map card,
  so composition was judged on the map surface and controls rather than outer application chrome.
- State: completed shared-network calculation, map mode, optional restrictions hidden

## Full-view comparison evidence

The source shows a visually dense OSM surface, four permanently exposed layer pills, a persistent
four-item legend and a technical extent caption. The revised implementation makes the basemap a
quiet background, keeps route geometry visually dominant, removes the persistent legend and extent
caption, and exposes optional layers through one familiar map control. The main product hierarchy —
variant tabs, map/schematic switch, inspector and result summary — is preserved.

## Focused region comparison evidence

The map region was inspected both with the layer menu closed and open. The closed state contains
only zoom, layers, route variants, map/schematic mode, scale and attribution. The open state uses a
compact four-row checklist. Restrictions are off by default and can be enabled without reloading
the basemap.

## Required fidelity surfaces

- Fonts and typography: existing product typography is preserved; map controls use the same
  13 px UI scale and readable weights as the surrounding interface.
- Spacing and layout rhythm: variant tabs are centered, zoom and layers are vertically separated,
  and the layer menu uses 38 px rows with consistent 9–14 px radii.
- Colors and tokens: the raster basemap is desaturated, lowered in contrast and brightened; route,
  network and node colors retain semantic distinction without competing with the inspector.
- Image quality and assets: OpenStreetMap raster tiles remain sharp; filtering is applied only to
  the basemap layer and does not soften vector routes or nodes. Attribution remains visible.
- Copy and content: `OSM` and the technical extent caption were removed from the default surface;
  layer names are now `Карта`, `Теплосеть`, `Ограничения`, `Маршруты`.

## Comparison history

1. First implementation pass found a P1 control overlap: the new layer button occupied the same
   top-left area as route variants and zoom. Fixed by centering route variants and placing the layer
   control below zoom. Post-fix evidence shows all controls separated.
2. Interaction pass found a P1 white flash because every layer toggle rebuilt the OpenLayers map.
   Fixed by retaining layer instances and changing their visibility in place. Post-fix evidence
   shows restrictions toggling instantly with the base map retained.

## Remaining polish

- P3: street labels are baked into the current OSM raster tiles. They are intentionally muted; a
  future self-hosted/vector basemap could remove labels selectively if the product later needs a
  fully custom cartographic style.

## Automated and interaction checks

- TypeScript: passed
- ESLint: passed
- Vitest: 7 passed
- Production Vite build: passed
- Layer menu open/close: passed
- Base, network, restriction and route toggles: passed
- Toggle without basemap reload/flash: passed
- Browser console errors: none

final result: passed
