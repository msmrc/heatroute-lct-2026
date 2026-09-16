# HeatRoute technical specification

**Baseline:** 2026-09-15. This document summarizes the official implementation contract; the
organizer PDF/DOCX remains authoritative.

## Goal

For every `oks_future` in one official GeoJSON, automatically produce up to three materially
different valid heating-network variants. A variant may share trunks, use one or more tie-ins,
must respect restrictions, size pipes from aggregated flow, calculate reconstruction toward the
source, calculate official cost and score, and preserve partial results for disconnected OKS.

## Required platform

| Layer | Decision |
|---|---|
| Backend | Java 11, Spring Boot 2.6.3 |
| API docs | springdoc-openapi-ui 1.7.0 |
| Geometry | JTS + PostGIS; Proj4J for EPSG:4326 → EPSG:32637 |
| Persistence | PostgreSQL 17 / PostGIS, JDBC, Liquibase |
| Long work | PostgreSQL-backed durable jobs with atomic claim, lease, retry and cancellation |
| Web | React, TypeScript, Vite |
| Delivery | Docker Compose compatible with 1.29.2; target acceptance OS Ubuntu Server 22 |

`apps/api` is the only backend module. API and worker logic may share one JVM during development,
but heavy calculations must remain restart-safe and detachable into a dedicated worker process.

## Pipeline

1. Stream a single WGS84 FeatureCollection and validate seven input types and typed references.
2. Persist source bytes metadata, report, WGS84 geometry and projected EPSG:32637 geometry.
3. Validate existing-network upstream topology to a source and create feasible tie-in candidates.
4. Route all future OKS jointly; compare shared and separate connections.
5. Normalize routes into trees, place chambers, reject cycles/crossings outside common nodes.
6. Aggregate `flow_tph` bottom-up, select minimum official DU and enforce continuous-length limits.
7. Propagate incremental flow upstream and calculate segment/chamber reconstruction.
8. Apply exact forbidden buffers and permitted special-crossing geometry.
9. Calculate official component costs, penalties and score; rank at most three diverse variants.
10. Independently validate and stream the strict official output GeoJSON.

## Non-functional requirements

- input up to 3 GB and output up to 500 MB without whole-file heap materialization;
- operation on a 16 GB machine and up to 50 concurrent users;
- deterministic results for equal input, algorithm version and catalogs;
- structured stable error codes with request IDs; no stack traces or filesystem paths in API;
- durable job progress, cooperative cancellation and recovery after process restart;
- no manual route editing required before the demonstration;
- public deployment exposes only 80/443; database and API remain loopback/internal.

## Core invariants

- each new non-root network edge has exactly one upstream path;
- branches occur only in chambers; a chamber has at most four incident sections;
- new sections do not cross each other except at a shared node;
- upstream flow equals the sum of downstream demand;
- DU is the minimum catalog entry covering flow and continuous length;
- a chamber does not reset the same-DU continuous-length counter;
- reconstruction is emitted only where required DU exceeds existing DU;
- no-route for one OKS does not discard valid routes for the others;
- every exported feature contains only fields allowed for its output type.

Implementation sequence and current truth are in `implementation/OFFICIAL_TZ_ROADMAP.md`.
