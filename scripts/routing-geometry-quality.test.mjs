import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { dominantBuildingAxes, routeAxisAlignment, symmetricProximity, topologyAngles } from "./routing-geometry-quality.mjs";

const rectangle = [[0, 0], [30, 0], [30, 20], [0, 20], [0, 0]];
const near = (actual, expected, tolerance = 1e-6) => assert(Math.abs(actual - expected) <= tolerance,
  `${actual} differs from ${expected} by more than ${tolerance}`);
const axialDistance = (a, b) => Math.min(((a - b) % 90 + 90) % 90, ((b - a) % 90 + 90) % 90);
const transformPoint = (p, angle, dx = 0, dy = 0) => {
  const radians = angle * Math.PI / 180;
  return [dx + Math.cos(radians) * p[0] - Math.sin(radians) * p[1],
    dy + Math.sin(radians) * p[0] + Math.cos(radians) * p[1]];
};
const transformLines = (lines, angle, dx = 0, dy = 0) => lines.map((line) => line.map((p) => transformPoint(p, angle, dx, dy)));
const reverseLines = (lines) => [...lines].reverse().map((line) => [...line].reverse());

test("facade orientation is data-derived modulo 90°, weighted by wall length", () => {
  const buildings = transformLines([rectangle], 27);
  const axes = dominantBuildingAxes(buildings);
  near(axialDistance(axes.orientation_deg_from_easting, 27), 0);
  near(axes.concentration, 1);
  near(axes.facade_length_m, 100);
  const aligned = routeAxisAlignment(transformLines([[[0, 0], [12, 0], [12, 8]]], 27), axes);
  near(aligned.weighted_mean_deviation_deg, 0);
  near(aligned.within_tolerance_length_ratio, 1);
  const diagonal = routeAxisAlignment(transformLines([[[0, 0], [10, 10]]], 27), axes);
  near(diagonal.weighted_mean_deviation_deg, 45);
  near(diagonal.within_tolerance_length_ratio, 0);
});

test("opposing equal orientation systems do not manufacture a dominant axis", () => {
  const axes = dominantBuildingAxes([rectangle, ...transformLines([rectangle], 45)]);
  near(axes.concentration, 0);
  assert.equal(axes.orientation_deg_from_easting, null);
  assert.equal(routeAxisAlignment([rectangle], axes).weighted_mean_deviation_deg, null);
});

test("dominant axes and alignment are translation/rotation/permutation invariant", () => {
  const buildings = [rectangle, [[40, 0], [50, 0], [50, 15], [40, 15], [40, 0]]];
  const route = [[[0, 0], [10, 0], [10, 10]], [[10, 10], [15, 15]]];
  const baselineAxes = dominantBuildingAxes(buildings);
  const baseline = routeAxisAlignment(route, baselineAxes);
  for (const angle of [17, 63, 113, -31]) {
    const movedBuildings = reverseLines(transformLines(buildings, angle, 1_000_000, -3_000_000));
    const axes = dominantBuildingAxes(movedBuildings);
    near(axialDistance(axes.orientation_deg_from_easting, baselineAxes.orientation_deg_from_easting + angle), 0);
    near(axes.concentration, baselineAxes.concentration);
    const measured = routeAxisAlignment(reverseLines(transformLines(route, angle, 1_000_000, -3_000_000)), axes);
    near(measured.weighted_mean_deviation_deg, baseline.weighted_mean_deviation_deg);
    near(measured.weighted_p95_deviation_deg, baseline.weighted_p95_deviation_deg);
    near(measured.within_tolerance_length_ratio, baseline.within_tolerance_length_ratio);
  }
});

test("facade subdivision and duplicated vertices do not reweight axes", () => {
  const original = [[[0, 0], [100, 0]], [[0, 0], [2, 2]]];
  const dense = [[...Array.from({ length: 101 }, (_, i) => [i, 0]), [100, 0]], original[1]];
  const a = dominantBuildingAxes(original);
  const b = dominantBuildingAxes(dense);
  near(axialDistance(a.orientation_deg_from_easting, b.orientation_deg_from_easting), 0);
  near(a.concentration, b.concentration);
  near(a.facade_length_m, b.facade_length_m);
});

test("parallel lines have the exact known distance in both directions and symmetric summary", () => {
  const a = [[[0, 0], [100, 0]]];
  const b = [[[0, 7], [100, 7]]];
  const result = symmetricProximity(a, b);
  for (const summary of [result.current_to_reference, result.reference_to_current, result.symmetric]) {
    near(summary.mean_distance_m, 7);
    near(summary.weighted_p95_distance_m, 7);
    near(summary.sampled_max_distance_m, 7);
    near(summary.within_distance[0].length_ratio, 1);
    assert(summary.continuous_max_upper_bound_m >= 7);
  }
});

test("1000 tiny far-away segments do not outweigh a long matching trunk", () => {
  const trunk = [[0, 0], [100, 0]];
  const tinyDenseLine = Array.from({ length: 1001 }, (_, i) => [i / 10_000, 100]);
  const result = symmetricProximity([trunk, tinyDenseLine], [trunk]);
  near(result.current_to_reference.mean_distance_m, 10 / 100.1);
  near(result.current_to_reference.weighted_p95_distance_m, 0);
  near(result.current_to_reference.within_distance[0].length_ratio, 100 / 100.1);
  near(result.symmetric.mean_distance_m, 10 / 200.1);
});

test("length-weighted proximity is invariant under swapping, permutations, rigid translation and rotation", () => {
  const a = [[[0, 0], [40, 0], [40, 20]], [[0, 0], [0, 10]]];
  const b = [[[0, 3], [40, 3], [40, 22]], [[0, 3], [-5, 10]]];
  const baseline = symmetricProximity(a, b);
  const swapped = symmetricProximity(b, a);
  near(swapped.symmetric.mean_distance_m, baseline.symmetric.mean_distance_m);
  near(swapped.symmetric.weighted_p95_distance_m, baseline.symmetric.weighted_p95_distance_m);
  for (const angle of [0, 29, 101]) {
    const moved = symmetricProximity(reverseLines(transformLines(a, angle, 9_000, -12_000)),
      reverseLines(transformLines(b, angle, 9_000, -12_000)));
    for (const key of ["current_to_reference", "reference_to_current", "symmetric"]) {
      near(moved[key].mean_distance_m, baseline[key].mean_distance_m);
      near(moved[key].weighted_p95_distance_m, baseline[key].weighted_p95_distance_m);
      near(moved[key].within_distance[0].length_ratio, baseline[key].within_distance[0].length_ratio);
    }
  }
});

test("midpoint discretization has explicit bounds, including line subdivision changes", () => {
  const line = [[0, 0], [21.7, 0]];
  const split = [[0, 0], [3.17, 0], [13.2, 0], [21.7, 0]];
  const target = [[[0, 1], [9, 7], [21.7, 0]]];
  const coarse = symmetricProximity([line], target, { sampleStepM: 3 });
  const subdivided = symmetricProximity([split], target, { sampleStepM: 3 });
  const fine = symmetricProximity([line], target, { sampleStepM: 0.1 });
  const c = coarse.current_to_reference;
  const d = subdivided.current_to_reference;
  const f = fine.current_to_reference;
  assert(Math.abs(c.mean_distance_m - f.mean_distance_m) <= c.mean_error_bound_m + f.mean_error_bound_m);
  assert(Math.abs(c.mean_distance_m - d.mean_distance_m) <= c.mean_error_bound_m + d.mean_error_bound_m);
  assert(c.sampled_max_distance_m <= c.continuous_max_upper_bound_m);
});

function tree() {
  const root = [0, -10];
  const branch = [3, 0];
  return { nodes: [{ id: "root", point: root, kind: "root", root: true },
    { id: "branch", point: branch, kind: "branch" },
    { id: "left", point: [-7, 0], kind: "demand" }, { id: "right", point: [13, 0], kind: "demand" }],
  edges: [{ from: "root", to: "branch", coordinates: [root, [0, -5], [3, -5], branch] },
    { from: "branch", to: "left", coordinates: [branch, [-7, 0]] },
    { from: "branch", to: "right", coordinates: [branch, [13, 0]] }] };
}

test("bends, T-junction pairs, branch chambers, root sites and rays are distinct", () => {
  const result = topologyAngles(tree());
  assert.equal(result.bend_count, 2);
  assert.equal(result.junction_count, 1);
  assert.equal(result.junction_pair_angles.near_90_count, 2);
  assert.equal(result.junction_pair_angles.near_180_count, 1);
  assert.equal(result.branch_chambers, 1);
  assert.equal(result.root_site_count, 1);
  assert.equal(result.root_ray_count, 1);
});

test("graph angle diagnostics are rotation/translation/node/edge/order-direction invariant", () => {
  const graph = tree();
  const baseline = topologyAngles(graph);
  for (const angle of [19, 121, -54]) {
    const moved = { nodes: [...graph.nodes].reverse().map((node) => ({ ...node, point: transformPoint(node.point, angle, -10_000, 2000) })),
      edges: [...graph.edges].reverse().map((edge) => ({ from: edge.to, to: edge.from,
        coordinates: transformLines([[...edge.coordinates].reverse()], angle, -10_000, 2000)[0] })) };
    const measured = topologyAngles(moved);
    for (const key of ["bend_count", "junction_count", "branch_chambers", "root_site_count", "root_ray_count"]) assert.equal(measured[key], baseline[key]);
    measured.bend_turn_angles_deg.forEach((value, i) => near(value, baseline.bend_turn_angles_deg[i]));
    measured.junctions[0].pair_angles_deg.forEach((value, i) => near(value, baseline.junctions[0].pair_angles_deg[i], 1e-5));
  }
});

test("a degree-two node does not hide an existing bend; collinear vertices are not bends", () => {
  const graph = tree();
  const original = graph.edges.shift();
  graph.nodes.push({ id: "elbow", point: [0, -5], kind: "routing_anchor" });
  graph.edges.push({ from: "root", to: "elbow", coordinates: [[0, -10], [0, -7], [0, -5]] },
    { from: "elbow", to: "branch", coordinates: original.coordinates.slice(1) });
  const result = topologyAngles(graph);
  assert.equal(result.internal_bend_count, 1);
  assert.equal(result.degree_two_bend_count, 1);
  assert.equal(result.bend_count, 2);
});

test("invalid or empty geometry and sampling parameters fail explicitly", () => {
  assert.throws(() => dominantBuildingAxes([]), /No nonzero facade/);
  assert.throws(() => dominantBuildingAxes([[[0, 0], [NaN, 1]]]), /finite metric/);
  assert.throws(() => symmetricProximity([], [rectangle]), /Both routes/);
  assert.throws(() => symmetricProximity([rectangle], [rectangle], { sampleStepM: 0 }), /positive/);
  assert.throws(() => routeAxisAlignment([rectangle], dominantBuildingAxes([rectangle]), 45), /Angle tolerance/);
  const graph = tree();
  graph.edges[0].from = "unknown";
  assert.throws(() => topologyAngles(graph), /Unknown graph endpoint/);
});

test("CLI requires an explicit result; help is read-only and describes advisory output", () => {
  const script = fileURLToPath(new URL("./routing-geometry-quality.mjs", import.meta.url));
  const missing = spawnSync(process.execPath, [script], { encoding: "utf8", timeout: 5000 });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /Missing required option: --result/);
  const help = spawnSync(process.execPath, [script, "--help"], { encoding: "utf8", timeout: 5000 });
  assert.equal(help.status, 0);
  assert.match(help.stdout, /All thresholds are advisory/);
});
