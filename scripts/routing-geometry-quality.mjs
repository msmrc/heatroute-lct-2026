#!/usr/bin/env node
/** Advisory-диагностика геометрии, не инженерный валидатор и не правило конкурсной приёмки. */
import { createHash } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { analyzeRoutingReference, projectEpsg32637 } from "./reference-routing-benchmark.mjs";
import { candidateModel, verifyReferenceProvenance } from "./render-routing-comparison.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const EPS_M = 1e-8;
const DEG = 180 / Math.PI;
const DEFAULTS = { sampleStepM: 2, angleToleranceDeg: 5, proximityThresholdsM: [10, 25] };

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function point(value) {
  requireValue(Array.isArray(value) && value.length >= 2 && value.slice(0, 2).every(Number.isFinite),
    "Expected finite metric [x, y] coordinates");
  return value;
}

const distance = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
const dot = (a, b) => a[0] * b[0] + a[1] * b[1];
const quarterTurn = (degrees) => ((degrees % 90) + 90) % 90;
const deviation = (angle, axis) => Math.min(quarterTurn(angle - axis), 90 - quarterTurn(angle - axis));

function sum(values) {
  let total = 0;
  let correction = 0;
  for (const value of values) {
    const adjusted = value - correction;
    const next = total + adjusted;
    correction = (next - total) - adjusted;
    total = next;
  }
  return total;
}

function segments(lines) {
  return lines.flatMap((line) => {
    requireValue(Array.isArray(line) && line.length >= 2, "Each polyline needs at least two coordinates");
    line.forEach(point);
    return line.slice(1).map((to, index) => {
      const from = line[index];
      const length = distance(from, to);
      return { from, to, length, direction: [(to[0] - from[0]) / length, (to[1] - from[1]) / length],
        angle: Math.atan2(to[1] - from[1], to[0] - from[0]) * DEG };
    }).filter((segment) => segment.length > EPS_M);
  });
}

function weightedQuantile(observations, fraction) {
  if (observations.length === 0) return null;
  const sorted = [...observations].sort((a, b) => a.value - b.value);
  const target = sum(sorted.map((item) => item.weight)) * fraction;
  let cumulative = 0;
  for (const item of sorted) {
    cumulative += item.weight;
    if (cumulative >= target) return item.value;
  }
  return sorted.at(-1).value;
}

/** Четвёртая круговая гармоника объединяет параллельные и перпендикулярные фасады.
 * Вес — длина стены, не количество вершин. При изотропии ось не выдумываем.
 */
export function dominantBuildingAxes(facadeLines) {
  const walls = segments(facadeLines);
  requireValue(walls.length > 0, "No nonzero facade segments to infer building axes");
  const length = sum(walls.map((wall) => wall.length));
  const x = sum(walls.map((wall) => wall.length * Math.cos(wall.angle / DEG * 4)));
  const y = sum(walls.map((wall) => wall.length * Math.sin(wall.angle / DEG * 4)));
  const concentration = Math.min(1, Math.hypot(x, y) / length);
  const angle = concentration <= 1e-9 ? null : quarterTurn(Math.atan2(y, x) * DEG / 4);
  const distribution = angle === null ? [] : walls.map((wall) => ({ value: deviation(wall.angle, angle), weight: wall.length }));
  return { method: "facade_length_weighted_fourth_circular_moment", orientation_deg_from_easting: angle,
    perpendicular_orientation_deg: angle === null ? null : angle + 90,
    concentration, facade_length_m: length, facade_segment_count: walls.length,
    weighted_mean_facade_deviation_deg: angle === null ? null : sum(distribution.map((item) => item.value * item.weight)) / length,
    weighted_p95_facade_deviation_deg: weightedQuantile(distribution, 0.95) };
}

export function routeAxisAlignment(lines, axes, toleranceDeg = DEFAULTS.angleToleranceDeg) {
  requireValue(toleranceDeg >= 0 && toleranceDeg < 45, "Angle tolerance must be in [0, 45) degrees");
  const route = segments(lines);
  const length = sum(route.map((segment) => segment.length));
  requireValue(length > 0, "Route has no nonzero segments");
  if (axes.orientation_deg_from_easting === null) return { length_m: length, segment_count: route.length,
    weighted_mean_deviation_deg: null, weighted_p95_deviation_deg: null, within_tolerance_length_ratio: null,
    reason: "No identifiable dominant facade axes" };
  const distribution = route.map((segment) => ({
    value: deviation(segment.angle, axes.orientation_deg_from_easting), weight: segment.length,
  }));
  return { length_m: length, segment_count: route.length, tolerance_deg: toleranceDeg,
    weighted_mean_deviation_deg: sum(distribution.map((item) => item.value * item.weight)) / length,
    weighted_p95_deviation_deg: weightedQuantile(distribution, 0.95),
    within_tolerance_length_ratio: sum(distribution.filter((item) => item.value <= toleranceDeg + 1e-9).map((item) => item.weight)) / length,
    off_axis_length_m: sum(distribution.filter((item) => item.value > toleranceDeg + 1e-9).map((item) => item.weight)) };
}

function nearestOnSegment(p, segment) {
  const offset = [p[0] - segment.from[0], p[1] - segment.from[1]];
  const along = Math.max(0, Math.min(segment.length, dot(offset, segment.direction)));
  const projection = [segment.from[0] + along * segment.direction[0], segment.from[1] + along * segment.direction[1]];
  return { distance: distance(p, projection), along, projection };
}

function directedSamples(source, target, stepM) {
  const observations = [];
  let endpointMaximum = 0;
  const distanceToTarget = (p) => {
    let minimum = Infinity;
    for (const segment of target) minimum = Math.min(minimum, nearestOnSegment(p, segment).distance);
    return minimum;
  };
  // Поворот/перенос даёт погрешность double возле целого L/step. Не меняем сетку выборки из-за ULP.
  const subdivisions = (length) => Math.max(1, Math.ceil(length / stepM - 1e-10 * Math.max(1, length / stepM)));
  const sampleCount = sum(source.map((segment) => subdivisions(segment.length)));
  requireValue(sampleCount <= 500_000 && sampleCount * target.length <= 100_000_000,
    "Diagnostic sampling budget exceeded; increase --step-m or use a smaller route snapshot");
  for (const segment of source) {
    const count = subdivisions(segment.length);
    const weight = segment.length / count;
    for (let i = 0; i < count; i += 1) {
      const along = (i + 0.5) * weight;
      const p = [segment.from[0] + segment.direction[0] * along, segment.from[1] + segment.direction[1] * along];
      observations.push({ value: distanceToTarget(p), weight, halfCell: weight / 2 });
    }
    endpointMaximum = Math.max(endpointMaximum, distanceToTarget(segment.from), distanceToTarget(segment.to));
  }
  return { observations, endpointMaximum };
}

function sampleSummary({ observations, endpointMaximum }, thresholds) {
  const length = sum(observations.map((item) => item.weight));
  const maximum = observations.reduce((value, item) => Math.max(value, item.value), endpointMaximum);
  const halfCell = observations.reduce((value, item) => Math.max(value, item.halfCell), 0);
  return { sampled_length_m: length, sample_count: observations.length,
    mean_distance_m: sum(observations.map((item) => item.weight * item.value)) / length,
    weighted_p50_distance_m: weightedQuantile(observations, 0.5),
    weighted_p95_distance_m: weightedQuantile(observations, 0.95), sampled_max_distance_m: maximum,
    // Distance-to-polyline — 1-Lipschitz: погрешность среднего <= h/4, квантилей <= h/2.
    mean_error_bound_m: sum(observations.map((item) => item.weight * item.halfCell / 2)) / length,
    quantile_error_bound_m: halfCell, continuous_max_upper_bound_m: maximum + halfCell,
    within_distance: thresholds.map((limit) => ({ threshold_m: limit,
      length_ratio: sum(observations.filter((item) => item.value <= limit + 1e-9).map((item) => item.weight)) / length,
      conservative_lower_ratio: sum(observations.filter((item) => item.value + item.halfCell <= limit).map((item) => item.weight)) / length,
      conservative_upper_ratio: sum(observations.filter((item) => item.value - item.halfCell <= limit).map((item) => item.weight)) / length,
    })) };
}

/** Midpoint-квадратура по длине каждого сегмента; симметрия — объединённая мера длин обеих сетей.
 * Концевые точки не получают искусственный вес. Max — оценка с границей, не точный Hausdorff.
 */
export function symmetricProximity(currentLines, referenceLines, options = {}) {
  const step = options.sampleStepM ?? DEFAULTS.sampleStepM;
  const thresholds = options.proximityThresholdsM ?? DEFAULTS.proximityThresholdsM;
  requireValue(Number.isFinite(step) && step > 0, "Sampling step must be positive and finite");
  requireValue(thresholds.every((value) => Number.isFinite(value) && value >= 0), "Invalid proximity threshold");
  const current = segments(currentLines);
  const reference = segments(referenceLines);
  requireValue(current.length > 0 && reference.length > 0, "Both routes need nonzero segments for proximity");
  const forward = directedSamples(current, reference, step);
  const backward = directedSamples(reference, current, step);
  return { advisory: true, method: "segment_midpoints_weighted_by_represented_arc_length", sample_step_m: step,
    current_to_reference: sampleSummary(forward, thresholds), reference_to_current: sampleSummary(backward, thresholds),
    symmetric: sampleSummary({ observations: [...forward.observations, ...backward.observations],
      endpointMaximum: Math.max(forward.endpointMaximum, backward.endpointMaximum) }, thresholds) };
}

function angleBetween(a, b) {
  return Math.acos(Math.max(-1, Math.min(1, dot(a, b)))) * DEG;
}

function angleSummary(angles, toleranceDeg) {
  return { count: angles.length, min_deg: angles.length ? Math.min(...angles) : null,
    max_deg: angles.length ? Math.max(...angles) : null,
    near_90_count: angles.filter((angle) => Math.abs(angle - 90) <= toleranceDeg + 1e-9).length,
    near_180_count: angles.filter((angle) => Math.abs(angle - 180) <= toleranceDeg + 1e-9).length,
    acute_below_90_minus_tolerance_count: angles.filter((angle) => angle < 90 - toleranceDeg - 1e-9).length,
    oblique_count: angles.filter((angle) => Math.abs(angle - 90) > toleranceDeg + 1e-9
      && Math.abs(angle - 180) > toleranceDeg + 1e-9).length };
}

/** Внутренние повороты + повороты в узлах степени 2; камеры/корни не выводятся из числа вершин. */
export function topologyAngles(graph, toleranceDeg = DEFAULTS.angleToleranceDeg) {
  requireValue(toleranceDeg >= 0 && toleranceDeg < 45, "Angle tolerance must be in [0, 45) degrees");
  const rays = new Map(graph.nodes.map((node) => [node.id, []]));
  requireValue(rays.size === graph.nodes.length, "Duplicate graph node IDs");
  const bends = [];
  for (const edge of graph.edges) {
    requireValue(rays.has(edge.from) && rays.has(edge.to), "Unknown graph endpoint");
    const parts = segments([edge.coordinates]);
    requireValue(parts.length > 0, "Graph edge has no nonzero segment");
    rays.get(edge.from).push(parts[0].direction);
    rays.get(edge.to).push(parts.at(-1).direction.map((value) => -value));
    for (let i = 1; i < parts.length; i += 1) {
      const turn = angleBetween(parts[i - 1].direction, parts[i].direction);
      if (turn > toleranceDeg + 1e-9) bends.push(turn);
    }
  }
  const degreeTwoTurns = [];
  const junctions = [];
  const rootAngles = [];
  for (const node of graph.nodes) {
    const directions = rays.get(node.id);
    const angles = directions.flatMap((direction, i) => directions.slice(i + 1).map((other) => angleBetween(direction, other)));
    const record = { node_id: node.id, degree: directions.length, pair_angles_deg: angles.sort((a, b) => a - b),
      ...angleSummary(angles, toleranceDeg) };
    if (directions.length >= 3) junctions.push(record);
    if (node.root) rootAngles.push(record);
    if (!node.root && directions.length === 2 && 180 - angles[0] > toleranceDeg + 1e-9) degreeTwoTurns.push(180 - angles[0]);
  }
  const roots = graph.nodes.filter((node) => node.root);
  return { tolerance_deg: toleranceDeg, internal_bend_count: bends.length, degree_two_bend_count: degreeTwoTurns.length,
    bend_count: bends.length + degreeTwoTurns.length, bend_turn_angles_deg: [...bends, ...degreeTwoTurns].sort((a, b) => a - b),
    junction_count: junctions.length, junction_pair_angles: angleSummary(junctions.flatMap((node) => node.pair_angles_deg), toleranceDeg),
    junctions: junctions.sort((a, b) => String(a.node_id).localeCompare(String(b.node_id))),
    roots: rootAngles.sort((a, b) => String(a.node_id).localeCompare(String(b.node_id))),
    branch_chambers: graph.nodes.filter((node) => node.kind === "branch").length,
    root_node_count: roots.length, root_site_count: new Set(roots.map((node) => JSON.stringify(node.point))).size,
    root_ray_count: sum(roots.map((node) => rays.get(node.id).length)) };
}

function facadeLines(official) {
  const features = official.features.filter((feature) => (feature.properties?.object_type === "restriction"
    && feature.properties.restriction_type === "oks") || ["oks_existing", "oks_future"].includes(feature.properties?.object_type));
  const lines = features.flatMap((feature) => {
    const geometry = feature.geometry;
    requireValue(["Polygon", "MultiPolygon"].includes(geometry?.type), "Building facades must be Polygon or MultiPolygon");
    const rings = geometry.type === "Polygon" ? geometry.coordinates : geometry.coordinates.flat();
    return rings.map((ring) => {
      requireValue(ring.length >= 4 && distance(ring[0], ring.at(-1)) === 0, "Unclosed building polygon ring");
      return ring.map(projectEpsg32637);
    });
  });
  return { lines, building_feature_count: features.length, scope: "all building polygon rings in supplied official dataset" };
}

function candidateGraph(bundle, id) {
  const run = bundle.run ?? bundle;
  const result = run.result ?? run;
  const variant = result.variants.find((item) => item.id === id);
  const model = candidateModel(bundle, id);
  return { model, lines: model.lines.map((line) => line.coordinates), nodes: variant.nodes.map((node) => ({
    id: node.id, point: [node.coordinate.xm, node.coordinate.ym], root: node.root,
    kind: node.node_type === "new_branch_chamber" ? "branch" : node.node_type,
  })), edges: variant.edges.map((edge) => ({ from: edge.upstream_node_id, to: edge.downstream_node_id,
    coordinates: edge.coordinates.map((p) => [p.xm, p.ym]) })) };
}

function stationOnLine(p, line) {
  let accumulated = 0;
  let best = { distance: Infinity, station: 0 };
  for (const segment of segments([line])) {
    const projection = nearestOnSegment(p, segment);
    if (projection.distance < best.distance) best = { distance: projection.distance, station: accumulated + projection.along };
    accumulated += segment.length;
  }
  return best.station;
}

function subline(line, start, end) {
  const result = [];
  let accumulated = 0;
  for (const segment of segments([line])) {
    const low = Math.max(start, accumulated);
    const high = Math.min(end, accumulated + segment.length);
    if (high > low + EPS_M) {
      const at = (station) => segment.from.map((value, i) => value + segment.direction[i] * (station - accumulated));
      const from = at(low);
      if (!result.length || distance(result.at(-1), from) > EPS_M) result.push(from);
      result.push(at(high));
    }
    accumulated += segment.length;
  }
  requireValue(result.length >= 2, "Empty reference graph subline");
  return result;
}

function referenceGraph(reference, official, manifest) {
  const analysis = analyzeRoutingReference(reference, official, manifest);
  requireValue(analysis.valid, `Reference contract failed: ${analysis.issues.join("; ")}`);
  const linesById = new Map(reference.features.filter((feature) => feature.properties.reference_role === "route_centerline")
    .map((feature) => [feature.properties.id, feature.geometry.coordinates.map(projectEpsg32637)]));
  const nodes = reference.features.filter((feature) => feature.properties.reference_role === "junction_marker")
    .map((feature) => ({ id: feature.properties.id, point: projectEpsg32637(feature.geometry.coordinates), kind: "branch", root: false }));
  for (const feature of official.features.filter((f) => f.properties.object_type === "oks_connection_point")) {
    nodes.push({ id: `demand:${feature.properties.id}`, point: projectEpsg32637(feature.geometry.coordinates), kind: "demand", root: false });
  }
  const contract = manifest.root_existing_object;
  const root = official.features.find((f) => f.properties.object_type === contract.object_type && String(f.properties.id) === String(contract.id));
  nodes.push({ id: `existing:${contract.object_type}:${contract.id}`, point: projectEpsg32637(root.geometry.coordinates), kind: "root", root: true });
  const byId = new Map(nodes.map((node) => [node.id, node]));
  // Разрезаем только диагностический граф по проекциям маркеров. Proximity использует нетронутые исходные линии.
  const edges = analysis.logicalEdges.map((edge) => {
    const line = linesById.get(edge.sourceFeatureId);
    return { from: edge.from, to: edge.to, coordinates: subline(line,
      stationOnLine(byId.get(edge.from).point, line), stationOnLine(byId.get(edge.to).point, line)) };
  });
  return { lines: [...linesById.values()], nodes, edges, contract_metrics: analysis.metrics };
}

function recommendations(candidate, reference, axes) {
  const messages = [];
  if (axes.concentration < 0.25) messages.push("Слабая единая система фасадов (concentration < 0.25): исследовать локальные блоки, не навязывать глобальную ортогональную сетку.");
  if (candidate.alignment.weighted_mean_deviation_deg !== null && reference.alignment.weighted_mean_deviation_deg !== null
    && candidate.alignment.weighted_mean_deviation_deg > reference.alignment.weighted_mean_deviation_deg + 5) {
    messages.push("Среднее отклонение от фасадов выше эталона более чем на 5°: проверить data-driven фасадные коридоры/локальные оси вместо фиксированных мировых направлений.");
  }
  if (candidate.topology.branch_chambers > reference.topology.branch_chambers) messages.push("Узловых камер больше, чем у эталона: проверить общие стволы и группировку вводов; число эталона не является лимитом.");
  if (candidate.proximity.symmetric.within_distance.some((item) => item.threshold_m === 25 && item.length_ratio < 0.8)) {
    messages.push("Менее 80% объединённой длины лежит в 25 м от другой сети: визуально проверить выбор коридоров; не копировать координаты эталона.");
  }
  messages.push("Все пороги эвристические/advisory. Ортогональность не объявляется требованием ТЗ; близость к эталону не доказывает безопасность, оптимальность, sizing или допустимость.");
  return messages;
}

export function analyzeGeometryQuality(bundle, official, reference, manifest, options = {}) {
  const config = { ...DEFAULTS, ...options };
  const buildings = facadeLines(official);
  const axes = { ...dominantBuildingAxes(buildings.lines), building_feature_count: buildings.building_feature_count, scope: buildings.scope };
  const expert = referenceGraph(reference, official, manifest);
  const expertSummary = { alignment: routeAxisAlignment(expert.lines, axes, config.angleToleranceDeg),
    topology: topologyAngles(expert, config.angleToleranceDeg), contract_metrics: expert.contract_metrics };
  const run = bundle.run ?? bundle;
  const result = run.result ?? run;
  requireValue(Array.isArray(result.variants), "Missing result variants");
  const selector = options.variant ?? "all";
  const ids = selector === "all" ? result.variants.map((variant) => variant.id).sort()
    : [selector === "best" ? result.preferred_variant_id : selector];
  requireValue(ids.length > 0 && ids.every((id) => result.variants.some((variant) => variant.id === id)), "Unknown or missing selected variant");
  const variants = ids.map((id) => {
    const graph = candidateGraph(bundle, id);
    const summary = { source_metrics: graph.model.metrics,
      alignment: routeAxisAlignment(graph.lines, axes, config.angleToleranceDeg),
      topology: topologyAngles(graph, config.angleToleranceDeg),
      proximity: symmetricProximity(graph.lines, expert.lines, config) };
    return { variant_id: id, ...summary, advisory_recommendations: recommendations(summary, expertSummary, axes) };
  });
  return { schema: "heatroute-geometry-quality-v1", advisory: true, crs: "EPSG:32637",
    algorithm_version: result.algorithm_version ?? run.algorithm_version, run_id: run.id ?? null,
    parameters: run.parameters ?? null, preferred_variant_id: result.preferred_variant_id ?? null,
    config: { sample_step_m: config.sampleStepM, angle_tolerance_deg: config.angleToleranceDeg,
      proximity_thresholds_m: config.proximityThresholdsM, low_axis_concentration: 0.25,
      mean_axis_deviation_gap_warning_deg: 5, proximity_warning_length_ratio_at_25m: 0.8 },
    caveats: ["Reference similarity and orthogonality are advisory, not official acceptance requirements.",
      "Facade axes use all supplied building rings; one global orientation may hide locally rotated blocks.",
      "Reference graph incidence uses manifest snap tolerance; original lines remain unchanged for length/alignment/proximity.",
      "Lengths count each supplied edge once; overlapping duplicates are not dissolved.",
      "Proximity uses length-weighted midpoint quadrature with error bounds; sampled maximum is not exact Hausdorff."],
    axes, reference: expertSummary, variants };
}

async function readInput(path) {
  requireValue((await stat(path)).size <= 32 * 1024 * 1024, `Diagnostic input exceeds 32 MiB: ${path}`);
  const bytes = await readFile(path);
  return { path, bytes, data: JSON.parse(bytes.toString("utf8")), sha256: createHash("sha256").update(bytes).digest("hex") };
}

export async function main(argv = process.argv.slice(2)) {
  const args = { official: "datasets/official/lct-2026.geojson", reference: "datasets/reference/professional-routing-01.geojson",
    manifest: "datasets/reference/professional-routing-01.reference.json", variant: "all", "step-m": "2", "angle-tolerance-deg": "5" };
  const keys = new Set([...Object.keys(args), "result", "reference-source"]);
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i].replace(/^--/, "");
    if (key === "help") args.help = true;
    else {
      requireValue(argv[i].startsWith("--") && keys.has(key) && argv[i + 1] && !argv[i + 1].startsWith("--"), `Unknown option/missing value: ${argv[i]}`);
      args[key] = argv[++i];
    }
  }
  if (args.help) {
    console.log("node scripts/routing-geometry-quality.mjs --result bundle.json [--variant all|best|<id>]\n"
      + "  [--official dataset.geojson] [--reference expert.geojson] [--manifest reference.json]\n"
      + "  [--reference-source /read-only/path/1.geojson] [--step-m 2] [--angle-tolerance-deg 5]\n"
      + "Read-only diagnostics to stdout. Relative paths resolve from repository root. All thresholds are advisory.");
    return;
  }
  requireValue(args.result?.trim(), "Missing required option: --result <bundle.json>");
  const [result, official, reference, manifest, original] = await Promise.all(["result", "official", "reference", "manifest", "reference-source"]
    .map((key) => args[key] ? readInput(resolve(ROOT, args[key])) : null));
  requireValue(official.sha256 === manifest.data.official_input_sha256, "Official input SHA-256 differs from reference contract");
  const run = result.data.run ?? result.data;
  requireValue(run.input_sha256 === official.sha256, "Result input SHA-256 differs from official input");
  const provenance = verifyReferenceProvenance(reference.data, manifest.data, original);
  const report = analyzeGeometryQuality(result.data, official.data, reference.data, manifest.data, {
    variant: args.variant, sampleStepM: Number(args["step-m"]), angleToleranceDeg: Number(args["angle-tolerance-deg"]),
  });
  report.reference_provenance = provenance;
  report.inputs = Object.fromEntries([result, official, reference, manifest, original].filter(Boolean)
    .map((input) => [input.path, { sha256: input.sha256, bytes: input.bytes.length }]));
  console.log(JSON.stringify(report, null, 2));
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
