import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  buildComparison, candidateModel, commonViewport, polylinePath, verifyReferenceProvenance,
} from "./render-routing-comparison.mjs";

const rootPoint = { xm: 414300, ym: 6173400 };

function runCli(args) {
  const child = spawnSync(process.execPath, [fileURLToPath(new URL("./render-routing-comparison.mjs", import.meta.url)), ...args],
    { encoding: "utf8", timeout: 5000 });
  assert.ifError(child.error);
  return child;
}

test("CLI requires explicit --result even when an output directory is supplied", () => {
  for (const args of [[], ["--out-dir", ".tooling/routing-comparison-48"]]) {
    const child = runCli(args);
    assert.equal(child.status, 1);
    assert.equal(child.stdout, "");
    assert.match(child.stderr, /^Missing required option: --result <bundle.json>/);
  }
});

test("CLI rejects --result without a nonblank value", () => {
  for (const args of [["--result"], ["--result", ""], ["--result", "   "]]) {
    const child = runCli(args);
    assert.equal(child.status, 1);
    assert.equal(child.stdout, "");
    assert.match(child.stderr, /(?:missing value|Missing required option).*--result/);
  }
});

test("CLI help works without --result and documents it as required", () => {
  const child = runCli(["--help"]);
  assert.equal(child.status, 0);
  assert.equal(child.stderr, "");
  assert.match(child.stdout, /^node scripts\/render-routing-comparison\.mjs --result bundle.json /);
  assert(!child.stdout.includes("[--result"));
});

function bundle() {
  return { run: { algorithm_version: "test-version", parameters: { depth_enabled: true }, result: {
    algorithm_version: "test-version", demand_count: 2, variants: [{
      id: "balanced", strategy: "engineering", total_length_m: 230, connected_demand_count: 2,
      valid: true, validation_issues: [], engineering_issues: [], sizing_issues: [],
      nodes: [
        { id: "root", node_type: "existing_chamber_tie_in", root: true, chamber: true, coordinate: rootPoint, target_id: "R" },
        { id: "branch", node_type: "new_branch_chamber", root: false, chamber: true, coordinate: { xm: 414350, ym: 6173400 } },
        { id: "d1", node_type: "demand_connection", root: false, chamber: false, coordinate: { xm: 414400, ym: 6173400 } },
        { id: "d2", node_type: "demand_connection", root: false, chamber: false, coordinate: { xm: 414300, ym: 6173530 } },
      ],
      edges: [
        { id: "r-b", upstream_node_id: "root", downstream_node_id: "branch", coordinates: [rootPoint, { xm: 414350, ym: 6173400 }] },
        { id: "b-d1", upstream_node_id: "branch", downstream_node_id: "d1", coordinates: [{ xm: 414350, ym: 6173400 }, { xm: 414400, ym: 6173400 }] },
        { id: "r-d2", upstream_node_id: "root", downstream_node_id: "d2", coordinates: [rootPoint, { xm: 414300, ym: 6173500 }, { xm: 414300, ym: 6173530 }] },
      ],
    }],
  } } };
}

test("cameras, root sites and root rays are separate metrics; demand points are not cameras", () => {
  const model = candidateModel(bundle());
  assert.equal(model.metrics.chamber_count, 2);
  assert.equal(model.metrics.branch_chambers, 1);
  assert.equal(model.metrics.existing_root_chambers, 1);
  assert.equal(model.metrics.root_sites, 1);
  assert.equal(model.metrics.root_rays, 2);
  assert.equal(model.metrics.chambers_by_type.demand_connection, undefined);
  assert.equal(model.metrics.geometry_length_m, 230);
  assert.equal(model.markers.filter((marker) => marker.kind === "root").length, 1);
  assert.equal(model.markers.filter((marker) => marker.kind === "demand").length, 2);
});

test("development probes are labeled separately from the published algorithm result", () => {
  const input = bundle();
  assert.equal(candidateModel(input).title, "HeatRoute · наш маршрут");
  assert.equal(candidateModel(input).metrics.development_candidate, false);
  input.run.result.algorithm_version = "corridor-development";
  assert.equal(candidateModel(input).title, "HeatRoute · кандидат в разработке");
  assert.equal(candidateModel(input).metrics.development_candidate, true);
});

test("economics and selected strategy are copied unchanged; best follows source preference, balanced stays control", () => {
  const input = bundle();
  const result = input.run.result;
  const balanced = result.variants[0];
  balanced.rank = 3;
  balanced.economics = { complete: true, calculated_cost: 269806210.7, score: 13.2517269, incomplete_reasons: [] };
  result.preferred_variant_id = "cheapest";
  result.variants.push({ ...structuredClone(balanced), id: "cheapest", strategy: "cost-focused",
    rank: 1, economics: { complete: true, calculated_cost: 266087216.11, score: 13.164617051, incomplete_reasons: [] } });
  const before = JSON.stringify(input);
  const control = candidateModel(input).metrics;
  assert.equal(control.variant_id, "balanced");
  assert.equal(control.strategy, "engineering");
  assert.equal(control.calculated_cost, balanced.economics.calculated_cost);
  assert.equal(control.score, balanced.economics.score);
  assert.equal(control.rank, 3);
  assert.equal(control.preferred_variant_id, "cheapest");
  const best = candidateModel(input, "best").metrics;
  assert.equal(best.variant_id, "cheapest");
  assert.equal(best.strategy, "cost-focused");
  assert.equal(best.calculated_cost, result.variants[1].economics.calculated_cost);
  assert.equal(best.score, result.variants[1].economics.score);
  assert.deepEqual(best.selection, { requested: "best", resolved_by: "result.preferred_variant_id" });
  assert.equal(JSON.stringify(input), before);
});

test("unknown economics stays null, zero stays zero, and best never silently falls back", () => {
  const input = bundle();
  assert.equal(candidateModel(input).metrics.calculated_cost, null);
  assert.equal(candidateModel(input).metrics.score, null);
  assert.equal(candidateModel(input).metrics.economics_complete, null);
  input.run.result.variants[0].economics = { complete: false, calculated_cost: 0, score: 0,
    incomplete_reasons: ["fixture"] };
  const metrics = candidateModel(input).metrics;
  assert.equal(metrics.calculated_cost, 0);
  assert.equal(metrics.score, 0);
  assert.equal(metrics.economics_complete, false);
  assert.deepEqual(metrics.economics_incomplete_reasons, ["fixture"]);
  assert.throws(() => candidateModel(input, "best"), /no preferred_variant_id/);
  input.run.result.preferred_variant_id = "missing";
  assert.throws(() => candidateModel(input, "best"), /Missing result variant: missing/);
});

test("auxiliary non-chamber nodes are not mislabeled as demand connection points", () => {
  const input = bundle();
  input.run.result.variants[0].nodes.push({ id: "bend", node_type: "routing_anchor", root: false,
    chamber: false, coordinate: { xm: 414300, ym: 6173500 } });
  const model = candidateModel(input);
  assert.equal(model.markers.length, 4);
  assert.equal(model.metrics.chamber_count, 2);
});

test("co-located root nodes do not inflate root site count", () => {
  const input = bundle();
  const variant = input.run.result.variants[0];
  variant.nodes.push({ ...variant.nodes[0], id: "root-alias" });
  variant.edges[2].upstream_node_id = "root-alias";
  const metrics = candidateModel(input).metrics;
  assert.equal(metrics.root_node_count, 2);
  assert.equal(metrics.root_sites, 1);
  assert.equal(metrics.root_rays, 2);
});

test("viewport is isotropic, grid north up, and includes every vertex of both routes", () => {
  const first = candidateModel(bundle());
  const second = structuredClone(first);
  second.lines[0].coordinates.push([414600, 6173600]);
  const viewport = commonViewport([first, second]);
  const [sx, skewY, skewX, sy, dx, dy] = viewport.matrix;
  assert.equal(sx, -sy);
  assert.equal(skewY, 0);
  assert.equal(skewX, 0);
  assert(sx > 0);
  for (const model of [first, second]) {
    for (const [x, y] of model.lines.flatMap((line) => line.coordinates)) {
      const screenX = sx * x + dx;
      const screenY = sy * y + dy;
      assert(screenX > viewport.box.x && screenX < viewport.box.x + viewport.box.width);
      assert(screenY > viewport.box.y && screenY < viewport.box.y + viewport.box.height);
    }
  }
});

test("path retains all source vertices and intermediate collinear points without curves", () => {
  const coordinates = [[414300.123456789, 6173400.1], [414300.123456789, 6173500], [414300.123456789, 6173530]];
  assert.equal(polylinePath(coordinates), "M414300.123456789 6173400.1 L414300.123456789 6173500 L414300.123456789 6173530");
  const input = bundle();
  const before = JSON.stringify(input);
  candidateModel(input);
  assert.equal(JSON.stringify(input), before);
});

test("malformed result, missing variant and broken endpoint references fail explicitly", () => {
  assert.throws(() => candidateModel(bundle(), "unknown"), /Missing result variant/);
  const invalid = bundle();
  invalid.run.result.variants[0].edges[0].coordinates[0] = { x_m: 1, y_m: 2 };
  assert.throws(() => candidateModel(invalid), /finite xm\/ym/);
  const badReference = bundle();
  badReference.run.result.variants[0].edges[0].downstream_node_id = "missing";
  assert.throws(() => candidateModel(badReference), /Unknown endpoint/);
});

test("provenance validates original bytes AND extracted geometry, not just a source filename", () => {
  const geometry = { type: "LineString", coordinates: [[37.6, 55.7], [37.61, 55.71]] };
  const bytes = Buffer.from(JSON.stringify({ type: "FeatureCollection", features: [{ geometry }] }));
  const reference = { reference: { id: "fixture", kind: "expert_routing_geometry", source_filename: "1.geojson",
    source_sha256: createHash("sha256").update(bytes).digest("hex"), source_feature_count: 1 },
  features: [{ geometry, properties: { id: "route", source_feature_index: 0 } }] };
  const original = { path: "/read-only/1.geojson", bytes };
  assert.equal(verifyReferenceProvenance(reference, { id: "fixture" }, original).verification, "source_hash_and_all_geometries");
  assert.throws(() => verifyReferenceProvenance(reference, { id: "fixture" }, { ...original, bytes: Buffer.from("{}") }), /SHA-256 mismatch/);
  const altered = structuredClone(reference);
  altered.features[0].geometry.coordinates[0][0] += 0.01;
  assert.throws(() => verifyReferenceProvenance(altered, { id: "fixture" }, original), /geometry differs/);
});

const readJson = async (path) => JSON.parse(await readFile(new URL(path, import.meta.url), "utf8"));

test("repository reference is 1.geojson; both standalone SVGs and side-by-side have exactly one common transform", async () => {
  const [official, reference, manifest] = await Promise.all([
    readJson("../datasets/official/lct-2026.geojson"),
    readJson("../datasets/reference/professional-routing-01.geojson"),
    readJson("../datasets/reference/professional-routing-01.reference.json"),
  ]);
  assert.equal(reference.reference.source_filename, "1.geojson");
  const input = bundle();
  input.run.result.algorithm_version = '<unsafe & "version">';
  const rendered = buildComparison(input, official, reference, manifest, verifyReferenceProvenance(reference, manifest));
  assert.equal(rendered.metadata.reference.reported_length_m, 1913.859);
  assert.equal(rendered.metadata.reference.branch_chambers, 11);
  assert.equal(rendered.metadata.reference.root_sites, 1);
  assert.equal(rendered.metadata.reference.root_rays, 2);
  assert.equal(rendered.metadata.basemap_layer_counts.oks, 85);
  assert.equal(rendered.metadata.basemap_layer_counts.road, undefined);
  const transforms = Object.values(rendered.svgs).flatMap((svg) => [...svg.matchAll(/transform="matrix\(([^)]+)\)"/g)].map((match) => match[1]));
  assert.equal(transforms.length, 4);
  assert.equal(new Set(transforms).size, 1);
  assert.match(rendered.svgs["heatroute-balanced"], /&lt;unsafe &amp; &quot;version&quot;&gt;/);
  assert(!rendered.svgs["heatroute-balanced"].includes('<unsafe & "version">'));
  assert.match(rendered.svgs["evgeny-reference"], /Дорожного слоя нет/);
  assert.equal([...rendered.svgs["heatroute-balanced"].matchAll(/data-route-id=/g)].length, 3);
  assert.equal([...rendered.svgs["evgeny-reference"].matchAll(/data-route-id=/g)].length, 26);
  for (const svg of Object.values(rendered.svgs)) {
    for (const [, route] of svg.matchAll(/data-route-id="[^"]*" d="([^"]+)"/g)) assert(!/[CQSTA]/.test(route));
  }
  input.run.result.preferred_variant_id = "cheapest";
  input.run.result.variants.push({ ...structuredClone(input.run.result.variants[0]), id: "cheapest", strategy: "cheapest" });
  const best = buildComparison(input, official, reference, manifest, verifyReferenceProvenance(reference, manifest), {}, "best");
  assert(best.svgs["heatroute-cheapest"]);
  assert.equal(best.svgs["heatroute-balanced"], undefined);
  assert.equal(best.metadata.candidate.variant_id, "cheapest");
  assert.match(best.svgs["heatroute-cheapest"], /cheapest \/ cheapest/);
});
