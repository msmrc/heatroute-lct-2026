# HeatRoute solver dependency and runtime manifest

**Stage:** N01/N02 foundations / N03 catalog and physical assets / N04 port slice / N05 master / N06 proof store.
**Date:** 2026-09-28.
**Promotion status:** not connected to `stable`; no competition result or speed-up is claimed yet.

## Pinned runtime

| Component | Version / digest | License / role |
|---|---|---|
| Java | Temurin 11.0.28+6 runtime; local tests use 11.0.32+1 | Java 11 target |
| Maven build image | `maven:3.9.12-eclipse-temurin-11@sha256:f39c21c3fef9ec69a85fa024c5513fb28dbcd2f282a27a11dae4568b7547f535` | Ubuntu Noble/glibc build |
| API runtime image | `eclipse-temurin:11.0.28_6-jre-jammy@sha256:fc451894669bc656f81082ced9daddfd8df0d8e8fea659dc26a232f0e0124837` | Ubuntu 22.04/glibc runtime |
| OR-Tools Java/CP-SAT | 9.15.6755 | Apache-2.0; discrete master solver |
| OR-Tools native | Windows x86-64 and Linux x86-64, 9.15.6755 | Development and release targets only |
| JNA / JNA Platform | 5.14.0 | Apache-2.0/LGPL dual distribution; native extraction support |
| Protobuf Java | 4.33.1 | BSD-3-Clause; OR-Tools model/response runtime |

`mvn dependency:tree` is the source of transitive versions. Maven cache, Java/JNA temp and local
evidence paths are directed to `E:\job\.tooling`; the container uses
`/var/lib/heatroute/tmp`. Runtime calculation has no network dependency.

## Implemented boundaries

- `CpSatRuntime` owns JNI initialization, deterministic session settings and cancellation.
- `CpSatChoiceOptimizer` owns exactly-one groups, integer guiding cost and sound no-good addition.
- `DiscreteChoiceProblem` validates immutable catalog IDs and conflict references.
- `CpSatPortAssignment` is an isolated N04 vertical slice next to `CorridorPortSearch`; the exact
  existing compatibility and engineering evaluators decide admission.
- `FrozenNetworkCandidate` deep-copies mutable feature geometry/attributes and records a
  deterministic topology/XY hash. `FrozenNetworkEvaluator` is the only constructor boundary for
  `AcceptedNetworkSolution`; canonical sizing feedback, UNKNOWN and ERROR are typed outcomes.
- `NetworkConstraintProblem` and `CpSatNetworkOptimizer` implement the exact finite-catalog N05
  master for acyclic multi-root flow, mandatory terminals, one diameter per selected physical
  asset, capacity, shared cost and pair/hyper-conflicts.
- `RoutingProblemSnapshot`, `DirectedPathOption`, `PathAdmissionCertificate` and
  `RoutingCatalogSnapshot` establish immutable N03 identities without materializing all obstacle
  geometry. `CatalogBuildResult` keeps truncation distinct from proven infeasibility.
- `PhysicalAssetCompiler` uses the existing JTS 1.20 robust intersector plus an `STRtree` to
  atomize only collinear overlaps with the same physical context/construction mode. Point crossings
  do not create free junctions; reverse traversal shares physical identity but keeps direction.
- `CatalogNetworkProblemCompiler` resolves strict physical chains into N05 nodes/arcs using only
  explicit ports and declared adjacency. It reuses `OfficialPipeCatalog` for capacity and a clearly
  labelled linearized objective; exact sizing/economics stay in the frozen evaluator. Explicit
  zero-flow demands remain mandatory connectivity terminals.
- `CatalogFrozenCandidateAssembler` converts a feasible master assignment into frozen route nodes,
  edges and connections. It collapses compatible degree-2 accounting atoms, retains canonical
  demand sizing IDs, requires explicit chamber/port realizations and delegates exact section
  construction; incompatible or branching anonymous nodes are rejected instead of becoming free
  chambers.
- `ConflictExplanation` and bounded `ConflictStore` retain versioned positive/negative asset,
  root and diameter proof literals under source/rule/checker/catalog scope. Full-assignment no-goods require the exact decision set;
  compatible stable-subset proofs may survive monotonic catalog expansion.
- `CpSatNetworkRefinement` runs a bounded first-incumbent solve/check/add-cut loop. It reserves
  final-admission time, stops CP-SAT on its first feasible callback, applies proof batches
  atomically, stops on UNKNOWN without a cut and
  distinguishes catalog infeasibility, search limit, stalled proof, cancellation and technical error.
- `CatalogFrozenNetworkRefinement` is the production bridge from that loop to
  `FrozenNetworkEvaluator`. Exact rejection creates an auditable no-good only for the complete
  current Boolean catalog assignment. Canonical sizing instead preserves the full topology/root
  scope and implies the required DU for every source arc in a collapsed physical chain. If that DU
  is absent, the outcome requests catalog expansion without a cut. UNKNOWN/ERROR also create no
  permanent cut. Accepted exact solutions are inserted directly into the bounded archive.
- `AcceptedSolutionArchive` is a bounded, exact-metrics-only store for evaluator-approved networks.
  It deduplicates topology/XY with a full equality check after the hash lookup and evicts the worst
  exact score/cost/length entry instead of allowing unbounded candidate growth.
- `AdaptiveCatalogNetworkSearch` keeps the archive/conflict store across bounded catalog stages
  under one monotonic deadline. Incomplete stages may request addressable expansion; unchanged or
  non-additive replacements are rejected. Old physical/path fingerprints, master semantics,
  decision keys and static conflicts must remain reproducible. Exhaustion stays
  `CATALOG_INCOMPLETE`, and a verified archive incumbent is not lost on a later limit.
- `CatalogNetworkStageCompiler` is the single production wiring boundary from a
  `RoutingProblemSnapshot` plus `CatalogBuildResult` to the executable stage. It compiles the
  master, derives its exact identity, freezes the feature window, resolves explicit nodes and
  constructs deterministic assignment-specific frozen candidates with edge→master provenance.
- Exact root-node realization now belongs to `RoutingProblemSnapshot` and its hash.
  `CatalogProblemNodeRealizationResolver` maps declared root/demand ports without inventing branch
  chambers. `CatalogEdgeSectionAssemblerFactory` prepares one immutable routing environment and
  reconstructs base/special sections at the selected DU; invalid hybrid crossing assembly is left
  for ordinary exact rejection. Missing chamber/node configurations request catalog expansion
  through a typed non-proof outcome instead of becoming a technical error.
- `BoundedRootDemandCatalogGenerator` now supplies a real obstacle-aware initial N03 seed without
  invoking the full legacy planner. It reuses one prepared `OfficialRoutingEnvironment`, normal
  terminal egress and the existing engineering/left/right router paths, then compiles coincident
  geometry into shared physical assets. Pair/call/egress/path/time limits are telemetry, and the
  result explicitly leaves shared-network seeds and chamber configurations uncovered.
- `PreparedRoutingFeatureWindow` and `BoundedCatalogNetworkStageFactory` now make that initial path
  executable with one owned feature snapshot and one prepared obstacle/crossing environment.
  Generation, section assembly and exact evaluation reuse the same indexes/caches; directly built
  legacy test candidates retain the isolated preparation fallback. An empty seed stays a typed
  incomplete preparation and never creates an empty master.
- Executable stages now compile exact local node configurations into CP-SAT. Every used node picks
  one complete incident-arc set; root capacity includes immutable existing rays, demands cannot
  become transit nodes, and three/four-way chambers require pairwise orthogonal/opposite rays.
  Configuration choices participate in identity, no-good scope, canonical sizing and additive
  expansion. The frozen assembler publishes selected branches as real `new_branch_chamber` nodes.
- `RoutingProblemFactory` now builds the immutable small problem from the bounded production core:
  extended demand flow is resolved through the linked `oks_future`, missing flow is rejected,
  coincident connection points stay distinct, and topology tie-ins become exact roots using the
  existing support incidence/ray/diameter oracle. The normal calculation adapter passes import ID,
  source hash, contract version and profile without changing the stable planner implementation.
- The then-current `NextGenerationRoutePlanner` owns an internal production-shaped execution path without
  registering a second routing adapter. One monotonic budget covers deterministic bounded-window
  loading, initial catalog generation, CP-SAT refinement and frozen evaluation. The selected
  existing-network target is exempt only for its legal endpoint contact. Accepted exact networks
  are converted through the existing final selector/result contract; incomplete work remains
  diagnostic and never becomes `no_route`.
- queued execution resolves the persisted routing-engine version before loading feature windows;
  no worker may silently execute a queued run with another registered version.
- `/api/v1/health/ready` includes cached `cp_sat` capability after a real known-optimum solve.

## Required before promotion

N01 still requires the clean packaged-image build/readiness evidence recorded below. N02 requires
differential official-dataset and depth evidence. N05 is a tested finite-catalog master, not an
end-to-end solver: N03 targeted shared-path expansion and relocated/DU-aware chamber configurations,
registration of the new planner in the job/API path, targeted terminal/chamber expanders, mapped asset-split
rebasing and three-role portfolio integration,
full lease-attempt fencing, official pipeline/API/export,
performance, resource, Ubuntu 22 and release gates remain open. The active `stable` implementation
must not be switched until those gates pass.

## Verification record

Completed on the 2026-09-28 working tree with JDK/temp/Maven cache on `E:`:

- combined native/model/evaluator/versioning/performance-regression suites: 76 tests, zero
  failures/errors;
- 80 seeded small finite catalogs match an independent exhaustive network enumerator;
- catalog/proof-scope focused tests cover direction/context/ДУ, source/rule changes, negative
  literals, catalog expansion, exact full-assignment scope and bounded proof storage;
- clean fast Java gate excluding the three documented long dataset classes: 2,371 tests, zero
  failures/errors and 3 existing skips;
- web: 8 Vitest files / 36 tests and 37 Node tests; ESLint and TypeScript pass;
- `mvn dependency:tree`: OR-Tools 9.15.6755, JNA/JNA Platform 5.14.0 and Protobuf 4.33.1;
- the executable Spring Boot JAR packages only the Windows x86-64 and Linux x86-64 OR-Tools
  artifacts; Darwin and Linux ARM artifacts are absent;
- the base, VPS override and offline override Compose configurations parse successfully (the two
  legacy overrides still emit their pre-existing obsolete `version` warning);
- Windows native known optimum, repeated load, explicit asynchronous stop and direct Java-thread
  interruption pass.

`docker build --target test` did not enter the build: Docker Desktop crashed on the pre-existing
zero-byte reparse socket `C:\Users\dragon\AppData\Local\Docker\run\sailor-ingest.sock` dated
2026-09-16, and machine policy rejected its removal. Linux glibc load/solve/stop, final non-root
image, Compose readiness and live smoke are therefore **not verified**. Historical source102
results remain the legacy comparison baseline and are not results of this solver.

### 2026-09-29 local packaged-runtime follow-up (performance snapshot R5)

The earlier Docker blocker is not reproduced with a bounded checkout-local build context.
The unchanged production Dockerfile built `heatroute-api:perf-r5` from the immutable
`candidate-r5` source snapshot (base `052dedb` plus the documented common hot-path changes).
In a separate local Compose project, without replacing the user's running application:

- final Ubuntu 22.04 image runs as `uid=10001(heatroute)`, Temurin `11.0.28+6`;
- `/api/v1/health/ready` returns `ready`, PostGIS `3.5` and native `ortools-9.15.6755` both `ok`;
  the CP-SAT status executes the real known-optimum capability solve before caching success;
- bundled official import succeeds: 144 features, 633402 bytes, zero errors, source SHA-256
  `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`;
- local evidence is under `.tooling/optimization-20260929/evidence/r5-runtime-*`.

This is R5 packaging/readiness/import evidence, not nextgen promotion, a completed API route job,
or evidence for later source edits (including R6). Full API/job/export, repeatable performance,
and the other promotion requirements above remain open. See
[the performance report](ROUTING_PERFORMANCE_2026_09_29.md) for measured scope and pending gates.

### 2026-09-29 R8 packaged API/job/export follow-up

The isolated `heatroute-api:perf-r8` stable/source103 runtime completed official depth-enabled
run `7a673880-b5e3-401e-b02c-ef254aa5543b` on attempt1 in1721.878047 seconds
(persisted created→completed, including queue). Both variants connect17/17; strict HTTP exports
returned200 with60/74 features. Readiness, OpenAPI and the144-feature official import also pass.
The temporary Compose project is stopped, with its data retained; the user's main stack is unchanged.
These are R8 stable runtime results, not nextgen promotion or evidence for subsequent R9 edits.
The in-memory baseline has different input preparation/JVM, so production-shaped speed-up still
requires a matched baseline/final API pair. See the performance report for exact evidence and scope.

### 2026-09-29 matched stable API performance pair

The isolated baseline052dedb/R10 default-2D pair passed on the same persisted official import,
runtime/JVM and2-CPU/4-GiB limits:2554.766262 →1658.202680 seconds (−35.09%,1.5407x).
Both jobs completed on attempt1; all2092 result values match exactly, both variants connect17/17,
and strict HTTP exports return200. Temporary services are stopped with data/evidence retained.
This is one pair, not established repeatability, nextgen registration, or closure of the remaining
promotion gates. Later R11/trial source changes were excluded from the measured runtime images.

### 2026-09-29 R13 stable API follow-up

The R13 image completed run74470b23-fec8-4b19-8f95-1fee79ca8a55 on attempt1 in
1531.358837 seconds (25m31.36s), against the same persisted import, default2D
parameters and2-CPU/4-GiB limits. All2092 result values exactly match the earlier
baseline; both variants connect17/17 and both strict exports return200 (60/62features).
Compared with that earlier2554.766262-second baseline this is40.06% less elapsed
time (1.6683x), not a fresh paired repetition or established repeatability.
The isolated API/db are stopped with volumes/evidence retained; the main stack
is unchanged. R14 source changes are not included in this runtime evidence.
This stable optimization does not register or promote the next-generation planner.

### 2026-09-29 R15 stable API follow-up

The same-import/default2D R15 job completed on attempt1 in1464.763484 seconds
(24m24.76s), with all2092 result values exactly matching the retained baseline.
Both variants connect17/17; strict exports return200 (60/62features). Compared
with the earlier2554.766262-second baseline, elapsed time is42.67% lower (1.74415x).
This includes R14 angular cones and R15 dead initial-window removal; neither
isolated attribution nor repeatability is established. The finalized-portfolio
phase did not improve versus R13. API/db remain available for the user-requested
browser view on local port 5175. No nextgen registration/promotion follows.
