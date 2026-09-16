# R8 vertical routing evidence

Date: 2026-09-16

Runtime contract: Java 11 / Spring Boot 2.6.3

Status: integrated vertical-profile checkpoint; XY feedback remains open

## Implemented

- Official utility geometry is projected to stationing along every sized route edge.
- Existing gas, power and heat-network envelopes use the depths, dimensions and vertical
  clearances from the technical appendix.
- Candidate depth is searched at the official 0.5 m step with 3.0 m retained as the ordinary
  level and 0.7 m enforced as the lower bound.
- The cheapest feasible above/below decision creates a 4 m constant-depth plateau and straight
  ramps limited to 0.10 m/m.
- A second component independently validates endpoint coverage, station order, range, slope,
  candidate grid, plateau, passage direction and actual clearance.
- Depth-aware construction cost is calculated per route section.
- Strict output contains `depth_start`, `depth_end` and WGS84 LineString positions with Z equal to
  the negative elevation of the pair-envelope axis.
- The web workspace exposes a dedicated longitudinal-profile view with crossing zones, depth
  range, 3D length and validation state.

## Automated checks

- `OfficialDepthOptimizerTest`: six cases for cheaper passage, forced below passage, ordinary
  depth, insufficient ramp length, overlapping transitions and tampered slope.
- `OfficialDepthPlannerTest`: three cases for geometry-to-chainage projection, tie-in endpoint
  exclusion and verified profile generation.
- `OfficialGeoJsonExporterTest` asserts numeric depth properties and XYZ positions.
- Full local backend suite: 87 tests, 0 failures, 2 intentional scale skips.
- Full web suite: 13 tests, 0 failures; TypeScript typecheck passes.

## Deliberate boundary

The official appendix describes the depth task as a separate rerouting operation whose plan view
may change. The current implementation computes and validates a vertical profile over every
accepted 2D route. If the configured depth interval or available ramp length is impossible, it
returns an explicit partial profile and manual-resolution issue. It does not yet feed that conflict
back into the XY obstacle search. Therefore R8 must not be called fully complete until a separate
depth-run feedback loop can choose an alternative plan route and re-run the same validator.
