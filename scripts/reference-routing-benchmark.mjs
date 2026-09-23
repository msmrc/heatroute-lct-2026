import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_REFERENCE = "datasets/reference/professional-routing-01.geojson";
const DEFAULT_MANIFEST = "datasets/reference/professional-routing-01.reference.json";
const DEFAULT_OFFICIAL_INPUT = "datasets/official/lct-2026.geojson";

function invariant(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

// WGS 84 / UTM zone 37N. This mirrors the EPSG:32637 metric boundary used by the backend.
export function projectEpsg32637([longitude, latitude]) {
  const semiMajorAxis = 6_378_137;
  const flattening = 1 / 298.257223563;
  const scale = 0.9996;
  const eccentricitySquared = flattening * (2 - flattening);
  const secondEccentricitySquared = eccentricitySquared / (1 - eccentricitySquared);
  const latitudeRad = latitude * Math.PI / 180;
  const longitudeRad = longitude * Math.PI / 180;
  const centralMeridianRad = 39 * Math.PI / 180;
  const sinLatitude = Math.sin(latitudeRad);
  const cosLatitude = Math.cos(latitudeRad);
  const tangentSquared = Math.tan(latitudeRad) ** 2;
  const etaSquared = secondEccentricitySquared * cosLatitude ** 2;
  const longitudeTerm = cosLatitude * (longitudeRad - centralMeridianRad);
  const radius = semiMajorAxis / Math.sqrt(1 - eccentricitySquared * sinLatitude ** 2);
  const meridianArc = semiMajorAxis * (
    (1 - eccentricitySquared / 4
      - 3 * eccentricitySquared ** 2 / 64
      - 5 * eccentricitySquared ** 3 / 256) * latitudeRad
    - (3 * eccentricitySquared / 8
      + 3 * eccentricitySquared ** 2 / 32
      + 45 * eccentricitySquared ** 3 / 1024) * Math.sin(2 * latitudeRad)
    + (15 * eccentricitySquared ** 2 / 256
      + 45 * eccentricitySquared ** 3 / 1024) * Math.sin(4 * latitudeRad)
    - 35 * eccentricitySquared ** 3 / 3072 * Math.sin(6 * latitudeRad)
  );
  const x = 500_000 + scale * radius * (
    longitudeTerm
    + (1 - tangentSquared + etaSquared) * longitudeTerm ** 3 / 6
    + (5 - 18 * tangentSquared + tangentSquared ** 2
      + 72 * etaSquared - 58 * secondEccentricitySquared) * longitudeTerm ** 5 / 120
  );
  const y = scale * (
    meridianArc
    + radius * Math.tan(latitudeRad) * (
      longitudeTerm ** 2 / 2
      + (5 - tangentSquared + 9 * etaSquared + 4 * etaSquared ** 2)
        * longitudeTerm ** 4 / 24
      + (61 - 58 * tangentSquared + tangentSquared ** 2
        + 600 * etaSquared - 330 * secondEccentricitySquared)
        * longitudeTerm ** 6 / 720
    )
  );
  return [x, y];
}

function metricLine(coordinates) {
  return coordinates.map(projectEpsg32637);
}

function lineLengthMetric(metricCoordinates) {
  let lengthM = 0;
  for (let index = 1; index < metricCoordinates.length; index += 1) {
    lengthM += Math.hypot(
      metricCoordinates[index][0] - metricCoordinates[index - 1][0],
      metricCoordinates[index][1] - metricCoordinates[index - 1][1],
    );
  }
  return lengthM;
}

function distanceMetric(left, right) {
  return Math.hypot(left[0] - right[0], left[1] - right[1]);
}

function projectPointToMetricLine(point, line) {
  let stationM = 0;
  let traversedM = 0;
  let minimumDistanceM = Number.POSITIVE_INFINITY;
  for (let index = 1; index < line.length; index += 1) {
    const from = line[index - 1];
    const to = line[index];
    const dx = to[0] - from[0];
    const dy = to[1] - from[1];
    const segmentLengthSquared = dx ** 2 + dy ** 2;
    const segmentLengthM = Math.sqrt(segmentLengthSquared);
    const projection = segmentLengthSquared === 0
      ? 0
      : Math.max(0, Math.min(1,
        ((point[0] - from[0]) * dx + (point[1] - from[1]) * dy)
          / segmentLengthSquared));
    const projected = [from[0] + projection * dx, from[1] + projection * dy];
    const distanceM = distanceMetric(point, projected);
    if (distanceM < minimumDistanceM) {
      minimumDistanceM = distanceM;
      stationM = traversedM + projection * segmentLengthM;
    }
    traversedM += segmentLengthM;
  }
  return { distanceM: minimumDistanceM, stationM, totalLengthM: traversedM };
}

function featuresOfType(collection, objectType) {
  return collection.features.filter((feature) => feature?.properties?.object_type === objectType);
}

function referenceFeaturesByRole(collection, role) {
  return collection.features.filter((feature) => feature?.properties?.reference_role === role);
}

function graphComponents(nodeIds, edges) {
  const adjacency = new Map(nodeIds.map((nodeId) => [nodeId, []]));
  for (const edge of edges) {
    adjacency.get(edge.from).push(edge.to);
    adjacency.get(edge.to).push(edge.from);
  }
  let componentCount = 0;
  const visited = new Set();
  for (const nodeId of nodeIds) {
    if (visited.has(nodeId)) continue;
    componentCount += 1;
    const stack = [nodeId];
    while (stack.length > 0) {
      const current = stack.pop();
      if (visited.has(current)) continue;
      visited.add(current);
      stack.push(...adjacency.get(current).filter((next) => !visited.has(next)));
    }
  }
  return { adjacency, componentCount };
}

function rootBranchFlows(rootId, adjacency, demandFlowByNode) {
  return adjacency.get(rootId).map((rootNeighbour) => {
    let flowTph = 0;
    const stack = [[rootNeighbour, rootId]];
    const visited = new Set([rootId]);
    while (stack.length > 0) {
      const [nodeId, parentId] = stack.pop();
      if (visited.has(nodeId)) continue;
      visited.add(nodeId);
      flowTph += demandFlowByNode.get(nodeId) ?? 0;
      for (const neighbour of adjacency.get(nodeId)) {
        if (neighbour !== parentId && !visited.has(neighbour)) stack.push([neighbour, nodeId]);
      }
    }
    return Math.round(flowTph * 100) / 100;
  }).sort((left, right) => left - right);
}

function compareNumber(actual, expected, label, issues, tolerance = 0) {
  if (Math.abs(actual - expected) > tolerance) {
    issues.push(`${label}: expected ${expected}, got ${actual}`);
  }
}

export function analyzeRoutingReference(reference, officialInput, manifest) {
  invariant(reference?.type === "FeatureCollection", "Reference must be a FeatureCollection");
  invariant(Array.isArray(reference.features), "Reference features must be an array");
  invariant(officialInput?.type === "FeatureCollection", "Official input must be a FeatureCollection");
  invariant(Array.isArray(officialInput.features), "Official input features must be an array");

  const routeFeatures = referenceFeaturesByRole(reference, "route_centerline");
  const junctionFeatures = referenceFeaturesByRole(reference, "junction_marker");
  const demandFeatures = featuresOfType(officialInput, "oks_connection_point");
  const rootContract = manifest.root_existing_object;
  const rootFeature = officialInput.features.find((feature) =>
    feature?.properties?.object_type === rootContract.object_type
      && String(feature.properties.id) === String(rootContract.id));
  invariant(rootFeature, `Existing root ${rootContract.object_type}:${rootContract.id} is missing`);

  const junctionAnchors = junctionFeatures.map((feature) => ({
    id: feature.properties.id,
    kind: "junction",
    coordinate: feature.geometry.coordinates,
    metric: projectEpsg32637(feature.geometry.coordinates),
  }));
  const demandAnchors = demandFeatures.map((feature) => ({
    id: `demand:${feature.properties.id}`,
    kind: "demand",
    coordinate: feature.geometry.coordinates,
    metric: projectEpsg32637(feature.geometry.coordinates),
    flowTph: Number(feature.properties.flow_tph),
  }));
  const rootAnchor = {
    id: `existing:${rootContract.object_type}:${rootContract.id}`,
    kind: "root",
    coordinate: rootFeature.geometry.coordinates,
    metric: projectEpsg32637(rootFeature.geometry.coordinates),
  };
  const anchors = [...junctionAnchors, ...demandAnchors, rootAnchor];
  const snapToleranceM = manifest.snap_tolerance_m;
  const logicalEdges = [];
  let totalLengthM = 0;
  let maximumAttachmentDistanceM = 0;

  for (const feature of routeFeatures) {
    invariant(feature.geometry?.type === "LineString", `${feature.properties.id} must be a LineString`);
    const line = metricLine(feature.geometry.coordinates);
    const lineLengthM = lineLengthMetric(line);
    totalLengthM += lineLengthM;
    const endpoints = [line[0], line[line.length - 1]];
    const matches = [];
    for (const anchor of anchors) {
      const projection = projectPointToMetricLine(anchor.metric, line);
      const endpointDistanceM = Math.min(
        distanceMetric(anchor.metric, endpoints[0]),
        distanceMetric(anchor.metric, endpoints[1]),
      );
      const matchesLine = anchor.kind === "junction"
        ? projection.distanceM <= snapToleranceM
        : endpointDistanceM <= snapToleranceM;
      if (matchesLine) {
        matches.push({ ...anchor, ...projection, endpointDistanceM });
        if (anchor.kind !== "junction") {
          maximumAttachmentDistanceM = Math.max(maximumAttachmentDistanceM, endpointDistanceM);
        }
      }
    }
    matches.sort((left, right) => left.stationM - right.stationM);
    const uniqueMatches = matches.filter((match, index) =>
      index === 0 || match.id !== matches[index - 1].id);
    invariant(uniqueMatches.length >= 2, `${feature.properties.id} has fewer than two matched nodes`);
    invariant(uniqueMatches[0].stationM <= snapToleranceM,
      `${feature.properties.id} start is not attached to a known node`);
    invariant(lineLengthM - uniqueMatches[uniqueMatches.length - 1].stationM <= snapToleranceM,
      `${feature.properties.id} end is not attached to a known node`);
    for (let index = 1; index < uniqueMatches.length; index += 1) {
      const from = uniqueMatches[index - 1];
      const to = uniqueMatches[index];
      invariant(from.id !== to.id, `${feature.properties.id} creates a self-loop at ${from.id}`);
      logicalEdges.push({
        sourceFeatureId: feature.properties.id,
        from: from.id,
        to: to.id,
        lengthM: to.stationM - from.stationM,
      });
    }
  }

  const nodeIds = anchors.map((anchor) => anchor.id);
  const { adjacency, componentCount } = graphComponents(nodeIds, logicalEdges);
  const degrees = new Map([...adjacency].map(([nodeId, neighbours]) => [nodeId, neighbours.length]));
  const demandLeaves = demandAnchors.filter((anchor) => degrees.get(anchor.id) === 1).length;
  const maximumJunctionDegree = Math.max(...junctionAnchors.map((anchor) => degrees.get(anchor.id)));
  const cycleCount = logicalEdges.length - nodeIds.length + componentCount;
  const demandFlowByNode = new Map(demandAnchors.map((anchor) => [anchor.id, anchor.flowTph]));
  const branchFlowsTph = rootBranchFlows(rootAnchor.id, adjacency, demandFlowByNode);
  const totalDemandFlowTph = Math.round(
    demandAnchors.reduce((sum, anchor) => sum + anchor.flowTph, 0) * 100,
  ) / 100;

  const metrics = {
    routeFeatureCount: routeFeatures.length,
    junctionMarkerCount: junctionFeatures.length,
    logicalSectionCount: logicalEdges.length,
    graphNodeCount: nodeIds.length,
    connectedComponentCount: componentCount,
    cycleCount,
    demandCount: demandAnchors.length,
    demandLeafCount: demandLeaves,
    maximumJunctionDegree,
    rootDegree: degrees.get(rootAnchor.id),
    rootBranchFlowsTph: branchFlowsTph,
    totalDemandFlowTph,
    totalLengthM: Math.round(totalLengthM * 1000) / 1000,
    maximumAttachmentDistanceM: Math.round(maximumAttachmentDistanceM * 1000) / 1000,
  };

  const expected = manifest.expected;
  const issues = [];
  compareNumber(metrics.routeFeatureCount, expected.route_feature_count, "route features", issues);
  compareNumber(metrics.junctionMarkerCount, expected.junction_marker_count, "junction markers", issues);
  compareNumber(metrics.logicalSectionCount, expected.logical_section_count, "logical sections", issues);
  compareNumber(metrics.graphNodeCount, expected.graph_node_count, "graph nodes", issues);
  compareNumber(metrics.connectedComponentCount, expected.connected_component_count, "components", issues);
  compareNumber(metrics.cycleCount, expected.cycle_count, "cycles", issues);
  compareNumber(metrics.demandCount, expected.demand_count, "demands", issues);
  compareNumber(metrics.demandLeafCount, expected.demand_leaf_count, "demand leaves", issues);
  compareNumber(metrics.maximumJunctionDegree, expected.maximum_junction_degree,
    "maximum junction degree", issues);
  compareNumber(metrics.rootDegree, expected.root_degree, "root degree", issues);
  compareNumber(metrics.totalDemandFlowTph, expected.total_demand_flow_tph,
    "total demand flow", issues, 0.001);
  compareNumber(metrics.totalLengthM, expected.total_length_m.value,
    "total reference length", issues, expected.total_length_m.tolerance);
  if (JSON.stringify(metrics.rootBranchFlowsTph) !== JSON.stringify(expected.root_branch_flows_tph)) {
    issues.push(`root branch flows: expected ${expected.root_branch_flows_tph.join(", ")}, `
      + `got ${metrics.rootBranchFlowsTph.join(", ")}`);
  }
  if (metrics.maximumAttachmentDistanceM > snapToleranceM) {
    issues.push(`maximum attachment distance ${metrics.maximumAttachmentDistanceM} m exceeds `
      + `${snapToleranceM} m`);
  }
  return { valid: issues.length === 0, issues, metrics, logicalEdges };
}

function pointDistanceToLines(point, metricLines) {
  return Math.min(...metricLines.map((line) => projectPointToMetricLine(point, line).distanceM));
}

function sampleMetricLines(metricLines, stepM = 10) {
  const samples = [];
  for (const line of metricLines) {
    for (let index = 1; index < line.length; index += 1) {
      const from = line[index - 1];
      const to = line[index];
      const lengthM = distanceMetric(from, to);
      const steps = Math.max(1, Math.ceil(lengthM / stepM));
      for (let step = 0; step < steps; step += 1) {
        const ratio = step / steps;
        samples.push([
          from[0] + (to[0] - from[0]) * ratio,
          from[1] + (to[1] - from[1]) * ratio,
        ]);
      }
    }
    samples.push(line[line.length - 1]);
  }
  return samples;
}

function proximityMetrics(sourceLines, targetLines) {
  const distances = sampleMetricLines(sourceLines)
    .map((point) => pointDistanceToLines(point, targetLines))
    .sort((left, right) => left - right);
  const meanDistanceM = distances.reduce((sum, distance) => sum + distance, 0) / distances.length;
  const percentile95M = distances[Math.min(distances.length - 1, Math.floor(distances.length * 0.95))];
  const within = (limitM) => distances.filter((distance) => distance <= limitM).length / distances.length;
  return {
    meanDistanceM: Math.round(meanDistanceM * 100) / 100,
    percentile95M: Math.round(percentile95M * 100) / 100,
    within10mRatio: Math.round(within(10) * 1000) / 1000,
    within25mRatio: Math.round(within(25) * 1000) / 1000,
  };
}

export function analyzeCandidate(candidate, reference, demandCount) {
  invariant(candidate?.type === "FeatureCollection", "Candidate must be a FeatureCollection");
  const referenceLines = referenceFeaturesByRole(reference, "route_centerline")
    .map((feature) => metricLine(feature.geometry.coordinates));
  const variants = new Map();
  for (const feature of candidate.features ?? []) {
    const variantId = feature?.properties?.variant_id;
    if (!variantId) continue;
    if (!variants.has(variantId)) {
      variants.set(variantId, { lines: [], chambers: 0, tieIns: [], summary: null });
    }
    const variant = variants.get(variantId);
    switch (feature.properties.object_type) {
      case "heat_network":
        variant.lines.push(feature);
        break;
      case "heat_chamber":
        variant.chambers += 1;
        break;
      case "tie_in":
        variant.tieIns.push(feature);
        break;
      case "variant_summary":
        variant.summary = feature.properties;
        break;
      default:
        break;
    }
  }
  invariant(variants.size > 0, "Candidate contains no variant_id features");
  return [...variants].map(([variantId, variant]) => {
    invariant(variant.lines.length > 0, `Candidate variant ${variantId} has no heat_network lines`);
    const candidateLines = variant.lines.map((feature) => metricLine(feature.geometry.coordinates));
    const referenceToCandidate = proximityMetrics(referenceLines, candidateLines);
    const candidateToReference = proximityMetrics(candidateLines, referenceLines);
    const nodes = new Set();
    const edges = [];
    for (const feature of variant.lines) {
      const from = feature.properties.start_node_id;
      const to = feature.properties.end_node_id;
      if (from && to) {
        nodes.add(from);
        nodes.add(to);
        edges.push({ from, to });
      }
    }
    const topology = nodes.size > 0 ? graphComponents([...nodes], edges) : null;
    const maximumDegree = topology
      ? Math.max(...[...topology.adjacency.values()].map((neighbours) => neighbours.length))
      : null;
    return {
      variantId,
      rank: variant.summary?.rank ?? null,
      connectedDemandCount: variant.summary
        ? demandCount - (variant.summary.unconnected_oks_ids?.length ?? 0)
        : null,
      routeFeatureCount: variant.lines.length,
      chamberCount: variant.chambers,
      tieInCount: variant.tieIns.length,
      newNetworkLengthM: variant.summary?.new_network_length ?? null,
      calculatedCostRub: variant.summary?.calculated_cost ?? null,
      graphComponentCount: topology?.componentCount ?? null,
      graphCycleCount: topology ? edges.length - nodes.size + topology.componentCount : null,
      maximumNodeDegree: maximumDegree,
      advisoryGeometrySimilarity: { referenceToCandidate, candidateToReference },
    };
  }).sort((left, right) => (left.rank ?? 99) - (right.rank ?? 99));
}

function formatReference(result) {
  const metrics = result.metrics;
  return [
    `Reference: ${result.valid ? "VALID" : "INVALID"}`,
    `  graph: ${metrics.graphNodeCount} nodes, ${metrics.logicalSectionCount} sections, `
      + `${metrics.connectedComponentCount} component, ${metrics.cycleCount} cycles`,
    `  demands: ${metrics.demandLeafCount}/${metrics.demandCount} leaves`,
    `  junctions: ${metrics.junctionMarkerCount}, max degree ${metrics.maximumJunctionDegree}`,
    `  root: degree ${metrics.rootDegree}, branch flows ${metrics.rootBranchFlowsTph.join(" + ")} t/h`,
    `  geometry: ${metrics.totalLengthM} m, max source-to-current attachment `
      + `${metrics.maximumAttachmentDistanceM} m`,
    ...result.issues.map((issue) => `  ERROR: ${issue}`),
  ].join("\n");
}

async function main() {
  const candidateArgument = process.argv[2];
  const root = process.cwd();
  const reference = await readJson(resolve(root, DEFAULT_REFERENCE));
  const officialInput = await readJson(resolve(root, DEFAULT_OFFICIAL_INPUT));
  const manifest = await readJson(resolve(root, DEFAULT_MANIFEST));
  const referenceResult = analyzeRoutingReference(reference, officialInput, manifest);
  process.stdout.write(`${formatReference(referenceResult)}\n`);
  if (!referenceResult.valid) process.exitCode = 1;
  if (candidateArgument) {
    const candidate = await readJson(resolve(root, candidateArgument));
    process.stdout.write(`${JSON.stringify(
      analyzeCandidate(candidate, reference, manifest.expected.demand_count),
      null,
      2,
    )}\n`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}
