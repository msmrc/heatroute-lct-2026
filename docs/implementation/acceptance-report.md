# Local acceptance report

Date: 2026-09-08

The repository has no baseline commit yet, so evidence is tied to the working tree and immutable
run/version hashes rather than a commit SHA.

| Milestone | Local status | Evidence |
|---|---|---|
| M0 | complete | Compose/readiness/migrations/worker smoke and CI definition |
| M1 | complete | `m1-evidence.md` — ING-01…12, SEC-01…06 |
| M2 | complete | `m2-evidence.md` — GEO-01…14, TOP-01…10, CRS-01…08 |
| M3 | complete | `m3-evidence.md` — RTE-01…15, CST-01…10, JOB-01…10 |
| M4 | complete | `m4-evidence.md` — API-backed workspace and E2E-01…04, E2E-06 |
| M5 | complete | `m5-evidence.md` — exports, security/operations, production web and clean offline/restart rehearsal |

The local verification command set comprises Ruff, strict MyPy, all Python unit/integration tests,
web lint/typecheck/tests/build, OpenAPI generation/client drift, Compose config, Alembic head,
readiness, Celery worker smoke, secret audit, deterministic seed and routing benchmark. The final
counts are 118 Python unit tests, 20 live integration tests and 6 web tests. Detailed command
outcomes are maintained in `progress.md`.

Known limitations are not hidden: the demo uses synthetic data/prices, hydraulic and vertical
engineering checks are not performed, the benchmark is a small local measurement, the development
database profile is not durable, P1 backup/retention/external telemetry is not implemented, and
remote CI has not run because no remote repository/commit exists yet. These limitations do not
block the synthetic P0 demonstration but do block claiming production readiness for meaningful
real data.
