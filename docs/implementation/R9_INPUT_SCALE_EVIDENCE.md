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

The manual `r9-scale` GitHub workflow repeats the same measurement on clean Ubuntu 22, Temurin
Java 11 and `-Xmx512m`, and preserves the full `/usr/bin/time -v` log as a CI artifact. Its first
successful run must be recorded below before Java 11 scale evidence is called complete.
