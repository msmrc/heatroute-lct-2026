# R9 50-user API evidence

## Clean-stack measurement

- workflow: `ci`;
- run: `35112362689`;
- commit: `1aa836364b3a4685ceb0fcc18d9f57b4889c5982`;
- environment: clean GitHub `ubuntu-22.04` runner, two logical CPUs and 8,322,998,272 bytes RAM;
- stack: checksum-pinned docker-compose 1.29.2, Java 11 API, PostgreSQL/PostGIS and web;
- clients: 50 simultaneous multipart imports of the unchanged 233,277-byte organizer GeoJSON;
- assertion: every response resolved to the same durable import ID under the unique
  `(contract_version, raw_sha256)` invariant;
- completion time: 3.674 seconds from first dispatch to the last parsed response;
- upload latency: min 2,138 ms, p50 2,952 ms, p95 3,614 ms, max 3,619 ms;
- result: passed; evidence artifact `r9-load-50-1aa836364b3a4685ceb0fcc18d9f57b4889c5982`.

The integration gate then runs the real all-OKS calculation, verifies the result contract, restarts
the API container and reads the completed result back from PostgreSQL. The workflow stores the load
JSON for 30 days and repeats the same 50-client race on every candidate commit.

## Connection-pool starvation regression and fix

Run `35124933139` exposed a real timing-dependent defect: the import service kept a database
transaction open while parsing and loading the winning upload. With a 12-connection Hikari pool,
50 simultaneous requests could therefore occupy every connection while duplicate inserts waited
for the winner, and one request timed out with HTTP 500.

Commit `6b0ff88e7d596649777e1cfc1e08a13292dd01be` separates registration from feature persistence:

- the unique `(contract_version, raw_sha256)` registration is committed immediately;
- only the winning request opens the feature-load transaction;
- duplicate requests poll with short independent reads and hold no connection while waiting;
- a failed winning load is durably marked `failed` after rollback.

Clean Ubuntu 22 run `35126566499` verifies the fix with the same 50 simultaneous requests. All
responses resolve to import `0ed2cd1c-2b93-4e57-be84-21d849bd8e59`; completion time is 3.865 s,
with min/p50/p95/max latencies of 3,720/3,787/3,838/3,838 ms. The run also passes the real
all-demand calculation, schema and API contract checks, restart recovery, Java 11 backend suite and
web gates. Artifact: `r9-load-50-6b0ff88e7d596649777e1cfc1e08a13292dd01be`.

## Interpretation and limit

This is measured evidence that the public API accepts 50 concurrent users without duplicate
imports, lost responses or inconsistent durable state. It deliberately does not claim 50 heavy
route calculations executing at once: calculation execution is hard-bounded to 1–16 workers
(default 2), uses `SKIP LOCKED` claims and renews leases while work is active. Queue saturation and
maximum-topology route throughput remain distinct capacity-planning checks when a representative
organizer-scale geometry fixture is available.
