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
- strict seven-type GeoJSON passes the published JSON Schema and cross-feature reference validation;
- partial no-route output preserves successful OKS and lists/penalizes failures;
- 3 GB input, 500 MB output, 16 GB RAM and 50-user evidence is recorded;
- clean Ubuntu Server 22 / docker-compose 1.29.2 deployment and restart recovery pass;
- UI displays the official output rather than a legacy/internal model.

Current local smoke evidence and open gates are in `implementation/progress.md`.

## Current evidence snapshot (2026-09-16)

| Gate | State | Evidence / qualification |
|---|---|---|
| All-demand routing, automatic tie-ins, tree/chambers, partial no-route | Passed | 17-demand clean-stack calculation plus focused R4 tests |
| Flow, 18-row DU catalog, continuous length and reconstruction | Passed on contract-complete input | Supplied file omits reconstruction baseline/direction fields |
| Published 2D restrictions and special crossings | Passed | Exact-value positive/boundary/negative matrix |
| Cost, penalty, score and deterministic rank | Passed on contract-complete input | Appendix 10.8 illustrative-number discrepancy is documented |
| Strict seven-type output and UI consumer | Passed on contract-complete input | Draft 2020-12 schema is published/tested; supplied incomplete file correctly returns `OFFICIAL_EXPORT_INCOMPLETE` |
| 3 GiB input / ≥500 MiB output / memory below 16 GiB | Passed | Run `35112046184` plus full topology runs `35119722470` and `35120995991` |
| 50 concurrent users | Passed for public API sessions | Runs `35112362689` and `35126566499`; 50 simultaneous imports, one durable ID; the latter verifies the connection-pool starvation fix |
| Ubuntu 22 / docker-compose 1.29.2 / restart recovery | Passed in clean CI | Run `35112362689`; production-like host may still be requested |
| Full calculation beyond supplied topology | Passed on project 2× profile | Run `35119722470`: 288 features, 34 demands, 408 candidates, 481,092 KiB peak RSS; organizer/PM must still approve the maximum profile |
| Supplied-file final reconstruction/score/export | Blocked on source data | Requires missing flow, upstream direction and chamber diameter or a waiver |

Detailed byte/memory and concurrency measurements are in
`implementation/R9_INPUT_SCALE_EVIDENCE.md` and
`implementation/R9_CONCURRENCY_EVIDENCE.md`; the complete route measurement is in
`implementation/R9_TOPOLOGY_SCALE_EVIDENCE.md`.
