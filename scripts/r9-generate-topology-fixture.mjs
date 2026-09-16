import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

function option(name, fallback) {
  const prefix = `--${name}=`;
  const value = process.argv.find((argument) => argument.startsWith(prefix));
  return value ? value.slice(prefix.length) : fallback;
}

const inputPath = resolve(option("input", "datasets/official/lct-2026.geojson"));
const outputPath = resolve(option("output", "tmp/r9-topology-scale.geojson"));
const copies = Number.parseInt(option("copies", "2"), 10);
const longitudeStep = Number.parseFloat(option("longitude-step", "0.05"));

if (!Number.isInteger(copies) || copies < 1 || copies > 20) {
  throw new Error("copies must be an integer from 1 to 20");
}
if (!Number.isFinite(longitudeStep) || longitudeStep <= 0) {
  throw new Error("longitude-step must be positive");
}

const source = JSON.parse(await readFile(inputPath, "utf8"));
if (source.type !== "FeatureCollection" || !Array.isArray(source.features)) {
  throw new Error("input must be a GeoJSON FeatureCollection");
}

function shiftedCoordinates(value, longitudeOffset) {
  if (!Array.isArray(value)) return value;
  if (value.length >= 2 && typeof value[0] === "number" && typeof value[1] === "number") {
    return [value[0] + longitudeOffset, ...value.slice(1)];
  }
  return value.map((item) => shiftedCoordinates(item, longitudeOffset));
}

function scopedIdentifier(copyIndex, value) {
  return value == null ? value : `scale-${copyIndex}-${value}`;
}

const referenceFields = new Set([
  "oks_id",
  "upstream_object_id",
  "start_node_id",
  "end_node_id",
  "source_id",
]);

const features = [];
for (let copyIndex = 0; copyIndex < copies; copyIndex += 1) {
  const longitudeOffset = copyIndex * longitudeStep;
  for (const original of source.features) {
    const feature = structuredClone(original);
    feature.properties = { ...feature.properties };
    feature.properties.id = scopedIdentifier(copyIndex, feature.properties.id);
    for (const field of referenceFields) {
      if (Object.hasOwn(feature.properties, field)) {
        feature.properties[field] = scopedIdentifier(copyIndex, feature.properties[field]);
      }
    }
    feature.geometry = {
      ...feature.geometry,
      coordinates: shiftedCoordinates(feature.geometry.coordinates, longitudeOffset),
    };
    features.push(feature);
  }
}

const fixture = {
  ...source,
  name: `HeatRoute R9 topology fixture (${copies} isolated official districts)`,
  features,
};

await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(fixture)}\n`, "utf8");

const demandCount = features.filter(
  (feature) => feature.properties?.object_type === "oks_connection_point",
).length;
process.stdout.write(
  `${JSON.stringify({ output: outputPath, copies, features: features.length, demands: demandCount })}\n`,
);
