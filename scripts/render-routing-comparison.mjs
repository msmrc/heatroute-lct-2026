#!/usr/bin/env node
/** Статическое сравнение исходных полилиний: единая метрическая рамка, без сглаживания. */
import { createHash } from "node:crypto";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { analyzeRoutingReference, projectEpsg32637 } from "./reference-routing-benchmark.mjs";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const MAX_INPUT_BYTES = 32 * 1024 * 1024; // Диагностические снимки, не потоковый импорт произвольных ГИС.
const PANEL = { width: 1120, height: 1144, map: { x: 24, y: 190, width: 1072, height: 816 } };
const COLOR = {
  page: "#ffffff", map: "#f5f7f8", ink: "#192936", muted: "#526271",
  building: "#dfe5e9", buildingEdge: "#a8b4bf", water: "#d8ebf4", railway: "#e3dde4",
  road: "#e9e9e4", network: "#b46760", route: "#bb7105", root: "#087e91",
};

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function xml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;",
  })[char]);
}

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const metricKey = (coordinate) => JSON.stringify(coordinate);
const formatLength = (value) => value.toLocaleString("ru-RU", {
  minimumFractionDigits: 3, maximumFractionDigits: 3,
});

function metricCoordinate(value) {
  assert(value && Number.isFinite(value.xm) && Number.isFinite(value.ym),
    "Result coordinates must contain finite xm/ym in EPSG:32637");
  return [value.xm, value.ym];
}

function projectCoordinate(value) {
  assert(Array.isArray(value) && value.length >= 2 && Number.isFinite(value[0])
    && Number.isFinite(value[1]) && Math.abs(value[0]) <= 180 && Math.abs(value[1]) <= 90,
  "GeoJSON coordinates must be finite WGS84 longitude/latitude");
  return projectEpsg32637(value);
}

function lineLength(line) {
  return line.slice(1).reduce((sum, p, i) => sum + Math.hypot(p[0] - line[i][0], p[1] - line[i][1]), 0);
}

/** Считает камеры по семантике узлов; корневые площадки и выходящие лучи — отдельно. */
export function candidateModel(bundle, variantSelector = "balanced") {
  const run = bundle.run ?? bundle;
  const result = run.result ?? run;
  const variantId = variantSelector === "best" ? result.preferred_variant_id : variantSelector;
  assert(variantId, "Cannot select best: result has no preferred_variant_id");
  const variant = result.variants?.find((item) => item.id === variantId);
  assert(variant, `Missing result variant: ${variantId}`);
  assert(Array.isArray(variant.nodes) && Array.isArray(variant.edges), "Variant requires nodes and edges");
  assert(Number.isFinite(variant.total_length_m), "Variant requires total_length_m");
  const nodes = variant.nodes.map((node) => ({ ...node, point: metricCoordinate(node.coordinate) }));
  const byId = new Map(nodes.map((node) => [node.id, node]));
  assert(byId.size === nodes.length, "Duplicate result node IDs");
  const lines = variant.edges.map((edge) => {
    assert(byId.has(edge.upstream_node_id) && byId.has(edge.downstream_node_id),
      `Unknown endpoint node in edge ${edge.id}`);
    assert(Array.isArray(edge.coordinates) && edge.coordinates.length >= 2, `Invalid edge ${edge.id}`);
    return { id: edge.id, coordinates: edge.coordinates.map(metricCoordinate) };
  });
  assert(lines.length > 0, "Cannot render an empty route");
  const roots = nodes.filter((node) => node.root);
  const rootIds = new Set(roots.map((node) => node.id));
  const counts = {};
  for (const node of nodes) counts[node.node_type] = (counts[node.node_type] ?? 0) + 1;
  const chamberCounts = {};
  for (const node of nodes.filter((item) => item.chamber)) {
    chamberCounts[node.node_type] = (chamberCounts[node.node_type] ?? 0) + 1;
  }
  const markers = nodes.filter((node) => node.root || node.chamber || node.node_type === "demand_connection").map((node) => ({
    id: node.id, point: node.point,
    kind: node.root ? "root" : node.chamber ? "branch" : "demand",
    label: node.root ? `${node.node_type === "existing_chamber_tie_in" ? "Существующая ТК" : "Новая ТК"} ${node.target_id ?? node.id}` : node.id,
  }));
  const developmentCandidate = result.algorithm_version === "corridor-development";
  return {
    kind: "candidate", title: developmentCandidate ? "HeatRoute · кандидат в разработке" : "HeatRoute · наш маршрут", lines, markers,
    subtitle: `${result.algorithm_version ?? run.algorithm_version ?? "версия не указана"} · ${variant.id} / ${variant.strategy}`,
    metrics: {
      development_candidate: developmentCandidate,
      variant_id: variant.id, strategy: variant.strategy,
      selection: { requested: variantSelector,
        resolved_by: variantSelector === "best" ? "result.preferred_variant_id" : "explicit_variant_id" },
      preferred_variant_id: result.preferred_variant_id ?? null,
      rank: variant.rank ?? null,
      // Только опубликованные economics: renderer не пересчитывает стоимость или конкурсный score.
      calculated_cost: variant.economics?.calculated_cost ?? null,
      score: variant.economics?.score ?? null,
      economics_complete: variant.economics?.complete ?? null,
      economics_incomplete_reasons: variant.economics?.incomplete_reasons ?? null,
      reported_length_m: variant.total_length_m,
      geometry_length_m: lines.reduce((sum, line) => sum + lineLength(line.coordinates), 0),
      line_count: lines.length, chamber_count: nodes.filter((node) => node.chamber).length,
      nodes_by_type: counts, chambers_by_type: chamberCounts,
      branch_chambers: counts.new_branch_chamber ?? 0,
      new_tie_in_chambers: counts.new_tie_in_chamber ?? 0,
      existing_root_chambers: roots.filter((node) => node.node_type === "existing_chamber_tie_in").length,
      root_sites: new Set(roots.map((node) => metricKey(node.point))).size,
      root_node_count: roots.length,
      root_rays: variant.edges.reduce((sum, edge) => sum
        + Number(rootIds.has(edge.upstream_node_id)) + Number(rootIds.has(edge.downstream_node_id)), 0),
      root_target_ids: roots.map((node) => node.target_id),
      connected_demands: variant.connected_demand_count,
      demand_count: result.demand_count ?? nodes.filter((node) => node.node_type === "demand_connection").length,
      valid: variant.valid, depth_enabled: run.parameters?.depth_enabled ?? null,
      validation_issue_count: variant.validation_issues?.length ?? null,
      engineering_issue_count: variant.engineering_issues?.length ?? null,
      sizing_issue_count: variant.sizing_issues?.length ?? null,
    },
  };
}

/** Происхождение проверяется по metadata; при переданном оригинале — также по байтам и всем геометриям. */
export function verifyReferenceProvenance(reference, manifest, original = null) {
  const provenance = reference.reference;
  assert(provenance?.id === manifest.id && provenance.kind === "expert_routing_geometry",
    "Reference metadata does not match the expert manifest");
  assert(provenance.source_filename && /^[a-f0-9]{64}$/i.test(provenance.source_sha256 ?? ""),
    "Reference has no source filename/SHA-256");
  const indices = reference.features.map((feature) => feature.properties?.source_feature_index);
  assert(indices.every((index) => Number.isInteger(index) && index >= 0)
    && new Set(indices).size === indices.length, "Invalid reference source feature indices");
  if (original) {
    assert(basename(original.path) === provenance.source_filename, "Original reference filename mismatch");
    assert(sha256(original.bytes) === provenance.source_sha256, "Original reference SHA-256 mismatch");
    const source = JSON.parse(original.bytes.toString("utf8"));
    assert(source.features?.length === provenance.source_feature_count, "Original feature count mismatch");
    for (const feature of reference.features) {
      assert(JSON.stringify(feature.geometry)
        === JSON.stringify(source.features[feature.properties.source_feature_index]?.geometry),
      `Reference geometry differs from original: ${feature.properties.id}`);
    }
  }
  return { ...provenance, verification: original ? "source_hash_and_all_geometries" : "repository_metadata_only",
    author_label_basis: "Евгений — атрибуция пользователя; metadata подтверждает исходный файл, не авторство" };
}

function referenceModel(reference, official, manifest) {
  // Benchmark сопоставляет узлы с допуском только для счётчиков. Рисуем исходные координаты, без snapping.
  const analysis = analyzeRoutingReference(reference, official, manifest);
  assert(analysis.valid, `Invalid reference contract: ${analysis.issues.join("; ")}`);
  const lines = reference.features.filter((f) => f.properties.reference_role === "route_centerline")
    .map((f) => ({ id: f.properties.id, coordinates: f.geometry.coordinates.map(projectCoordinate) }));
  const markers = reference.features.filter((f) => f.properties.reference_role === "junction_marker")
    .map((f) => ({ id: f.properties.id, kind: "branch", point: projectCoordinate(f.geometry.coordinates) }));
  for (const feature of official.features.filter((f) => f.properties.object_type === "oks_connection_point")) {
    markers.push({ id: `demand:${feature.properties.id}`, kind: "demand", point: projectCoordinate(feature.geometry.coordinates) });
  }
  const contract = manifest.root_existing_object;
  const root = official.features.find((f) => f.properties.object_type === contract.object_type
    && String(f.properties.id) === String(contract.id));
  markers.push({ id: `root:${contract.id}`, kind: "root", label: `Существующая ТК ${contract.id}`,
    point: projectCoordinate(root.geometry.coordinates) });
  return {
    kind: "reference", title: "Евгений · ручная разметка", lines, markers,
    subtitle: `${reference.reference.source_filename} · экспертный геометрический ориентир`,
    metrics: {
      reported_length_m: analysis.metrics.totalLengthM,
      geometry_length_m: lines.reduce((sum, line) => sum + lineLength(line.coordinates), 0),
      line_count: lines.length, branch_chambers: analysis.metrics.junctionMarkerCount,
      chamber_count: analysis.metrics.junctionMarkerCount + 1,
      existing_root_chambers: 1, root_sites: 1, root_rays: analysis.metrics.rootDegree,
      root_target_ids: [String(contract.id)], connected_demands: analysis.metrics.demandLeafCount,
      demand_count: analysis.metrics.demandCount,
      reference_contract: analysis.metrics,
    },
  };
}

/** Из общего охвата строится один изотропный affine-transform для всех трёх картинок. */
export function commonViewport(models, box = PANEL.map) {
  const points = models.flatMap((model) => [
    ...model.lines.flatMap((line) => line.coordinates), ...model.markers.map((marker) => marker.point),
  ]);
  assert(points.length > 0, "Cannot frame empty geometry");
  const bounds = points.reduce((b, [x, y]) => [Math.min(b[0], x), Math.min(b[1], y), Math.max(b[2], x), Math.max(b[3], y)],
    [Infinity, Infinity, -Infinity, -Infinity]);
  const paddingM = Math.max(20, Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * 0.065);
  const scale = Math.min(box.width / (bounds[2] - bounds[0] + 2 * paddingM),
    box.height / (bounds[3] - bounds[1] + 2 * paddingM));
  const centerX = (bounds[0] + bounds[2]) / 2;
  const centerY = (bounds[1] + bounds[3]) / 2;
  return { crs: "EPSG:32637", orientation: "grid north up", box, pixels_per_metre: scale,
    extent_m: [centerX - box.width / scale / 2, centerY - box.height / scale / 2,
      centerX + box.width / scale / 2, centerY + box.height / scale / 2],
    matrix: [scale, 0, 0, -scale, box.x + box.width / 2 - scale * centerX,
      box.y + box.height / 2 + scale * centerY],
  };
}

export function polylinePath(coordinates) {
  return coordinates.map(([x, y], i) => `${i ? "L" : "M"}${x} ${y}`).join(" ");
}

function geometryPath(geometry) {
  const line = (coordinates, closed = false) => polylinePath(coordinates.map(projectCoordinate)) + (closed ? " Z" : "");
  switch (geometry.type) {
    case "LineString": return line(geometry.coordinates);
    case "MultiLineString": return geometry.coordinates.map((coordinates) => line(coordinates)).join(" ");
    case "Polygon": return geometry.coordinates.map((coordinates) => line(coordinates, true)).join(" ");
    case "MultiPolygon": return geometry.coordinates.flatMap((rings) => rings.map((coordinates) => line(coordinates, true))).join(" ");
    default: throw new Error(`Unsupported basemap geometry: ${geometry.type}`);
  }
}

function basemap(official) {
  const paths = [];
  const counts = {};
  for (const feature of official.features) {
    const properties = feature.properties;
    const layer = properties.object_type === "restriction" ? properties.restriction_type : properties.object_type;
    counts[layer] = (counts[layer] ?? 0) + 1;
    if (!["oks", "water", "railway", "road", "heat_network"].includes(layer)) continue;
    const fill = { oks: COLOR.building, water: COLOR.water, railway: COLOR.railway, road: COLOR.road }[layer] ?? "none";
    const stroke = layer === "heat_network" ? COLOR.network : COLOR.buildingEdge;
    paths.push(`<path data-layer="${xml(layer)}" d="${geometryPath(feature.geometry)}" fill="${fill}" fill-rule="evenodd" stroke="${stroke}" stroke-width="${layer === "heat_network" ? 1.8 : 0.8}" vector-effect="non-scaling-stroke"/>`);
  }
  return { svg: paths.join("\n"), counts };
}

function screenPoint([x, y], viewport) {
  const [scale, , , negativeScale, dx, dy] = viewport.matrix;
  return [scale * x + dx, negativeScale * y + dy];
}

function markerSvg(kind, x, y, radius = 5) {
  if (kind === "root") return `<path d="M${x} ${y - 9} L${x + 9} ${y} L${x} ${y + 9} L${x - 9} ${y} Z" fill="${COLOR.root}" stroke="white" stroke-width="2"/>`;
  return `<circle cx="${x}" cy="${y}" r="${radius}" fill="${kind === "branch" ? COLOR.route : "white"}" stroke="${kind === "branch" ? COLOR.ink : COLOR.muted}" stroke-width="1.5"/>`;
}

function text(x, y, value, size = 18, color = COLOR.ink) {
  return `<text x="${x}" y="${y}" font-size="${size}" fill="${color}">${xml(value)}</text>`;
}

function panelSvg(model, viewport, background, prefix) {
  const m = model.metrics;
  const box = viewport.box;
  const routePaths = model.lines.map((line) => `<path data-route-id="${xml(line.id)}" d="${polylinePath(line.coordinates)}" fill="none" stroke="${COLOR.route}" stroke-width="3.2" stroke-linejoin="miter" stroke-linecap="butt" vector-effect="non-scaling-stroke"/>`).join("\n");
  const markers = model.markers.map((marker) => {
    const [x, y] = screenPoint(marker.point, viewport);
    return `<g data-marker-kind="${marker.kind}" data-marker-id="${xml(marker.id)}"><title>${xml(marker.label ?? marker.id)}</title>${markerSvg(marker.kind, x, y, marker.kind === "demand" ? 3.5 : 5.5)}</g>`;
  }).join("\n");
  const rootLabels = model.markers.filter((marker) => marker.kind === "root").map((marker) => {
    const [x, y] = screenPoint(marker.point, viewport);
    const alignRight = x > box.x + box.width * 0.65;
    return `<text x="${x + (alignRight ? -14 : 14)}" y="${y - 12}" text-anchor="${alignRight ? "end" : "start"}" font-size="17" fill="${COLOR.root}" stroke="white" stroke-width="4" paint-order="stroke">${xml(marker.label)}</text>`;
  }).join("\n");
  const scaleMax = 145 / viewport.pixels_per_metre;
  const power = 10 ** Math.floor(Math.log10(scaleMax));
  const scaleM = [1, 2, 5, 10].map((n) => n * power).filter((n) => n <= scaleMax).at(-1);
  const scalePixels = scaleM * viewport.pixels_per_metre;
  const chamberText = model.kind === "candidate"
    ? `Камеры: ${m.branch_chambers} новых узловых · ${m.new_tie_in_chambers} новых врезок · ${m.existing_root_chambers} существующих корневых`
    : `Камеры: ${m.branch_chambers} узловых маркеров · ${m.existing_root_chambers} существующая корневая`;
  return `<g font-family="Arial, DejaVu Sans, sans-serif">
${text(24, 44, model.title, 30)}
${text(24, 76, model.subtitle, 19, COLOR.muted)}
${text(24, 110, `Длина ${formatLength(m.reported_length_m)} м · ${m.line_count} полилиний · ОКС ${m.connected_demands}/${m.demand_count}`, 23)}
${text(24, 143, chamberText, 19)}
${text(24, 174, `Корневых площадок: ${m.root_sites} · Выходов из корней: ${m.root_rays}`, 19, COLOR.muted)}
<defs><clipPath id="${prefix}-map"><rect x="${box.x}" y="${box.y}" width="${box.width}" height="${box.height}"/></clipPath></defs>
<rect x="${box.x}" y="${box.y}" width="${box.width}" height="${box.height}" fill="${COLOR.map}"/>
<g clip-path="url(#${prefix}-map)"><g transform="matrix(${viewport.matrix.join(" ")})">${background.svg}\n${routePaths}</g>${markers}${rootLabels}</g>
<g transform="translate(${box.x + box.width - 82} ${box.y + 42})"><rect x="-56" y="-27" width="112" height="96" fill="white" opacity="0.9"/><path d="M0 35 L0 -2 M-6 7 L0 -2 L6 7" fill="none" stroke="${COLOR.ink}" stroke-width="2"/>${text(-6, -10, "N", 17)}${text(-46, 59, "Север сетки", 15)}</g>
<g transform="translate(${box.x + 22} ${box.y + box.height - 28})"><rect x="-10" y="-34" width="180" height="49" fill="white" opacity="0.94"/><path d="M0 -7 V0 H${scalePixels} V-7" fill="none" stroke="${COLOR.ink}" stroke-width="2"/>${text(0, -13, `${scaleM} м`, 17)}</g>
<path d="M24 1032 H56" stroke="${COLOR.route}" stroke-width="3.2"/>${text(66, 1038, "Маршрут", 17)}
${markerSvg("branch", 213, 1032)}${text(225, 1038, "Узловая камера", 17)}
${markerSvg("root", 429, 1032)}${text(445, 1038, "Корень / врезка", 17)}
${markerSvg("demand", 646, 1032, 3.5)}${text(658, 1038, "Точка ОКС", 17)}
<rect x="818" y="1024" width="18" height="14" fill="${COLOR.building}" stroke="${COLOR.buildingEdge}"/>${text(846, 1038, "Здание", 17)}
<path d="M24 1064 H56" stroke="${COLOR.network}" stroke-width="1.8"/>${text(66, 1070, "Существующая теплосеть", 17)}
${text(430, 1070, "EPSG:32637 · одинаковый масштаб и охват", 17, COLOR.muted)}
${text(24, 1100, background.counts.road ? "Фон: официальный набор; только исходная геометрия." : "Фон: официальный набор. Дорожного слоя нет; дороги не дорисованы.", 17, COLOR.muted)}
${text(24, 1128, model.kind === "reference" ? "Линии без snapping. Корень и точки ОКС — из официального набора; это не инженерная приёмка." : "Исходные вершины результата без сглаживания; подписи не заменяют инженерную валидацию.", 16, COLOR.muted)}
</g>`;
}

export function buildComparison(bundle, official, reference, manifest, provenance, inputs = {}, variantSelector = "balanced") {
  const candidate = candidateModel(bundle, variantSelector);
  assert(/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(candidate.metrics.variant_id), "Unsafe variant ID for output filename");
  const expert = referenceModel(reference, official, manifest);
  const viewport = commonViewport([candidate, expert]);
  const background = basemap(official);
  const metadata = {
    schema: "heatroute-routing-comparison-v1", viewport,
    geometry_policy: "Original vertices; straight line segments; no smoothing, snapping or inferred roads",
    reference_provenance: provenance, inputs, basemap_layer_counts: background.counts,
    candidate: { subtitle: candidate.subtitle, ...candidate.metrics }, reference: expert.metrics,
  };
  const makeSvg = (models) => {
    const width = PANEL.width * models.length;
    return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${PANEL.height}" viewBox="0 0 ${width} ${PANEL.height}" role="img">
<title>${xml(models.map((model) => model.title).join(" / "))}</title>
<desc>Геометрическое сравнение на одинаковом метрическом охвате. Корни выделены ромбом, узловые камеры кругом.</desc>
<metadata>${xml(JSON.stringify(metadata))}</metadata>
<rect width="${width}" height="${PANEL.height}" fill="${COLOR.page}"/>
${models.map((model, index) => `<g transform="translate(${index * PANEL.width} 0)">${panelSvg(model, viewport, background, `panel-${index}`)}</g>`).join("\n")}
</svg>\n`;
  };
  return { metadata, svgs: { [`heatroute-${candidate.metrics.variant_id}`]: makeSvg([candidate]),
    "evgeny-reference": makeSvg([expert]), "side-by-side": makeSvg([candidate, expert]) } };
}

async function readInput(path) {
  assert((await stat(path)).size <= MAX_INPUT_BYTES, `Input exceeds diagnostic limit of 32 MiB: ${path}`);
  const bytes = await readFile(path);
  return { path, bytes, data: JSON.parse(bytes.toString("utf8")), sha256: sha256(bytes) };
}

function options(argv) {
  const parsed = { official: "datasets/official/lct-2026.geojson",
    reference: "datasets/reference/professional-routing-01.geojson", manifest: "datasets/reference/professional-routing-01.reference.json",
    "out-dir": ".tooling/routing-comparison-48", variant: "balanced" };
  const valueKeys = new Set([...Object.keys(parsed), "result", "reference-source", "sharp-module"]);
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i].replace(/^--/, "");
    if (key === "svg-only" || key === "help") parsed[key] = true;
    else {
      assert(argv[i].startsWith("--") && valueKeys.has(key) && argv[i + 1] && !argv[i + 1].startsWith("--"),
        `Unknown option or missing value: ${argv[i]}`);
      parsed[key] = argv[++i];
    }
  }
  return parsed;
}

function loadSharp(modulePath) {
  const require = createRequire(import.meta.url);
  if (modulePath) return require(resolve(modulePath));
  for (const candidate of ["sharp", resolve(dirname(process.execPath), "../node_modules/sharp")]) {
    try { return require(candidate); } catch (error) { if (error.code !== "MODULE_NOT_FOUND") throw error; }
  }
  throw new Error("PNG rendering requires existing sharp; use --sharp-module /path/to/sharp or --svg-only. No dependency was installed.");
}

export async function main(argv = process.argv.slice(2)) {
  const args = options(argv);
  if (args.help) {
    console.log("node scripts/render-routing-comparison.mjs --result bundle.json [--out-dir .tooling/routing-comparison-48]\n"
      + "  [--official dataset.geojson] [--reference reference.geojson] [--manifest reference.json]\n"
      + "  [--variant balanced|best|<variant-id>]  (best resolves result.preferred_variant_id; default: balanced)\n"
      + "  [--reference-source /read-only/path/1.geojson] [--sharp-module /existing/sharp] [--svg-only]\n"
      + "Relative input/output paths are resolved from the repository root. PNG uses existing sharp only.");
    return;
  }
  assert(typeof args.result === "string" && args.result.trim().length > 0,
    "Missing required option: --result <bundle.json>. No default result is selected; use --help for usage.");
  const [result, official, reference, manifest, original] = await Promise.all(
    ["result", "official", "reference", "manifest", "reference-source"].map((key) => args[key]
      ? readInput(resolve(REPO_ROOT, args[key])) : null));
  assert(official.sha256 === manifest.data.official_input_sha256, "Official dataset SHA-256 differs from reference manifest");
  const run = result.data.run ?? result.data;
  assert(run.input_sha256 === official.sha256, "Result input SHA-256 differs from the basemap dataset");
  const provenance = verifyReferenceProvenance(reference.data, manifest.data, original);
  const inputs = Object.fromEntries([result, official, reference, manifest, original].filter(Boolean)
    .map((input) => [input.path, { sha256: input.sha256, bytes: input.bytes.length }]));
  const rendered = buildComparison(result.data, official.data, reference.data, manifest.data, provenance, inputs, args.variant);
  const outputDirectory = resolve(REPO_ROOT, args["out-dir"]);
  const sharp = args["svg-only"] ? null : loadSharp(args["sharp-module"]);
  // Явно сохраняем fingerprint: каталог может называться «48», пока внутри ещё результат версии 47.
  rendered.metadata.outputs = [];
  await mkdir(outputDirectory, { recursive: true });
  for (const [name, svg] of Object.entries(rendered.svgs)) {
    const svgPath = resolve(outputDirectory, `${name}.svg`);
    await writeFile(svgPath, svg);
    rendered.metadata.outputs.push(svgPath);
    if (sharp) {
      const pngPath = resolve(outputDirectory, `${name}.png`);
      await sharp(Buffer.from(svg)).png().toFile(pngPath);
      rendered.metadata.outputs.push(pngPath);
    }
  }
  const metadataPath = resolve(outputDirectory, "comparison-metadata.json");
  await writeFile(metadataPath, `${JSON.stringify(rendered.metadata, null, 2)}\n`);
  console.log(JSON.stringify({ algorithm: rendered.metadata.candidate.subtitle,
    candidate: rendered.metadata.candidate, reference: rendered.metadata.reference,
    provenance_verification: provenance.verification, outputs: [...rendered.metadata.outputs, metadataPath] }, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
