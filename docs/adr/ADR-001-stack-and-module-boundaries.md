# ADR-001: stack and module boundaries

- Status: superseded by ADR-005 for the backend runtime; frontend and modular boundaries retained
- Date: 2026-09-07

## Decision

Use a modular Python monolith with separate FastAPI and Celery processes, PostgreSQL/PostGIS
for durable and spatial state, Redis as transport/cache, and a React/Vite web client. Domain
code does not import FastAPI, Celery or SQLAlchemy. HTTP handlers and worker tasks call
application services through explicit transaction boundaries.

The official FastAPI full-stack template was evaluated as a bootstrap source. It provides
Compose, PostgreSQL, React/Vite, generated clients and Playwright, but its SQLModel, JWT and
Traefik choices do not satisfy this specification and it has no PostGIS/Celery/outbox core.
Reusing it wholesale would create an immediate migration. We instead reuse the proven
libraries and patterns while keeping the required module boundaries.

## Consequences

The API and worker share one backend image and codebase. Heavy import/routing/export work
never runs inside HTTP request handlers. Splitting a module into a service remains possible
without changing the public API.
