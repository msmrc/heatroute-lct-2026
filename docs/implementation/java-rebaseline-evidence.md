# Java official-TZ rebaseline evidence

Updated: 2026-09-15

This file records only verified results for the replacement backend required by the official task.
The Python implementation remains the production fallback until the cutover gates in ADR-005 pass.

## Verified foundation

- Runtime: Java 11, Spring Boot 2.6.3, Spring Framework 5.3.15.
- API documentation: springdoc-openapi-ui 1.7.0 at `/swagger-ui.html`; OpenAPI at `/v3/api-docs`.
- Database: PostgreSQL/PostGIS with Liquibase migrations and separate WGS 84 / metric geometry
  columns (`EPSG:4326` and `EPSG:32637`) protected by GiST indexes.
- Runtime container uses pinned Maven and Eclipse Temurin image digests and UID 10001.
- `compose.java.yaml` intentionally keeps Compose schema 3.8 so it can be parsed by the required
  docker-compose 1.29.2. A fresh named stack reached readiness and was removed with its test volumes.
- The Java Docker build ran 18 tests with zero failures.

## Verified official input slice

- The minimal fixture contains eight features and all seven official input object types.
- `POST /api/v1/official/imports/inspect` streamed and validated it without loading the complete
  FeatureCollection tree.
- `POST /api/v1/official/imports` persisted eight features; SQL verification returned
  `8 | 4326 | 32637` for row count and both SRIDs.
- `GET /api/v1/official/imports/{id}` reproduced the stored hash, counts and validation report.
- The semantic-negative fixture reported duplicate ID, unknown typed reference and a future OKS
  without a connection point. Its durable import state was `invalid` and it persisted zero features.
- Malformed JSON returned HTTP 422 through the documented snake_case error envelope without a
  stack trace.

## Verified official engineering constants

- All 18 nominal-diameter rows are encoded with maximum flow, continuous length, new-build rate,
  reconstruction rate and pipe-envelope dimensions.
- Boundary selection chooses the smallest diameter whose capacity is sufficient and returns no
  diameter above 22,501.9 t/h.
- Unit tests cover the official special-crossing multipliers, depth multiplier, unconnected-OKS
  penalty and score formula.

## Verified existing-network slice

- Upstream traversal requires every heat-network segment and chamber chain to terminate at a
  source; cycles and broken chains produce structured topology diagnostics.
- Pairwise indexed geometry checks reject interior XY crossings while allowing one shared endpoint.
- Candidate generation is deterministic and bounded to 12 candidates per connection point.
- A chamber within 10 m of the projected tie-in is reused only when adding the new segment keeps
  the chamber at no more than four incident segments; otherwise a new chamber is required.
- An internal line tie-in is snapped and split into two LineStrings without losing or duplicating
  metric length.
- A live PostGIS fixture produced one new-chamber line candidate and one existing-chamber candidate
  for its two future OKS connection points.

## Verified durable jobs

- Creating a topology job returns `queued`; the database worker atomically claims it and persists
  its result and terminal `completed` state.
- Jobs expose phase, progress, attempt count, timestamps, sanitized errors and a cooperative
  cancellation flag through the API.
- A simulated interrupted `running` job with an expired lease was reclaimed after service restart,
  completed successfully and incremented its attempt from 1 to 2.
- Cancelling a queued job persisted `cancelled` before any expensive topology work started.

## Still open

- Separate production worker process and calculation-job steps.
- Spatially indexed large-network candidate search and persisted selected tie-ins.
- Multi-OKS routing, flow aggregation, reconstruction, exact restrictions and output generation.
- Large-input memory/load evidence and clean Ubuntu Server 22 acceptance deployment.
- Frontend cutover to the Java compatibility API.

No HeatRoute test containers or volumes were left running after verification. The VPS was not
modified by this rebaseline.
