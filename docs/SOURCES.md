# Technical sources

Primary product requirements are the organizer PDF and the amended technical DOCX
`Техническое_приложение_ЛЦТ_новое.docx`. The amended written contract supersedes conflicting older
roadmap prose and earlier video Q&A. Source fingerprint, standards applicability and the explicit
separation of contest rules from expert heuristics are recorded in
[ROUTING_STANDARDS_APPLICABILITY.md](implementation/ROUTING_STANDARDS_APPLICABILITY.md).
Historical Q&A decisions and timestamps remain in `implementation/ORGANIZER_VIDEO_CLARIFICATIONS.md`.

Source precedence:

1. organizer PDF and technical DOCX for exact tables, fields and formulas;
2. later organizer Q&A for current supplied-dataset scope and implementation priority;
3. repository prose and roadmaps;
4. external documentation, used only to implement the requirements.

External implementation sources:

- Spring Boot 2.6.3 reference documentation;
- springdoc-openapi 1.7.0 documentation;
- Spring Batch 4.3 reference documentation;
- PostgreSQL 17 and PostGIS 3.5 manuals;
- Liquibase 4.33 documentation;
- LocationTech JTS and Proj4J documentation;
- MapLibre GL JS 6 documentation, v5-to-v6 migration guide and BSD-3-Clause license;
- GitHub Security Advisory GHSA-jrc7-96c5-q579 for the MapLibre GL JS expression-injection fix;
- CARTO Positron vector basemap documentation and OpenStreetMap attribution requirements;
- GeoJSON RFC 7946;
- Docker Compose file format 3.8 / docker-compose 1.29.2 documentation.

Dependency choices and versions are pinned in `apps/api/pom.xml`, `apps/web/package.json` and
Dockerfiles. Any library
change affecting Java 11 or the official deployment target requires an ADR and a clean build test.
