import { createHash } from "node:crypto";
import { basename, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { readFile, writeFile } from "node:fs/promises";

function usage() {
  return [
    "Usage:",
    "  node scripts/curate-routing-reference.mjs <source.geojson> <output.geojson> <reference-id>",
    "",
    "The source may contain an old typed dataset underneath a manually drawn overlay.",
    "Only untyped Point and LineString features are copied into the reference corpus.",
  ].join("\n");
}

function fail(message) {
  throw new Error(`${message}\n\n${usage()}`);
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function assertPosition(position, subject) {
  if (!Array.isArray(position) || position.length < 2) {
    fail(`${subject} must be a GeoJSON position`);
  }
  if (!Number.isFinite(position[0]) || !Number.isFinite(position[1])) {
    fail(`${subject} contains a non-numeric coordinate`);
  }
}

function assertSupportedGeometry(geometry, subject) {
  if (!isObject(geometry)) {
    fail(`${subject} has no geometry`);
  }
  if (geometry.type === "Point") {
    assertPosition(geometry.coordinates, `${subject} Point`);
    return;
  }
  if (geometry.type === "LineString") {
    if (!Array.isArray(geometry.coordinates) || geometry.coordinates.length < 2) {
      fail(`${subject} LineString must contain at least two positions`);
    }
    geometry.coordinates.forEach((position, index) =>
      assertPosition(position, `${subject} LineString position ${index}`));
    return;
  }
  fail(`${subject} uses unsupported geometry ${geometry.type}`);
}

export function curateRoutingReference(sourceBytes, sourceName, referenceId) {
  let source;
  try {
    source = JSON.parse(sourceBytes.toString("utf8"));
  } catch (error) {
    fail(`Cannot parse ${sourceName}: ${error.message}`);
  }
  if (source?.type !== "FeatureCollection" || !Array.isArray(source.features)) {
    fail(`${sourceName} must be a GeoJSON FeatureCollection`);
  }
  if (!referenceId || !/^[a-z0-9][a-z0-9-]*$/.test(referenceId)) {
    fail("reference-id must contain lowercase Latin letters, digits and hyphens");
  }

  const candidates = source.features
    .map((feature, sourceFeatureIndex) => ({ feature, sourceFeatureIndex }))
    .filter(({ feature }) => !feature?.properties?.object_type);
  if (candidates.length === 0) {
    fail(`${sourceName} contains no untyped overlay features`);
  }

  let lineNumber = 0;
  let junctionNumber = 0;
  const features = candidates.map(({ feature, sourceFeatureIndex }) => {
    const subject = `source feature ${sourceFeatureIndex}`;
    if (feature?.type !== "Feature") {
      fail(`${subject} is not a GeoJSON Feature`);
    }
    assertSupportedGeometry(feature.geometry, subject);
    const isLine = feature.geometry.type === "LineString";
    const serial = isLine ? ++lineNumber : ++junctionNumber;
    const role = isLine ? "route_centerline" : "junction_marker";
    const suffix = String(serial).padStart(2, "0");
    return {
      type: "Feature",
      geometry: feature.geometry,
      properties: {
        id: `${referenceId}-${isLine ? "line" : "junction"}-${suffix}`,
        reference_role: role,
        source_feature_index: sourceFeatureIndex,
      },
    };
  });

  return {
    type: "FeatureCollection",
    name: referenceId,
    reference: {
      id: referenceId,
      kind: "expert_routing_geometry",
      source_filename: basename(sourceName),
      source_sha256: createHash("sha256").update(sourceBytes).digest("hex"),
      source_feature_count: source.features.length,
      excluded_typed_feature_count: source.features.length - candidates.length,
      route_centerline_count: lineNumber,
      junction_marker_count: junctionNumber,
    },
    features,
  };
}

async function main() {
  const [, , sourceArgument, outputArgument, referenceId] = process.argv;
  if (!sourceArgument || !outputArgument || !referenceId) {
    fail("Missing required arguments");
  }
  const sourcePath = resolve(sourceArgument);
  const outputPath = resolve(outputArgument);
  const sourceBytes = await readFile(sourcePath);
  const curated = curateRoutingReference(sourceBytes, sourcePath, referenceId);
  await writeFile(outputPath, `${JSON.stringify(curated, null, 2)}\n`, "utf8");
  process.stdout.write(
    `Created ${outputPath}: ${curated.reference.route_centerline_count} lines, `
      + `${curated.reference.junction_marker_count} junction markers\n`,
  );
}

const invokedDirectly = process.argv[1]
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;

if (invokedDirectly) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
