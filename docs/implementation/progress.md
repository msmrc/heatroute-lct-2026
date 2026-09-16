# Current implementation progress

**Updated:** 2026-09-15

## Active baseline

The production path is Java-only. `apps/api` contains Java 11 / Spring Boot 2.6.3; default,
offline and VPS Compose use that image. Python application code, dependencies, migrations, tests
and runtime services were removed. The frontend calls only the current official Java endpoints.

## Verified in this cutover

- Maven `verify` succeeds in the pinned Java 11 builder image; 26 Java tests pass.
- Web ESLint, TypeScript, Vitest (4 tests) and production Vite build pass.
- Compose starts PostGIS, Java API and web; all three become healthy.
- `/api/v1/health/ready` reports PostGIS ready.
- Java OpenAPI is saved as `packages/api-client/openapi.json`.
- Browser smoke at 1264×712 shows the official workspace, Java-ready status and upload action
  without a blank page or framework error overlay.
- Live fixture import `63d30d86-e1db-4d03-b839-0300f583df4f` persisted as `valid` with eight
  features across the seven required types.
- Durable topology job `0afabe99-408e-43d4-84b5-148bc8835bb8` completed on attempt 1 and returned
  a valid topology with two deterministic tie-in candidates.

## Roadmap truth

- R0 — complete: official gap audit, Java decision and team roadmap.
- R1 — complete for current single-process foundation: Java runtime, PostGIS readiness, Liquibase,
  Swagger, durable PostgreSQL job state, claim/lease/cancel/recovery, Docker and CI.
- R2 — functionally implemented for the current contract fixture; large-file memory measurement,
  replay/deduplication policy and broader official-like fixtures remain acceptance work.
- R3 — functional vertical slice: topology validation, chamber rule, deterministic candidates and
  line splitting. Indexed large-network search and persistence of selected tie-ins remain.
- R4 — not implemented: multi-OKS routing/tree construction and final independent validator.
- R5 — partial: pure sizing/DU/continuous-length rules exist; existing-network flow propagation
  and reconstruction are not implemented.
- R6 — partial: catalog and crossing geometry exist; route-search and final-validator integration
  are not implemented.
- R7 — not implemented: official costing, ranking, diversity and strict output export.
- R8 — not implemented; optional after mandatory 2D.
- R9 — not complete: no 3 GB/500 MB/50-user evidence and current VPS OS is not the required
  Ubuntu Server 22 acceptance target.

## Next change

Implement the first R4 vertical slice described in `TOMORROW_HANDOFF.md`: immutable run record,
all-OKS input assembly, deterministic shared/separate candidate trees and a framework-independent
tree validator. Do not start depth, MVT or additional file formats before this slice passes.

Older `m1-evidence.md` … `m6-engineering-evidence.md` are historical prototype records only.
