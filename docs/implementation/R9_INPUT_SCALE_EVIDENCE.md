# R9 input streaming evidence

## Local preflight

- code commit: `915d42f1d9c2e8d587cd4fd382f8d25617eb618d`;
- generated size: exactly 3,221,225,472 bytes (3 GiB);
- source payload: the sole 144-feature organizer GeoJSON plus a legal root foreign-member padding;
- JVM: local Java 21 test runtime, production code compiled with `release 11`;
- test heap limit: 536,870,912 bytes (`-Xmx512m`);
- elapsed inspector time: 4,308 ms;
- reported peak heap: 42,005,872 bytes;
- streamed SHA-256: `7070908d6f52b3d45fe9455a4cdcfaacd4567aab6b33d0b6156e01feaf0eb9cb`;
- result: valid compatibility-profile input, 144 features, zero blocking errors.

Command and fixture generation are documented in `docs/operations/R9_ACCEPTANCE.md`. The generated
3 GiB file was verified by absolute path and exact size, then deleted from `E:`; it was never added
to Git.

## Interpretation

This proves that the Jackson inspection/hash pass consumes a 3 GiB upload stream without retaining
the whole file and with far less than 512 MiB heap. It intentionally does not prove multipart disk
spooling, PostGIS loading of a geometry-complex 3 GiB dataset, routing at maximum topology size or
50-user behavior. Those claims require separate probes.

The manual `r9-scale` GitHub workflow repeated the same measurement on clean Ubuntu 22, Temurin
Java 11 and `-Xmx512m`. Run `35111560434` passed in 60 seconds and preserved the logs as artifact
`r9-input-3gib-java11-b50cce654541f72996f0d0498e779684cd31caac`:

- inspector elapsed: 4,497 ms;
- reported peak heap: 40,650,752 bytes;
- maximum process RSS from `/usr/bin/time -v`: 357,272 KiB;
- total Maven step wall time: 22.11 seconds;
- streamed SHA-256 matches the local run exactly.

This closes the 3 GiB parser/hash byte boundary on the required Java 11/Ubuntu 22 runtime. Full
multipart spooling, PostGIS geometry complexity and topology scale remain separate evidence items.

## 500 MiB output writer preflight

The production FeatureCollection stream writer and exact output validator were exercised locally
with valid WGS84 technical nodes, network features, references and one variant summary:

- generated output: 524,781,467 bytes (at least 500 MiB);
- heap limit: 536,870,912 bytes (`-Xmx512m`);
- elapsed writer/validator time: 6,386 ms;
- reported peak heap: 344,267,392 bytes;
- no output collection or file was retained; a counting sink received the streamed bytes;
- contract validation completed with zero issues.

This proves the production writer/validator layer at the required byte boundary, not that a single
real calculation will naturally produce a 500 MiB result. The manual `r9-scale` workflow now repeats
this probe on Temurin Java 11 and must pass before the output byte boundary is marked complete.
