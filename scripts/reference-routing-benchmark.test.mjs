import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { curateRoutingReference } from "./curate-routing-reference.mjs";
import { analyzeCandidate, analyzeRoutingReference } from "./reference-routing-benchmark.mjs";

async function fixture(name) {
  return JSON.parse(await readFile(new URL(`../${name}`, import.meta.url), "utf8"));
}

test("professional routing reference remains a connected 17-demand tree", async () => {
  const [reference, officialInput, manifest] = await Promise.all([
    fixture("datasets/reference/professional-routing-01.geojson"),
    fixture("datasets/official/lct-2026.geojson"),
    fixture("datasets/reference/professional-routing-01.reference.json"),
  ]);

  const result = analyzeRoutingReference(reference, officialInput, manifest);

  assert.equal(result.valid, true, result.issues.join("\n"));
  assert.equal(result.metrics.demandLeafCount, 17);
  assert.equal(result.metrics.logicalSectionCount, 28);
  assert.equal(result.metrics.graphNodeCount, 29);
  assert.equal(result.metrics.maximumJunctionDegree, 4);
  assert.deepEqual(result.metrics.rootBranchFlowsTph, [96.9, 391.82]);
});

test("a broken professional route is rejected by the reference expectations", async () => {
  const [reference, officialInput, manifest] = await Promise.all([
    fixture("datasets/reference/professional-routing-01.geojson"),
    fixture("datasets/official/lct-2026.geojson"),
    fixture("datasets/reference/professional-routing-01.reference.json"),
  ]);
  reference.features = reference.features.filter((feature) =>
    feature.properties.id !== "professional-routing-01-line-26");

  const result = analyzeRoutingReference(reference, officialInput, manifest);

  assert.equal(result.valid, false);
  assert.match(result.issues.join("\n"), /route features|logical sections|components|demand leaves/);
});

test("curator strips a typed baseline and map-editor styling from a mixed source", () => {
  const source = Buffer.from(JSON.stringify({
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        geometry: { type: "Point", coordinates: [37.6, 55.7] },
        properties: { id: 106, object_type: "heat_chamber" },
      },
      {
        type: "Feature",
        geometry: { type: "LineString", coordinates: [[37.6, 55.7], [37.61, 55.71]] },
        properties: { "marker-color": "#ff0000" },
      },
      {
        type: "Feature",
        geometry: { type: "Point", coordinates: [37.61, 55.71] },
        properties: { "marker-color": "#800080" },
      },
    ],
  }));

  const curated = curateRoutingReference(source, "mixed.geojson", "test-reference");

  assert.equal(curated.features.length, 2);
  assert.equal(curated.reference.excluded_typed_feature_count, 1);
  assert.deepEqual(curated.features.map((feature) => feature.properties.reference_role), [
    "route_centerline",
    "junction_marker",
  ]);
  assert.equal(JSON.stringify(curated).includes("marker-color"), false);
});

test("candidate comparison reports official summary and advisory geometry separately", async () => {
  const reference = await fixture("datasets/reference/professional-routing-01.geojson");
  const geometry = reference.features.find((feature) =>
    feature.properties.reference_role === "route_centerline").geometry;
  const candidate = {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        geometry,
        properties: {
          id: "candidate-edge",
          object_type: "heat_network",
          variant_id: "candidate",
          start_node_id: "candidate-start",
          end_node_id: "candidate-end",
        },
      },
      {
        type: "Feature",
        geometry: null,
        properties: {
          id: "candidate-summary",
          object_type: "variant_summary",
          variant_id: "candidate",
          rank: 1,
          new_network_length: 100,
          calculated_cost: 1_000_000,
          unconnected_oks_ids: [],
        },
      },
    ],
  };

  const [result] = analyzeCandidate(candidate, reference, 17);

  assert.equal(result.connectedDemandCount, 17);
  assert.equal(result.graphCycleCount, 0);
  assert.equal(result.maximumNodeDegree, 1);
  assert.equal(typeof result.advisoryGeometrySimilarity.referenceToCandidate.meanDistanceM, "number");
});
