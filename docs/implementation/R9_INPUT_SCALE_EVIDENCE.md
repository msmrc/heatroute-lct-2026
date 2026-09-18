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
- result: valid official-contest-profile input, 144 features, zero blocking errors.

Command and fixture generation are documented in `docs/operations/R9_ACCEPTANCE.md`. The generated
3 GiB file was verified by absolute path and exact size, then deleted from `E:`; it was never added
to Git.

## Interpretation

This proves that the Jackson inspection/hash pass consumes a 3 GiB upload stream without retaining
the whole file and with far less than 512 MiB heap. It intentionally does not prove multipart disk
spooling, PostGIS loading of a geometry-complex 3 GiB dataset, routing at maximum topology size or
50-user behavior. Those claims require separate probes.

The manual `r9-scale` GitHub workflow repeated the same measurement on clean Ubuntu 22, Temurin
Java 11 and `-Xmx512m`. The final combined run `35112046184` passed in 68 seconds and preserved
both input and output logs as artifact
`r9-scale-java11-832609c5d366ca8fe6e50f12ceac413642cb7c2f`:

- inspector elapsed: 4,545 ms;
- reported peak heap: 44,502,016 bytes;
- maximum process RSS from `/usr/bin/time -v`: 357,664 KiB;
- total Maven step wall time: 26.20 seconds;
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

The same clean Ubuntu 22 / Temurin Java 11 run `35112046184` repeated the output probe:

- generated output: 524,781,467 bytes;
- writer/validator elapsed: 6,942 ms;
- reported peak heap: 104,260,560 bytes;
- maximum process RSS from `/usr/bin/time -v`: 267,096 KiB;
- total Maven step wall time: 13.22 seconds;
- result: Maven build and strict contract validation passed.

This closes the production writer/validator byte boundary on the required runtime. It does not
claim that the supplied 144-feature calculation naturally produces a 500 MiB result, and it does
not replace a geometry-representative maximum-topology dataset.
