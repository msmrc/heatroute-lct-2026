# ADR-006: CP-SAT foundation for the next-generation network solver

- Status: accepted for staged implementation; not promoted to `stable`
- Date: 2026-09-28
- Related specification: [`CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md`](../../CODEX_NEXTGEN_NETWORK_SOLVER_SPEC.md)

## Context

The current planner spends tens of minutes generating and rechecking local route combinations.
The replacement needs a joint discrete model for topology, port/path alternatives, shared assets,
diameters and proven conflicts, while JTS and the existing engineering services remain the owners
of exact geometry and final admission. Java 11, the single Spring Boot backend and the normal API
and export path remain mandatory.

The implementation survey in the related specification considered Google OR-Tools CP-SAT,
Timefold, Choco and commercial MIP solvers. Timefold is useful for heuristic planning but does not
provide the required exact Boolean/integer conflict model and infeasibility semantics. Choco can
model the problem, but CP-SAT has the stronger ready-made Boolean/linear modeling surface,
asynchronous `stopSearch` API and maintained Java/native distributions needed here. A commercial
solver would add licensing and deployment constraints. The existing A*/visibility routing remains
in place for geometric path generation; CP-SAT does not replace it with a second geometry engine.

## Decision

- Pin `com.google.ortools:ortools-java:9.15.6755` under the Apache-2.0 license.
- Keep the native boundary in `CpSatRuntime`: load JNI before constructing a model, use one
  deterministic worker initially, latch cancellation and repeat asynchronous `stopSearch` across
  the native-handle registration race.
- Package only Windows x86-64 (development) and Linux x86-64 (release) native artifacts.
- Replace the Alpine/musl API images with pinned Ubuntu/Temurin glibc images. Run the final image as
  UID/GID 10001 and extract Java/JNA temporary files only under `/var/lib/heatroute/tmp`.
- Include a known-optimum capability solve in API readiness, so a packaged image with an unusable
  native library never reports ready.
- Build the replacement incrementally. The first vertical slice solves joint port alternatives
  with pair/hyper-conflicts and an exact frozen-geometry callback. It is not wired into the current
  stable planner until the catalog, frozen evaluator, lifecycle and end-to-end gates are complete.

## Consequences

The API artifact and image become larger and include native code plus JNA and Protobuf. Builds need
glibc on the Linux target and must test actual load/solve/stop inside the packaged environment.
Native memory is outside the Java heap, so later promotion requires cgroup/RSS measurements and a
single concurrent solver job before capacity is raised.

CP-SAT optimum means optimum only inside the supplied finite catalog and declared integer
objective. It does not prove a globally optimal physical route. Geometry timeouts and incomplete
catalogs remain `UNKNOWN`; they may not create no-good cuts or `no_route` conclusions.
