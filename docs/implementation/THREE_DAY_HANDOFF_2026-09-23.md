# Handoff: routing work on 21-23 September 2026

This note is a short operational summary for the developer taking over the current routing work.
The organizer documents remain the source of truth. Detailed experiment evidence is retained in
`progress.md`, while the active implementation sequence is tracked in `OFFICIAL_TZ_ROADMAP.md`.

## Current repository state

- Branch: `master`.
- First consolidated routing commit: `3e4cbac` (`feat: improve routing geometry and variant planning`).
- Active algorithm version in code: `global-tree-46`.
- The Java 11 main and test sources compiled during the local Compose build; tests were skipped.
- No full tests, lint, typecheck or browser smoke were run before the push by explicit request.
- The latest fully completed corrected-dataset calculation is `global-tree-44`, run
  `0964d27f-13f0-48df-b141-9ff9ecd08584`.
- `global-tree-45` and `global-tree-46` calculations were cancelled deliberately. The former
  exposed repeated expensive group repair; the latter was stopped to avoid calculating a result
  that did not yet include the newly requested strict 90-degree building entry rule.

## 21 September: contract alignment and a stable routing baseline

The work began by aligning the implementation and tests with the amended organizer contract:

- `railway` is a forbidden object with the official clearance; `tram_tracks` keeps the special
  crossing handling;
- the invented arbitrary-bend tariff was removed;
- existing networks and existing chambers are not reconstructed or exported as new construction;
- official export is limited to `heat_network`, `heat_chamber`, `technical_node` and
  `variant_summary`;
- incomplete or invalid drafts are no longer published as successful complete alternatives;
- restored UI labels and backend/integration expectations were aligned with the contract.

The shared-tree search was then bounded and cached. This recovered the focused official dataset
from a greater-than-20-minute regression to about one minute at the early checkpoint. Subsequent
geometry work intentionally spent more time to improve topology and constructability.

Routing versions `global-tree-9` through `global-tree-13` added:

- short own-OKS exits with a bounded alternate side when the nearest exit is blocked;
- bottom-up diameter validation before a tree graft is accepted;
- constructability preferences for straight, 45-degree and 90-degree geometry;
- the official existing-chamber rule: reuse a chamber within the inclusive 10 m boundary only
  while no more than four linear sections meet there; farther away a new chamber may be created on
  the existing network;
- a bounded perpendicular approach preference at a tie-in chamber;
- rejection of OSM road polygons as the default input after they made the route longer, more
  expensive and slower.

The important lesson from the rejected single-junction experiments was that local point movement
does not fix an already poor surrounding tree. Complete branch groups have to be compared against
the completed control network.

## 22 September: genuinely different variants and topology experiments

The planner was changed from three labels over nearly identical geometry to a bounded portfolio of
different complete candidates:

- `engineering` optimizes constructability inside a bounded economic corridor;
- `shortest` selects by total routed length;
- `cheapest` selects by final official construction economics and may legally be longer.

The cheapest-only pass can reuse a bounded nearby existing chamber when the fully sized network is
strictly cheaper. This produced the intended visible difference: in `global-tree-29` the cheapest
route was 6.976 m longer than the shortest but about 4.35 million RUB cheaper and reused existing
chamber `106` instead of building a new tie-in chamber.

Topology versions `global-tree-20` through `global-tree-28` explored:

- merging two adjacent generated chambers;
- completing several early pair-based tree seeds;
- keeping valid chamber-merge alternatives for length and cost objectives;
- relocating a final new tie-in chamber after the complete tree is known.

Pair ordering alone did not generate the desired intersection-centred common trunk. The useful
result was the final-tree tie-in relocation, which shortened one root edge from 48.886 m to
18.090 m while retaining valid geometry.

The expert geometry contract was then separated from the official-TZ-only cheapest variant:

- engineering and shortest: bends from 90 to 135 degrees and at least 2 m between consecutive
  bends;
- cheapest: official TZ restrictions only, so it remains a real cost minimum rather than a copy of
  the engineering route;
- expert-rule findings are published as diagnostics and do not hide an otherwise official-valid
  alternative.

## 23 September: final-geometry evaluation and group routing

The UI now always exposes the available `engineering`, `shortest` and `cheapest` alternatives and
shows metrics for the selected variant. Missing sibling strategies no longer produce misleading
`0 m / 0 OKS` cards. Expert geometry warnings are visible in the inspector and validation dialog.

`EngineeringRouteEvaluator` was introduced to evaluate the geometry the user actually sees:

- internal bends inside one edge;
- turns where two route edges meet at a degree-two node;
- separate soft diagnostics for irregular multi-ray chamber geometry;
- 90-135-degree bend range and 2 m bend spacing.

The repair sequence was extended with alternative OKS exits, bounded left/right obstacle routing,
terminal junction relocation and finished-edge regularization. The last completed run,
`global-tree-44`, connects 17/17 OKS in all three official-valid alternatives:

| Variant | Length | Construction cost | Remaining expert-angle findings |
| --- | ---: | ---: | ---: |
| Engineering | 1,800.341 m | 271,313,093.18 RUB | 3 |
| Shortest | 1,794.571 m | 275,447,466.24 RUB | 3 |
| Cheapest | 1,762.700 m | 266,995,620.48 RUB | not applied |

This reduced the strict variants from five to three bad angles, but did not make all areas of the
map geometrically coherent.

The next experiment groups nearby OKS instead of repairing every building independently:

- a problem zone contains at most four nearby demands within 120 m;
- no more than two improving zones are accepted per engineering pass;
- the group is detached and evaluated as one topology decision;
- in `global-tree-46`, the first demand establishes a local branch and later demands may attach
  only to that group's growing spine, rather than independently selecting any edge in the global
  tree;
- group rebuilding is performed for the engineering variant only; shortest keeps the lighter
  individual normalization, and cheapest keeps official-TZ-only behavior.

The predecessor `global-tree-45` proved that grouping is promising but also exposed the wrong
execution shape. Intermediate group candidates reduced the raw draft from 17 to 15 and then to 14
angle violations, but the same expensive group pass was repeated for multiple variants and the run
was cancelled after about 27 minutes. `global-tree-46` removes that duplicate and adds the explicit
group spine. It compiles and is present in `master`, but it has not completed a corrected-dataset
calculation yet and must not be presented as a verified improvement.

## Open requirements and known problems

1. **Strict perpendicular building entry is not implemented yet.** The latest user requirement is
   that a heat-network terminal may enter a building only at 90 degrees to its wall. The current
   `NormalEgress` logic guarantees a short building exit, but a target-facing alternative can still
   follow an arbitrary ray. Replace target-facing ray exits with projections normal to actual
   facade segments and validate the final exported demand edge. Apply the rule consistently to the
   intended variants before starting the next long calculation.
2. **`global-tree-46` needs one complete corrected-dataset run and visual review.** Its two attempted
   runs were cancelled, first to remove duplicate work and then to add the new building-entry rule.
3. **Grouping is still a late repair.** If the group-spine geometry is useful, move clustering into
   primary construction: build local group trees first, then connect their roots with a global
   trunk. This should improve both geometry and runtime.
4. **Visibility search dominates runtime.** Repeated 75/200/600 m visibility graphs are the main
   cost. A group should receive one common corridor search; precise obstacle routing should be
   limited to final branches.
5. **Three expert-angle findings remain in the last completed result.** Known subjects include the
   overshoot/U-turn around `shared:edge:16` and a difficult graft near
   `shared:graft:branch:15`.
6. **Road polygons are diagnostic only.** The supplied corrected dataset has no official road
   geometry. Do not infer mandatory road corridors from the visual basemap or make the OSM-enriched
   experiment the default.

## Recommended continuation

1. Implement and cover strict wall-normal building entry, including irregular/rotated facades and
   final-diameter rerouting failure.
2. Rebuild the local Compose stack and run the corrected import once with the next algorithm
   version.
3. Compare the complete result against `global-tree-44`: route shape in every problem zone first,
   then expert warnings, length, cost, chamber count and runtime.
4. If the group spine is visually better, move it before per-demand global attachment and reuse one
   visibility corridor per group.
5. Only after the topology stabilizes, restore the focused tests and then the full required gate.

Do not hard-code the corrected dataset's coordinates, object IDs or highlighted screenshots. All
accepted routing operators must remain generic and must pass the complete official geometry,
topology, sizing and economics validation before publication.
