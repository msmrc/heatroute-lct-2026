# Design QA — upload flow and validation warnings

- Date: 2026-09-16
- Source visual truth: the five user-supplied problem crops:
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-df6a1a30-3dcf-4152-adfd-2c9d66c71729.png` — upload action affordance
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-c0bfae52-2c95-419f-af85-0251cd4770b4.png` — validation metric
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-9066197d-b075-426b-a148-2fc1f9510031.png` — overlapping sidebar/dataset icons
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-9220292d-8f81-43b4-b94c-eda917e4d387.png` — intermediate report screen
  - `C:\Users\dragon\AppData\Local\Temp\codex-clipboard-4006897d-3902-401e-bec4-0a4e8e57a35c.png` — full map workspace
- Implementation: `https://130-49-150-217.sslip.io/`, commit `407c0a7`
- Implementation screenshot: Codex in-app browser capture of the public map and open warnings dialog at 1118 × 909
- CSS viewport and density: desktop browser viewport at device scale 1; source crops retained at native density
- State: completed shared-network calculation, expanded and collapsed navigation, validation dialog open and closed

## Findings

No actionable P0, P1 or P2 mismatch remains against the requested changes. All interactive
buttons now expose a pointer cursor, the validation metric is a semantic dialog trigger, the
sidebar control no longer overlaps the dataset tile, and valid uploads transition through one
loader directly into the completed map workspace.

## Full-view comparison evidence

The public 1118 × 909 implementation was compared with all five source crops. The final expanded
state keeps the upload action, dataset title and sidebar control in separate hit areas. The
collapsed state replaces the brand row with the menu control instead of letting two floating
tiles collide. The map, inspector and result island retain the previously approved proportions.

## Focused region comparison evidence

- Validation metric: the full tile is now a keyboard-focusable button with hover/active feedback;
  the visible warning count remains part of the label.
- Dialog: clicking the metric opens a 520 px modal with the real 323 warnings, including code,
  message, feature index/id and field. The list scrolls independently and uses `content-visibility`
  for the long result set.
- Sidebar/header boundary: the toggle is contained inside the sidebar; expanded and collapsed
  states no longer overlap the dataset icon.
- Upload path: the valid-upload test proves `createOfficialRun(importId)` runs immediately. The
  report screen is skipped; the user sees a single progress surface until the run completes.

No new raster or generated assets were required. Existing Lucide icons and the established
HeatRoute component tokens were retained.

## Required fidelity surfaces

- Fonts and typography: existing Inter/system hierarchy, weights and 11–25 px UI scale remain
  unchanged. Modal metadata uses the same small-text optical weight as the workspace.
- Spacing and layout rhythm: the dialog uses the established 16 px radius and 20–22 px paddings;
  the sidebar toggle now has an internal 15/20 px inset instead of a negative offset.
- Colors and visual tokens: warning, success, accent, border and surface tokens are reused without
  introducing a parallel palette.
- Image quality and asset fidelity: the vector basemap and Lucide icons remain sharp; no bitmap,
  placeholder, handwritten SVG or CSS-drawn icon was introduced.
- Copy and content: warning content is returned by the Java API without invented summaries. The
  loader states say what the system is doing and that results open automatically.
- Accessibility and interactions: validation uses `aria-haspopup=dialog`; the dialog is labelled,
  closes from both buttons, backdrop and `Escape`; all enabled buttons/links/role-buttons show a
  pointer; the sidebar control keeps explicit expand/collapse names.

## Comparison history

1. Source state: warning count was static, generic buttons did not consistently expose pointer,
   sidebar and dataset icons visually collided, and upload stopped at a technical report (P1/P2).
2. First implementation: warning dialog and direct upload-to-run path were added; sidebar toggle
   was moved inside the navigation rail and the collapsed brand was suppressed.
3. Post-fix public capture: the dialog shows live warnings, `Escape` restores the map, and both
   sidebar states keep the header clear. No remaining P0/P1/P2 issue was observed.

## Automated and interaction checks

- TypeScript: passed
- ESLint: passed
- Vitest: 6 files, 9 tests passed
- Production build: passed
- GitHub Actions run `35089560867`: passed (web, backend, integration)
- Valid upload immediately starts calculation: passed
- Intermediate report skipped for valid input: passed
- Warning dialog open/content/close: passed
- Dialog `Escape` close on public site: passed
- Expanded/collapsed sidebar icon separation: passed
- VPS health, OpenAPI and HTTPS smoke: passed

## Follow-up polish

No blocking visual work remains for this request.

final result: passed
