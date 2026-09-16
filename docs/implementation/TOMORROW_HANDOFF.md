# Tomorrow handoff — PM and developer

**Prepared:** 2026-09-16
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
- Commit `e47cd72` is deployed on the VPS. Production now runs only PostGIS, Java API, web and
  gateway; public HTTPS, Java readiness, official import, topology and immutable calculation run
  were verified.

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

The newly supplied dataset is not shaped like the published seven-type contract. Read
`SUPPLIED_DATASET_AUDIT.md` before changing validation or routing. Use its 17 connection points as
demand objects under the named compatibility profile; never fabricate missing existing flows or
upstream links.

Already usable:

- streaming seven-type GeoJSON inspection and PostGIS persistence;
- WGS84 plus EPSG:32637 storage;
- existing-network topology diagnostics;
- deterministic tie-in candidates and 10 m chamber feasibility rule;
- durable PostgreSQL topology jobs with progress/cancel/recovery;
- pure Java official restriction catalog, crossing geometry and DU sizing primitives.
- immutable R4 runs with deterministic independent/shared variants, partial no-route and a separate
  tree/chamber/crossing validator;
- production evidence on the organizer file: the preferred shared variant connects all 17 demands
  with zero structural validator issues.
- interactive R4 result viewer with MapLibre GL/CARTO vector basemap, PostGIS source context, layer toggles,
  variant comparison, map-object inspection, no-route diagnostics, a retained metric schematic and
  a latest-completed-run demo endpoint. It renders the internal route graph plus official input
  features and must later be extended by the official R7 seven-type output adapter.
- map-first result UX with floating inspector/results islands over one uninterrupted map, the real
  imported filename in the toolbar and a collapsible navigation rail. Technical stack, version,
  team and Swagger live on the separate `/system` page instead of the work screen.

Still blocking official P0:

- obstacle-aware multi-OKS route search and special passages;
- route normalization/local improvement and a third diverse alternative;
- reconstruction propagation to source;
- constraints integrated into routing;
- official component costs, score and alternative diversity;
- strict seven-type result export;
- large-file/load/Ubuntu 22 acceptance evidence.

## Developer: next vertical slice

The first R4 slice is implemented in `domain/routing` and evidenced in `r4-evidence.md`. Continue
from its normalized directed trees; do not replace the immutable run/job contract.

Exit criteria:

1. route search treats forbidden buffers as impassable and creates only permitted special passages;
2. path normalization cannot invalidate clearance, angle, chamber or crossing invariants;
3. at least three materially different alternatives can be produced when the geometry permits;
4. every accepted variant passes the independent validator after normalization;
5. valid trees feed the existing bottom-up sizing primitive without adapter-side reinterpretation.

Do not mix official costing into the first PR; expose explicit placeholder quantities if needed.

## PM: tasks tomorrow

- verify the exact per-output-type fields against the organizer appendix and approve a machine-
  readable JSON Schema before R7;
- confirm whether Ubuntu 22 is mandatory for judging even though the current demo VPS uses a
  newer Ubuntu release;
- supply or approve an official-like fixture with at least two nearby and two distant future OKS;
- keep optional depth, MVT and extra formats outside P0 until R4–R7 close;
- review every “complete” claim against `docs/ACCEPTANCE.md`, not old M-stage evidence.
- ask the organizer to resolve the supplied-dataset mismatch, especially `railway`, direct demand
  on connection points and the missing existing-network reconstruction attributes.

## Known operational notes

## Latest product-flow decision (2026-09-16)

- Do not reintroduce the import/report page into the valid-file happy path. Upload must proceed as
  `file -> loader/progress -> completed map` and start the official run automatically.
- Input warnings remain available from the clickable `Проверка структуры` metric in the result
  island. Keep the full API diagnostics; do not replace them with a fake aggregate.
- Current deployed baseline is `407c0a7`; CI run `35089560867` passed all jobs.

- Local Docker data and tool caches must remain on `E:`.
- Never commit `.env.vps`, keys or dumps. The sole approved organizer dataset is the byte-identical
  `datasets/official/lct-2026.geojson`; do not add copies or synthetic dataset files.
- VPS updates follow `docs/operations/VPS_DEPLOYMENT.md`; take a DB backup first.
- Database schema history is now Liquibase under `apps/api/src/main/resources/db/changelog`.
- On the current Windows workstation Docker Desktop is blocked after reboot by a stale internal
  socket. CI and VPS are healthy. Do not factory-reset Docker or move its `E:` data; repair the
  local daemon separately before using the local `up` command.
