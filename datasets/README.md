# Contest and reference datasets

## Official input

`official/lct-2026.geojson` is the only tracked organizer input used by the active calculation.

- Source: corrected contest dataset supplied by the task owner.
- Size: 633,402 bytes.
- SHA-256: `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`.
- The file must not be edited to accommodate an algorithm or a test.

Known differences between the supplied file and earlier organizer prose are recorded in
`docs/implementation/SUPPLIED_DATASET_AUDIT.md`.

## Advisory references

`reference/` contains separately labelled expert and team examples used to improve route quality.
They are not organizer inputs, official expected outputs or acceptance evidence. Reference
geometry may guide topology and constructability diagnostics, but exact coordinate equality is
never a correctness requirement.

See `reference/README.md` and `docs/implementation/ROUTING_REFERENCE_CORPUS.md` before adding a
new example.
