# Routing reference corpus

This directory stores curated route-quality examples separately from organizer data.

## Current case

`professional-routing-01.geojson` contains only the manually drawn overlay from the supplied
`1.geojson` file:

- 26 route centerlines;
- 11 explicit junction markers;
- no embedded copy of the old input dataset;
- no map-editor colors or empty properties;
- source filename, hash and original feature indexes retained for provenance.

`professional-routing-01.reference.json` binds that geometry to the current official input and
records its reproducible expectations. The source used rounded coordinates, so matching allows a
1.5 m tolerance. The fixture is intentionally not valid official input or output and cannot be
uploaded to the HeatRoute API by mistake.

## Validate the corpus

```bash
pnpm benchmark:reference
```

The check reconstructs the logical graph in EPSG:32637, including splits where a chamber marker
falls inside a drawn line. It verifies one connected acyclic tree, all 17 demands as leaves, the
root at existing chamber 106, no junction degree above four and the expected downstream flows.

To get advisory geometry and topology metrics for an exported official result:

```bash
pnpm benchmark:reference -- output/result.geojson
```

Proximity to the expert trace is reported for diagnosis only. A shorter or cheaper legal route is
allowed to differ substantially from it.

## Add another mixed map-editor file

Keep the original outside Git, record who supplied it and whether it may be stored, then extract
only its untyped drawing layer:

```bash
node scripts/curate-routing-reference.mjs \
  /path/to/source.geojson \
  datasets/reference/<reference-id>.geojson \
  <reference-id>
```

Add a reviewed `<reference-id>.reference.json` manifest. Expectations must describe observed,
defensible properties of the example rather than force the production algorithm to reproduce one
set of coordinates. Every new fixture must pass a focused Node test before it joins the corpus.
