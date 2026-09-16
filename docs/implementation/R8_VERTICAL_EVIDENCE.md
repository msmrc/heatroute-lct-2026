# R8 vertical routing evidence

Date: 2026-09-16

Runtime contract: Java 11 / Spring Boot 2.6.3

Status: functional R8 implementation complete for the published depth rules

## Implemented

- Official utility geometry is projected to stationing along every sized route edge.
- Existing gas, power and heat-network envelopes use the depths, dimensions and vertical
  clearances from the technical appendix.
- Candidate depth is searched at the official 0.5 m step with 3.0 m retained as the ordinary
  level and 0.7 m enforced as the lower bound.
- Every immutable run stores `minimum_depth_m` and `maximum_depth_m`; omitted values resolve to
  the published 0.7 m minimum and the documented 10.0 m application maximum before queueing.
- The cheapest feasible above/below decision creates a 4 m constant-depth plateau and straight
  ramps limited to 0.10 m/m.
- A second component independently validates endpoint coverage, station order, range, slope,
  candidate grid, plateau, passage direction and actual clearance.
- A failed vertical passage starts a separate XY detour around the conflicting utility and then
  repeats sizing, depth optimization and independent validation. If no valid detour exists, the
  result remains explicitly partial instead of inventing a passage.
- Depth-aware construction cost is integrated piece by piece between profile breakpoints, so a
  ramp cannot be priced using one misleading average for the whole edge.
- Strict output splits sections at profile breakpoints, creates referentially valid technical
  nodes and contains numeric `depth_start`, `depth_end` plus WGS84 LineString XYZ positions. Z is
  the negative elevation of the pair-envelope axis.
- The web workspace exposes a dedicated longitudinal-profile view with crossing zones, depth
  range, 3D length and validation state.

## Automated checks

- `OfficialDepthOptimizerTest`: six cases for cheaper passage, forced below passage, ordinary
  depth, insufficient ramp length, overlapping transitions and tampered slope.
- `OfficialDepthPlannerTest`: three cases for geometry-to-chainage projection, tie-in endpoint
  exclusion and verified profile generation.
- `OfficialObstacleRouterTest` and `OfficialRoutePlannerTest` prove the separate plan detour and
  the repeated vertical check.
- `OfficialGeoJsonExporterTest` asserts technical-node references, numeric depth properties and
  exact XYZ segmentation.
- `OfficialVariantEconomicsCalculatorTest` proves piecewise depth pricing across ramps.
- Full local backend suite: 99 tests, 0 failures, 3 intentional scale skips; Maven verify passes.
- Full web suite: 13 tests, 0 failures; TypeScript typecheck passes.

## Deliberate boundary

The depth interval is validated before the run is queued and read back by the durable worker, so a
restart cannot silently change the calculation parameters. The interval must include the ordinary
3.0 m level and cannot go above the surface-side published minimum of 0.7 m. `railway` remains
outside R8 until the organizer defines its official vertical rule.
