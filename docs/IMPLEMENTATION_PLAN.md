# Implementation plan

The detailed plan is `implementation/OFFICIAL_TZ_ROADMAP.md`. This file defines execution order.

| Order | Stage | Owner focus | Exit gate |
|---:|---|---|---|
| 1 | Q&A-P0 economics | Developer | Implemented locally; supplied rank/export, connect-vs-penalty and strict-profile regression require final verification |
| 2 | Q&A-P0 geometry | Developer | Implemented locally; normal OKS egress and bend preference/economics require final verification |
| 3 | Q&A-P0 crossings/tie-ins | Developer | Implemented locally; overlap max `K_special` and per-ray tie-in require final verification |
| 4 | Q&A-P0 scale | Developer | Spatial-window calculation implemented locally; dense-window and PostGIS equivalence verification pending |
| 5 | R9 acceptance | Developer + PM | Updated supplied-profile contract, offline package and Ubuntu 22 evidence |
| 6 | Strict reconstruction / R8 | Developer | Preserve as optional profiles pending written organizer clarifications |

The active clarification checklist is
`implementation/ORGANIZER_VIDEO_CLARIFICATIONS.md`. PM owns written organizer clarifications,
official catalog transcription review, fixture provenance,
acceptance evidence and protection of P0 scope. The developer owns implementation, boundary/golden
tests, deterministic contracts and measured evidence. See `implementation/TOMORROW_HANDOFF.md`
for the exact first slice.
