# Acceptance gates

An R-stage is complete only when its roadmap checklist and evidence are both complete.

## Every change

- Java 11 Maven tests pass.
- Web lint, typecheck, tests and production build pass.
- Compose validates and starts from a clean checkout without secret defaults in production.
- API changes update `/v3/api-docs` and `packages/api-client/openapi.json`.
- A failure path is tested as well as the happy path.

## Official P0 release

The active supplied-dataset gate follows
`implementation/ORGANIZER_VIDEO_CLARIFICATIONS.md`. Reconstruction/depth evidence is retained for
strict/optional profiles and does not replace the following 2D gates.

- one run handles all demand objects (`oks_future` in strict profile or direct connection points in
  supplied profile) and automatically chooses feasible tie-ins;
- shared/separate topology, chambers and tree invariants pass independent validation;
- official flow, 18-row DU table and continuous lengths are exact;
- every demand exits its containing OKS along a validated normal egress segment;
- connect-vs-penalty, bend ×1.5, overlap max `K_special` and per-ray tie-in cost are exact;
- every restriction and special crossing has positive, boundary and negative tests;
- official costs, penalties and score reproduce appendix examples;
- up to three alternatives are materially different and ranked deterministically;
- supplied-profile GeoJSON is downloadable and ranked without reconstruction baseline; strict
  reconstruction output remains valid when all source fields exist;
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
| 50 concurrent users | Passed for public API sessions | Runs `35126566499` and `35129162919`; 50 simultaneous imports, one durable ID; both include the connection-pool starvation fix |
| Ubuntu 22 / docker-compose 1.29.2 / restart recovery | Passed in clean CI | Run `35129162919`; production-like host may still be requested |
| Full calculation beyond supplied topology | Passed on project 2× profile | Run `35119722470`: 288 features, 34 demands, 408 candidates, 481,092 KiB peak RSS; organizer/PM must still approve the maximum profile |
| Supplied-file score/rank/export without reconstruction | Implemented, verification pending | Supplied profile bypasses reconstruction gating; strict profile retains it |
| Normal OKS egress and Q&A economics rules | Implemented, verification pending | Normal exit, connect-vs-penalty, bend ×1.5, overlap max coefficient and per-ray tie-in are integrated |
| Bounded 2–3 GB calculation | Partial | Repository/JDBC reads are keyset-paged; analyzer/planner still materialize the accumulated feature list |

Detailed byte/memory and concurrency measurements are in
`implementation/R9_INPUT_SCALE_EVIDENCE.md` and
`implementation/R9_CONCURRENCY_EVIDENCE.md`; the complete route measurement is in
`implementation/R9_TOPOLOGY_SCALE_EVIDENCE.md`.
