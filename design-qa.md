# Design QA — map-first workspace islands

- Date: 2026-09-16
- Source visual truth: the four user-supplied problem crops:
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-cb51ec42-e38b-474c-9c8c-661aa0535f35.png` — 955 × 124
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-b1b6a2f0-2e48-4f12-ae67-c9d5ad7a3ac5.png` — 896 × 208
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-906b99a7-956a-45ed-a43a-56c07d3a729c.png` — 314 × 728
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-02d16835-aeee-4202-8ef4-88a43c88d3e9.png` — 280 × 921
- Implementation: `http://192.168.1.158:5173/` local HeatRoute preview
- Implementation screenshot: Codex in-app browser capture at 1280 × 720
- Responsive evidence: Codex in-app browser capture at 900 × 800
- CSS viewport and density: browser viewport override was used for the 900 × 800 pass and reset after capture; source crops were judged at native density
- State: completed shared-network calculation, MapLibre map mode, expanded/collapsed navigation, selected-route inspector

## Findings

No actionable P0, P1 or P2 mismatch remains against the requested changes. The map is now the
continuous work surface below the dataset toolbar. The results summary and object inspector are
separate elevated islands over the map instead of grid tracks that consume map area. The toolbar
uses the real imported filename, and the left navigation can be collapsed and restored.

## Full-view comparison evidence

The 1280 × 720 implementation was compared with all four source crops in the same review pass.
The source showed a generic `Официальный GeoJSON` label, a full-width results row outside the map,
a full-height right column, and a permanently wide sidebar. The final implementation shows
`!!!_Датасет.geojson`, an uninterrupted cartographic canvas, a compact bottom results island, a
content-sized right inspector island and a narrow icon rail after collapse.

## Focused region comparison evidence

- Toolbar: the hard-coded dataset label is gone; the actual filename is ellipsized only when needed.
- Results: the four metrics retain their scan order and readable 21 px values inside one floating
  surface; the map remains visible beneath and around it.
- Inspector: default and selected-route states both use the same floating surface. Selecting a
  route exposes its measured length and closing the selection restores variant information.
- Navigation: the boundary control has accessible expand/collapse names; collapsed state keeps
  both destinations available as icons and increases map width.

No raster or generated visual assets were required: all target content is application chrome or
the existing vector map. Existing Lucide icons were retained to match the product's icon family.

## Required fidelity surfaces

- Fonts and typography: existing Inter/system typography and hierarchy are preserved. The real
  filename uses the existing 15 px dataset-title treatment; metrics and inspector values retain
  their established optical weights and line heights.
- Spacing and layout rhythm: islands use 16 px outer offsets, 16 px radii and a shared shadow;
  map controls, variant tabs, inspector and results no longer collide at 1280 × 720. At 900 × 800,
  metrics form a 2 × 2 grid and the inspector becomes internally scrollable without covering the
  results island.
- Colors and visual tokens: surfaces use the existing neutral white, border and shadow tokens with
  translucent backdrop blur. Success, route and basemap colors are unchanged.
- Image quality and asset fidelity: MapLibre/CARTO remains vector-rendered and sharp. No screenshot,
  placeholder, CSS art or custom SVG was substituted for UI or map content.
- Copy and content: the visible dataset name now comes from `original_filename`; domain labels such
  as `Результаты расчёта`, `Общая сеть`, `Длина` and `Камер и врезок` remain factual.
- Accessibility and interactions: collapse/expand is a labelled button with persisted state;
  keyboard focus styles remain visible; tabs and map selection retain their semantic controls.

## Comparison history

1. Source state: four large layout regions reduced the usable map and the toolbar used a generic
   file label.
2. First island pass: results and inspector floated correctly, but the centered variant selector
   could overlap the map/scheme controls at the desktop breakpoint (P2).
3. Fix: variant tabs are centered in the unobscured map region, with breakpoint-specific offsets.
   The 1280 × 720 capture shows clear separation; the 900 × 800 capture shows a usable 2 × 2 result
   island and scroll-contained inspector.

## Automated and interaction checks

- TypeScript: passed
- ESLint: passed
- Vitest: 5 files, 8 tests passed
- Production build: passed
- Real imported filename: passed
- Sidebar collapse, restore and persistence: passed
- Map resize after sidebar transition: passed
- Selected-route inspector: passed
- 900 × 800 responsive layout: passed
- Browser console errors/warnings: none

## Follow-up polish

No blocking visual work remains for this request.

final result: passed
