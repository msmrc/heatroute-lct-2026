# R9 acceptance measurements

These probes create evidence; their existence is not evidence that the acceptance gate passed.
Run them only against a clean candidate release and keep the generated JSON, `/usr/bin/time -v`
output, container stats and exact commit SHA together.

For the repeatable Java 11 parser boundary, dispatch the manual GitHub Actions workflow
`r9-scale`; it generates the same 3 GiB file and ≥500 MiB contract output on Ubuntu 22, then
preserves both measurement logs without uploading the large input fixture or output bytes.

## 3 GiB streaming boundary

Generate the byte-boundary fixture on a volume with at least 4 GiB free. The generated file keeps
the 144 organizer features and uses a legal GeoJSON foreign member as padding, so this proves the
multipart/parser/hash/staging byte boundary but not large-topology route quality.

```bash
node scripts/r9-generate-byte-boundary.mjs \
  --source=datasets/official/lct-2026.geojson \
  --output=/mnt/heatroute-r9/official-3gib.geojson \
  --bytes=3221225472

/usr/bin/time -v curl --fail-with-body \
  --form 'file=@/mnt/heatroute-r9/official-3gib.geojson;type=application/geo+json' \
  http://127.0.0.1:8000/api/v1/official/imports
```

Record API/container peak RSS and free space before and after. Delete the generated probe after
evidence has been copied; it is intentionally ignored by Git.

## 50 concurrent users

The first mode sends 50 identical concurrent imports and requires all responses to resolve to one
contract+SHA import. The second additionally queues and waits for 50 complete immutable runs; it
can take hours with the safe default of two calculation workers.

```bash
node scripts/r9-concurrency.mjs \
  --base-url=http://127.0.0.1:8000 \
  --dataset=datasets/official/lct-2026.geojson \
  --users=50 \
  --queue-runs=false

node scripts/r9-concurrency.mjs \
  --base-url=http://127.0.0.1:8000 \
  --dataset=/mnt/heatroute-r9/contract-complete.geojson \
  --users=50 \
  --queue-runs=true \
  --timeout-ms=10800000
```

Set `HEATROUTE_JOB_CONCURRENCY` only after measuring CPU and peak heap. The application clamps it
to 1–16 and defaults to 2. Every active job renews its PostgreSQL lease once per minute, so a
calculation longer than five minutes cannot be reclaimed while its worker is alive.

## Still required for a pass

- a contract-complete, geometry-representative scale fixture from the organizer;
- a strict output reaching the 500 MB boundary;
- peak RSS below the agreed 16 GB machine limit;
- all 50 queued runs completed without duplicate execution or lost state;
- clean Ubuntu Server 22 and docker-compose 1.29.2 deployment/restart evidence.
