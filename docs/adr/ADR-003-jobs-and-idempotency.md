# ADR-003: jobs and idempotency

- Status: accepted
- Date: 2026-09-07

## Decision

PostgreSQL stores durable job state, run outcomes, events and an outbox. Celery with Redis
provides at-least-once delivery. A task message contains an immutable job ID, not the full
scenario. Workers acquire a database lease and every durable effect is idempotent.

Stale `If-Match` revisions return HTTP 412. Reusing an `Idempotency-Key` with a different
canonical request body returns HTTP 409. The initial idempotency retention is seven days and
is configurable; completed run records remain immutable beyond key expiry.

## Consequences

`acks_late` is defense in depth, not exactly-once delivery. Broker failure after the request
transaction leaves a pending outbox row. Cooperative cancellation and lease recovery are
implemented before long routing jobs are enabled.

