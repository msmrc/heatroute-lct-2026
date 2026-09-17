# Implementation plan

The detailed plan is `implementation/OFFICIAL_TZ_ROADMAP.md`. This file defines execution order.

| Order | Stage | Owner focus | Exit gate |
|---:|---|---|---|
| 1 | Q&A-P0 economics | Developer | Supplied run gets complete cost/rank/export without reconstruction; connect-vs-penalty choice works |
| 2 | Q&A-P0 geometry | Developer | Normal OKS egress, 45°/90° preference, non-standard bend ×1.5 |
| 3 | Q&A-P0 crossings/tie-ins | Developer | Overlap uses max `K_special`; every new chamber ray costs one tie-in |
| 4 | Q&A-P0 scale | Developer | Routing uses bounded PostGIS windows/cursors instead of one full Java feature list |
| 5 | R9 acceptance | Developer + PM | Updated supplied-profile contract, offline package and Ubuntu 22 evidence |
| 6 | Strict reconstruction / R8 | Developer | Preserve as optional profiles pending written organizer clarifications |

The active clarification checklist is
`implementation/ORGANIZER_VIDEO_CLARIFICATIONS.md`. PM owns written organizer clarifications,
official catalog transcription review, fixture provenance,
acceptance evidence and protection of P0 scope. The developer owns implementation, boundary/golden
tests, deterministic contracts and measured evidence. See `implementation/TOMORROW_HANDOFF.md`
for the exact first slice.
