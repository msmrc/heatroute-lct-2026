# ADR-005: official Java backend and safe replacement strategy

- Status: accepted
- Date: 2026-09-15
- Supersedes: backend runtime and job transport decisions in ADR-001 and ADR-003

## Context

The prototype backend was implemented with Python, FastAPI and Celery before the complete
organizer specification was audited. The official task explicitly requires Java 11, Spring Boot
2.6.3 and springdoc-openapi-ui 1.7.0. It also requires one run to process all future OKS objects,
PostgreSQL/PostGIS persistence, large streaming GeoJSON input, durable asynchronous execution and
an exact seven-type output contract. Extending the Python prototype would not make the submission
compliant.

## Decision

Create `apps/backend-java` as the only target production backend and implement the official
pipeline there. Keep the React client and PostgreSQL/PostGIS. Use:

- Java 11 and Spring Boot 2.6.3 exactly, with Maven and reproducible container builds;
- springdoc-openapi-ui 1.7.0 exactly for Swagger/OpenAPI;
- Spring Batch 4.3.x from the Spring Boot BOM with PostgreSQL metadata for restartable long jobs;
- Spring JDBC rather than ORM entities in the geometry-heavy core;
- Jackson streaming for the single GeoJSON input and output so file size is not proportional to
  JVM heap use;
- LocationTech JTS for planar geometry and PostGIS for spatial persistence and indexed queries;
- Liquibase 4.33.x for versioned application schema changes because it supports Java 11 and
  PostgreSQL 17, unlike current Flyway releases that require a newer Java runtime.

Core routing, validation, flow aggregation, diameter selection, reconstruction, cost and ranking
remain framework-independent Java packages. HTTP controllers and batch steps orchestrate these
services but do not contain engineering rules.

## Alternatives considered

1. Keep FastAPI/Celery. Rejected because it violates the explicit runtime requirement.
2. Move to current Spring Boot 4 and Java 25. Rejected because matching the organizer runtime is
   more important than adopting a newer framework for the competition artifact.
3. Write a custom queue and geometry kernel. Rejected because Spring Batch, JTS and PostGIS cover
   durable execution and geometry primitives with substantially lower implementation and
   verification risk.

## Migration and release gates

The Python service stays deployable while Java is built beside it. Production is switched only
after all of these gates pass:

1. health, readiness, errors, OpenAPI and frontend-required endpoint contracts are compatible;
2. official input schema and streaming limits pass contract tests;
3. the mandatory R2–R7 calculation pipeline passes independent geometry and arithmetic tests;
4. Java produces a strict official GeoJSON result for an end-to-end fixture;
5. clean Compose deployment, restart/recovery and browser smoke tests pass;
6. a database backup is taken and the reverse proxy is switched atomically.

Python code is deleted only after the deployed Java service passes the rollback window. No new
official-scope calculation behavior may be added to Python during the migration.

## Consequences

There is temporary duplication, but the public demo remains usable throughout the rewrite and
every migrated contract can be compared against a known reference. Redis and Celery can be
removed after cutover because PostgreSQL-backed Spring Batch becomes the durable execution source
of truth. Spring Boot 2.6.3 is old and must be isolated behind the gateway; upgrading it would
require a separate organizer-compatibility decision.
