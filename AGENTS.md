# HeatRoute repository instructions

## Source of truth

The organizer documents override repository prose. The active implementation baseline is
`docs/implementation/OFFICIAL_TZ_ROADMAP.md`; the next-shift checklist is
`docs/implementation/TOMORROW_HANDOFF.md`. Historical M0–M7 evidence is not an acceptance claim.

## Architecture

- Backend: Java 11, Spring Boot 2.6.3, springdoc 1.7.0, JDBC, JTS/Proj4J, Liquibase.
- State: PostgreSQL 17 + PostGIS. Durable job state is stored and leased in PostgreSQL.
- Frontend: React 19, TypeScript, Vite, TanStack Query and the repository design system.
- Runtime: `db`, `api`, `web`; `gateway` is added by the VPS overlay.
- `apps/api` is the only backend. Do not add Python, FastAPI, Celery, Alembic or Redis back.

Keep domain algorithms framework-independent. Controllers orchestrate; repositories own SQL;
the final geometry and arithmetic must be independently validated before export.

## Required workflow

1. Work only inside the exact repository root on `E:` and preserve unrelated changes.
2. Update `docs/implementation/progress.md` and roadmap status with every completed gate.
3. Add focused Java tests for every official table boundary, geometry invariant and failure mode.
4. Run `pwsh -File scripts/dev.ps1 test`, `lint`, `typecheck`, then a live Compose smoke for
   API-affecting changes.
5. Never claim an R-stage complete while any item in its “Готово, когда” list is unverified.

Do not hard-code organizer coordinates, IDs or a single dataset layout beyond the official
contract. WGS84 is the API boundary; all metric work uses EPSG:32637. Large files must be streamed.

## Storage and secrets

Keep toolchains/caches on `E:\job\.tooling`. Never commit `.env.vps`, credentials, SSH keys,
database dumps, uploads or generated artifacts. Do not modify files under Downloads.

## VPS

Use only `docs/operations/VPS_DEPLOYMENT.md`. Update by fast-forwarding `master`, backing up the
database, rebuilding Compose, and verifying readiness plus a real official import/job. Never use
`git reset --hard` or delete the PostgreSQL volume as an update shortcut.
