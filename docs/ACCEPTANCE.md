# Acceptance gates

An R-stage is complete only when its roadmap checklist and evidence are both complete.

## Every change

- Java 11 Maven tests pass.
- Web lint, typecheck, tests and production build pass.
- Compose validates and starts from a clean checkout without secret defaults in production.
- API changes update `/v3/api-docs` and `packages/api-client/openapi.json`.
- A failure path is tested as well as the happy path.

## Official P0 release

- one run handles all `oks_future` and automatically chooses feasible tie-ins;
- shared/separate topology, chambers and tree invariants pass independent validation;
- official flow, 18-row DU table, continuous lengths and upstream reconstruction are exact;
- every restriction and special crossing has positive, boundary and negative tests;
- official costs, penalties and score reproduce appendix examples;
- up to three alternatives are materially different and ranked deterministically;
- strict seven-type GeoJSON passes schema and reference validation;
- partial no-route output preserves successful OKS and lists/penalizes failures;
- 3 GB input, 500 MB output, 16 GB RAM and 50-user evidence is recorded;
- clean Ubuntu Server 22 / docker-compose 1.29.2 deployment and restart recovery pass;
- UI displays the official output rather than a legacy/internal model.

Current local smoke evidence and open gates are in `implementation/progress.md`.
