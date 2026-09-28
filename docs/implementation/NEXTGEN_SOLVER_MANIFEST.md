# Next-generation solver dependency and runtime manifest

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
- queued execution resolves the persisted routing-engine version before loading feature windows;
  no worker may silently execute a queued run with another registered version.
- `/api/v1/health/ready` includes cached `cp_sat` capability after a real known-optimum solve.

## Required before promotion

N01 still requires the clean packaged-image build/readiness evidence recorded below. N02 requires
differential official-dataset and depth evidence. N05 is a tested finite-catalog master, not an
end-to-end solver: N03 windowed path generation and chamber configurations, production section and
node-realization wiring, N06 adaptive expansion and three-role portfolio integration,
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
- clean fast Java gate excluding the three documented long dataset classes: 2,343 tests, zero
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
