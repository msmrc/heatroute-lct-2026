# ADR-005: Java-only official backend

- Status: accepted and cut over
- Date: 2026-09-15
- Supersedes: backend runtime and transport decisions in ADR-001 and ADR-003

## Context

The official task explicitly requires Java 11, Spring Boot 2.6.3 and springdoc-openapi-ui 1.7.0.
It also requires large streaming GeoJSON, PostGIS, durable work and a strict official result.

## Decision

`apps/api` is the only backend and uses:

- Java 11 and Spring Boot 2.6.3;
- springdoc-openapi-ui 1.7.0;
- Spring JDBC and PostgreSQL/PostGIS;
- Liquibase migrations;
- Jackson streaming for large GeoJSON;
- JTS and Proj4J for geometry/CRS;
- PostgreSQL job rows with atomic claim, leases, retries and cancellation.

Core routing, validation, sizing, reconstruction, cost and ranking remain framework-independent.
HTTP controllers and job workers orchestrate them but do not duplicate engineering rules.

## Cutover record

On 2026-09-15 the default, offline and VPS Compose profiles, CI, developer scripts, OpenAPI and web
client were switched to Java. The former backend source, dependency files, migrations and tests
were removed from the working tree. Git history is the rollback source; there is no parallel
runtime or compatibility promise for legacy endpoints.

The cutover does not imply R4–R9 feature parity. The UI intentionally exposes only implemented
official Java operations until equivalent contracts exist.

## Consequences

There is one backend path and no migration ambiguity. Spring Boot 2.6.3 is old and must stay behind
the gateway with pinned dependencies. Any restoration of another backend language or broker needs
a new ADR and must not weaken official compliance.
