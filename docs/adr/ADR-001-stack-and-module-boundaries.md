# ADR-001: stack and module boundaries

- Status: superseded by ADR-005 for backend runtime; modular boundaries retained
- Date: 2026-09-07

The original backend decision is historical. The active stack is Java/Spring Boot as defined by
ADR-005. Domain algorithms remain independent of Spring MVC and SQL; controllers orchestrate,
repositories own persistence, and long work uses durable job state.
