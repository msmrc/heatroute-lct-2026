# R9 topology-scale evidence

Date: 2026-09-16

This checkpoint exercises the complete calculation, not a padded byte stream. The reproducible
fixture generator makes isolated translated copies of all 144 supplied features and scopes every
identifier. Geometry, object mix and local obstacle density therefore stay equal to the organizer
dataset while demand count, candidate count and total topology grow linearly.

## Local Java measurement

- fixture: 2 isolated copies, 288 features, 34 demand points and 408 tie-in candidates;
- runtime: Java 21 locally, production-compatible Java 11 source target;
- heap cap: 2 GiB;
- complete calculation: topology plus three route strategies, constraints, sizing, depth profiles,
  validation and economics;
- elapsed calculation time: 116,141 ms;
- heap used after calculation: 249,833,520 bytes;
- result: 34 of 34 demands connected in the preferred variant, all three variants valid;
- test result: pass.

Run it locally from the repository root:

```powershell
node scripts/r9-generate-topology-fixture.mjs --copies=2 --output=tmp/r9-topology-scale.geojson
$env:MAVEN_OPTS='-Xmx2048m'
mvn -f apps/api/pom.xml `
  -Dtest=OfficialTopologyScaleTest `
  -Dheatroute.scale.topology=tmp/r9-topology-scale.geojson `
  -Dheatroute.scale.demands=34 `
  -Dheatroute.scale.maximumSeconds=600 test
```

The manual `r9-topology-scale` workflow repeats the same case on clean Ubuntu 22 and Temurin 11,
captures `/usr/bin/time -v`, and uploads only the small measurement logs.

## Qualification

This is a project-owned two-times load profile derived deterministically from the supplied geometry.
It proves multi-district scaling beyond the supplied 17-demand case without changing application
code or hand-editing routes. It is not represented as the organizer's unknown maximum topology.
Final replacement of the fixture label “representative” still requires the organizer or PM to
approve a maximum object/demand profile or provide that dataset.
