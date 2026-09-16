# ADR-003: PostgreSQL durable jobs

- Status: amended and active
- Date: 2026-09-15

PostgreSQL is the source of truth for job state. A job row stores immutable type/import input,
state, phase, progress, attempt, lease, cancellation request, result and failure. Workers atomically
claim queued or expired jobs, renew leases, make durable effects idempotent and cooperate with
cancellation. Broker delivery is intentionally absent from the current production path.

The development API process runs the scheduler/worker, but the domain service and lease protocol
must remain safe when moved into a separate JVM service before production load testing.
