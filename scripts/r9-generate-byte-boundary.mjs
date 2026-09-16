import { createWriteStream } from "node:fs";
import { mkdir, readFile, stat } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { once } from "node:events";

function option(name, fallback) {
  const prefix = `--${name}=`;
  const match = process.argv.slice(2).find((value) => value.startsWith(prefix));
  return match ? match.slice(prefix.length) : fallback;
}

const sourcePath = resolve(option("source", "datasets/official/lct-2026.geojson"));
const outputPath = resolve(option("output", "tmp/r9/official-3gib.geojson"));
const targetBytes = Number(option("bytes", String(3 * 1024 * 1024 * 1024)));
if (!Number.isSafeInteger(targetBytes) || targetBytes < 1_000_000) {
  throw new Error("--bytes must be a safe integer of at least 1,000,000");
}

const source = JSON.parse(await readFile(sourcePath, "utf8"));
if (source.type !== "FeatureCollection" || !Array.isArray(source.features)) {
  throw new Error(`${sourcePath} is not a GeoJSON FeatureCollection`);
}
const prefix = Buffer.from('{"type":"FeatureCollection","scale_probe_padding":"');
const suffix = Buffer.from(`","features":${JSON.stringify(source.features)}}`);
const paddingBytes = targetBytes - prefix.length - suffix.length;
if (paddingBytes < 0) {
  throw new Error(`Target is smaller than the source payload (${prefix.length + suffix.length} bytes)`);
}

await mkdir(dirname(outputPath), { recursive: true });
const output = createWriteStream(outputPath, { flags: "wx" });
output.write(prefix);
const chunk = Buffer.alloc(Math.min(1024 * 1024, paddingBytes), 0x61);
let remaining = paddingBytes;
while (remaining > 0) {
  const next = remaining >= chunk.length ? chunk : chunk.subarray(0, remaining);
  if (!output.write(next)) {
    await once(output, "drain");
  }
  remaining -= next.length;
}
output.end(suffix);
await once(output, "close");

const generated = await stat(outputPath);
if (generated.size !== targetBytes) {
  throw new Error(`Expected ${targetBytes} bytes, wrote ${generated.size}`);
}
console.log(JSON.stringify({
  output: outputPath,
  filename: basename(outputPath),
  bytes: generated.size,
  source_features: source.features.length,
  purpose: "R9 byte-boundary streaming probe; not a geometry-complexity fixture",
}, null, 2));
