# Tomorrow handoff — PM and developer

**Prepared:** 2026-09-15
**Repository:** `git@github.com:msmrc/heatroute-lct-2026.git`
**Branch:** `master`
**Public demo:** `https://130-49-150-217.sslip.io/`

## What changed today

- Java is now the only backend and lives at `apps/api`.
- Default/local/offline/VPS Compose all point to the Java image.
- The old Python application, Alembic, Celery/Redis services, Python tests and lockfiles are gone.
- The web app calls the Java official import/job contract and no longer calls legacy project/run
  endpoints.
- CI has three gates: Java verify/image, web quality/build, and live Compose smoke.
- OpenAPI comes from springdoc and is committed at `packages/api-client/openapi.json`.

## Start in five minutes

```powershell
cd E:\job\_lct2026\heatroute_codex
git status --short
git pull --ff-only origin master
pwsh -File scripts/dev.ps1 test
pwsh -File scripts/dev.ps1 up
```

Open `http://localhost:5173/` and `http://localhost:8000/swagger-ui.html`. If 5173 is occupied,
set `$env:WEB_HOST_PORT='5174'` for the smoke rather than killing an unknown process.

## Current reality

The Java foundation/import/topology slices work, but the product is not feature-complete. The
public UI currently exposes only what the Java backend actually supports. Do not restore legacy
screens until equivalent official Java endpoints exist.

Already usable:

- streaming seven-type GeoJSON inspection and PostGIS persistence;
- WGS84 plus EPSG:32637 storage;
- existing-network topology diagnostics;
- deterministic tie-in candidates and 10 m chamber feasibility rule;
- durable PostgreSQL topology jobs with progress/cancel/recovery;
- pure Java official restriction catalog, crossing geometry and DU sizing primitives.

Still blocking official P0:

- joint multi-OKS routing and shared trunks;
- valid tree/chamber construction and independent final validator;
- reconstruction propagation to source;
- constraints integrated into routing;
- official component costs, score and alternative diversity;
- strict seven-type result export;
- large-file/load/Ubuntu 22 acceptance evidence.

## Developer: first vertical slice tomorrow

Create an immutable `official_run` aggregate and a pure-Java R4 service that accepts every future
OKS and the R3 candidate set. For the first slice it must produce deterministic candidate forests
for two strategies: independent connections and one shared-trunk heuristic. Add a separate
validator that rejects cycles, multiple upstreams, branch points without chambers, more than four
incident sections and crossings outside a common node.

Exit criteria:

1. fixture with two nearby OKS prefers a shared section when cheaper;
2. fixture with distant OKS retains separate paths;
3. impossible OKS becomes a partial/no-route item without discarding others;
4. same input/version gives byte-stable normalized topology;
5. controller queues the run through the existing durable job mechanism;
6. all new invariants have negative tests;
7. progress and `OFFICIAL_TZ_ROADMAP.md` are updated honestly.

Do not mix official costing into the first PR; expose explicit placeholder quantities if needed.

## PM: tasks tomorrow

- verify the exact per-output-type fields against the organizer appendix and approve a machine-
  readable JSON Schema before R7;
- confirm whether Ubuntu 22 is mandatory for judging even though the current demo VPS uses a
  newer Ubuntu release;
- supply or approve an official-like fixture with at least two nearby and two distant future OKS;
- keep optional depth, MVT and extra formats outside P0 until R4–R7 close;
- review every “complete” claim against `docs/ACCEPTANCE.md`, not old M-stage evidence.

## Known operational notes

- Local Docker data and tool caches must remain on `E:`.
- Never commit `.env.vps`, keys, dumps or organizer datasets.
- VPS updates follow `docs/operations/VPS_DEPLOYMENT.md`; take a DB backup first.
- Database schema history is now Liquibase under `apps/api/src/main/resources/db/changelog`.
