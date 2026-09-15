# HeatRoute: project instructions for Codex

## Goal
Build a backend-first web system for preliminary district-heating route planning. Use the supplied specifications, real algorithms and explicitly synthetic demo inputs. Do not substitute a polished frontend mock for working computation.

## Read before implementation
Read `docs/TECH_SPEC.md`, then the relevant sections of `docs/DATA_CONTRACTS.md`, `docs/ACCEPTANCE.md`, `docs/UI_SPEC.md` and `docs/IMPLEMENTATION_PLAN.md`. Real-data onboarding is defined in `docs/ORGANIZER_DATA_PLAYBOOK.md`. Technical references are in `docs/SOURCES.md`.

## Architecture
- Modular Python/FastAPI backend; PostgreSQL/PostGIS; separate Celery workers; persistent job state and versioned results.
- Raw → staging → canonical imports. Source schemas belong in adapters/mappings, not in routing or React.
- Domain services must run and be tested without a browser. No long-running CPU/GDAL work inside HTTP handlers.
- React/TypeScript/Vite + official shadcn/ui + MapLibre. Reuse components and tokens; no landing-page-first workflow.

## Non-negotiable correctness
- No fabricated routes, costs, throughput, engineering checks or performance claims.
- Unknown is not zero, infinity or passed. Keep geometry, cost, capacity and hydraulic statuses separate.
- Compute metric geometry in a confirmed project CRS; API GeoJSON is longitude/latitude WGS84.
- Validate complete route corridors and connectors, not just endpoints. Revalidate after every geometric edit.
- XY line crossings do not automatically create physical pipe junctions.
- Road portals grant narrow exceptions only; they do not override unrelated obstacles.
- Distinguish supply/return pipes from construction corridor quantities.
- Hard restrictions cannot be converted to penalties. Entropy is never an engineering capacity model.
- No-route in a discretized, bounded graph is not proof of physical impossibility.
- Immutable data/rule/cost/scenario versions and complete cache keys are mandatory.

## Working approach
Follow milestones M0–M5 to complete P0. Implement a real backend/CLI/worker-to-map vertical slice before extended UI. P1/P2 must remain explicitly unavailable until implemented and validated.

Preserve existing user changes. Inspect the checkout before edits. Do not replace a working stack without a specific compatibility reason and ADR. Do not deploy, publish private data, perform destructive operations or change production credentials without authorization.

Use stable compatible dependencies, checked against official docs, and commit lockfiles. Do not ship unpinned latest images. Never fetch sensitive infrastructure data into third-party tools without explicit permission.

## Verification
Implement and run documented equivalents of: `make bootstrap`, `make up`, `make migrate`, `make seed-demo`, `make test`, `make test-integration`, `make test-e2e`, `make benchmark-demo`, `make lint`, `make typecheck`, `make export-openapi`.

These commands are requirements, not existing commands until implemented. Report actual commands, exit codes, passed/failed checks and environment blockers. Never claim tests ran when they did not.

Keep `docs/implementation/progress.md` current. At session end, state the completed milestone, evidence, remaining issues and next actionable step. A plan or a screenshot alone is not completion.

## VPS deployment
The shared demo VPS is updated only through the documented procedure in
`docs/operations/VPS_DEPLOYMENT.md`. Before changing it, verify the repository is clean, create a
database backup, fast-forward `master`, validate the merged Compose config, rebuild, wait for
health checks and run the smoke checks. Never copy `.env.vps`, `secrets/`, database dumps or SSH
keys into Git, and never expose the API, PostgreSQL or Redis ports publicly.
