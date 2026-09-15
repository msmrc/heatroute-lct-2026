# HeatRoute interface system

Updated: 2026-09-07

HeatRoute uses the Acctual information architecture with interaction principles adapted from
Apple's Human Interface Guidelines. This is a web product system, not an imitation of a native
Apple application.

## Foundations

- UI font stack: the operating-system font first, then SF Pro Text, Inter and Segoe UI.
- Base text: 14 px. Navigation and controls: 13 px. Supporting text: 11–12 px.
- Page title: 25 px, semibold. Avoid thin weights for meaningful information.
- Spacing follows a 4 px base unit. Main gaps use 8, 12, 16, 24 and 32 px.
- Controls are at least 40 px high in the primary desktop workflow.
- A view has one prominent action. Secondary actions use a quiet bordered style.
- State is never communicated by color alone: every dot or tint has a text label.

## Surfaces and hierarchy

- The persistent navigation rail is a subdued material layer.
- The main workspace is the primary white content surface.
- Borders separate data; shadows are reserved for elevated or transient elements.
- Rounded corners use 10 px for controls and 14 px for major surfaces.

## Motion

- Press feedback: 110 ms.
- Hover and state feedback: 180 ms.
- View continuity: 260 ms with a short ease-out curve and at most 6 px of travel.
- Motion must not delay input or be the only indication of a state change.
- `prefers-reduced-motion` removes translation, scaling, map movement and spinners; view changes
  become a short opacity fade.

## Accessibility and interaction

- Keyboard focus uses a visible three-pixel accent ring.
- Every icon-only or status control needs an accessible name.
- Disabled actions remain visible and explain their state through adjacent status text.
- Tables collapse noncritical columns on narrow screens while preserving the primary label,
  measurement and algorithm.
