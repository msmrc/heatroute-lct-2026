# Technical sources

Primary product requirements are the organizer PDF and technical DOCX. External documentation is
used only to implement those requirements:

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
