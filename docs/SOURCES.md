# Technical sources

Engineering requirements come only from the current organizer TZ/technical appendix and
confirmed Evgeny comments. The amended `Техническое_приложение_ЛЦТ_новое.docx` supersedes
conflicting older roadmap prose and earlier video Q&A. Current source fingerprints, the updated
expert document and the explicit exclusion of SP research are recorded in
[ACTIVE_ROUTING_RULES.md](implementation/ACTIVE_ROUTING_RULES.md).
Historical Q&A decisions and timestamps remain in `implementation/ORGANIZER_VIDEO_CLARIFICATIONS.md`;
they do not override the amended written contract.

Source precedence:

1. current organizer TZ and technical DOCX for exact tables, fields, formulas and constraints;
2. confirmed Evgeny comments for project geometry requirements; unresolved conflicts with the TZ
   require a specific clarification, not an inferred exception;
3. repository prose and roadmaps as implementation records, not independent engineering authority;
4. external software documentation, used only to implement those requirements.

By the user's decision of 2026-09-25, SP 124.13330.2012, SP 315.1325800.2017 and SP 41-105-2002
are excluded from active requirements. Do not research or import engineering constraints from
these or substitute construction standards. Earlier SP research instructions are superseded.
Keep every requirement independently stated in the TZ or confirmed by Evgeny, even when it also
appears in an SP. The old standards applicability note is historical evidence only.

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
