import { Activity, AlertTriangle, CheckCircle2, Focus, MapPin, MapPinned, Network, X, ZoomIn, ZoomOut } from "lucide-react";
import { lazy, Suspense, useCallback, useMemo, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";

import type {
  OfficialCalculationIssue,
  OfficialCalculationResult,
  OfficialInputWarning,
  OfficialRouteEdge,
  OfficialRouteNode,
  OfficialRouteVariant,
} from "../../shared/api";
import { Button } from "../ui/button";
import { Badge, Dialog } from "../ui/primitives";
import type { SelectedMapObject } from "./OfficialRouteMap";

const OfficialRouteMap = lazy(async () => {
  const module = await import("./OfficialRouteMap");
  return { default: module.OfficialRouteMap };
});

const CANVAS_WIDTH = 1000;
const CANVAS_HEIGHT = 590;
const CANVAS_PADDING = 58;
const EMPTY_WARNINGS: OfficialInputWarning[] = [];

function formatLength(value: number): string {
  return value >= 1_000
    ? `${(value / 1_000).toLocaleString("ru-RU", { maximumFractionDigits: 2 })} км`
    : `${Math.round(value).toLocaleString("ru-RU")} м`;
}

function formatMoney(value: number): string {
  if (value >= 1_000_000_000) {
    return `${(value / 1_000_000_000).toLocaleString("ru-RU", { maximumFractionDigits: 2 })} млрд ₽`;
  }
  return `${(value / 1_000_000).toLocaleString("ru-RU", { maximumFractionDigits: 1 })} млн ₽`;
}

function russianCount(value: number, one: string, few: string, many: string): string {
  const modulo100 = value % 100;
  const modulo10 = value % 10;
  const word = modulo100 >= 11 && modulo100 <= 14
    ? many
    : modulo10 === 1
      ? one
      : modulo10 >= 2 && modulo10 <= 4
        ? few
        : many;
  return `${value} ${word}`;
}

function errorCount(value: number): string {
  return russianCount(value, "ошибка", "ошибки", "ошибок");
}

function warningCount(value: number): string {
  return russianCount(value, "предупреждение", "предупреждения", "предупреждений");
}

function objectCount(value: number): string {
  return russianCount(value, "объект", "объекта", "объектов");
}

interface InputWarningGroup {
  key: string;
  title: string;
  message: string;
  count: number;
  exampleIds: string[];
  sample: OfficialInputWarning;
}

function inputWarningCopy(warning: OfficialInputWarning): { title: string; message: string } {
  if (warning.code === "MISSING_EXISTING_NETWORK_LINK") {
    return {
      title: "Нет направления существующей сети",
      message: "В исходном файле отсутствует upstream_object_id. Топология восстанавливается по геометрии, но официальный расчёт реконструкции остаётся недоступен.",
    };
  }
  if (warning.code === "MISSING_EXISTING_NETWORK_VALUE") {
    return {
      title: "Нет расходов существующей сети",
      message: "В исходном файле отсутствует flow_tph, поэтому система не подменяет данные и не публикует финальную реконструкцию.",
    };
  }
  if (warning.code === "MISSING_CHAMBER_DIAMETER") {
    return {
      title: "Не указаны диаметры камер",
      message: "Диаметр камеры отсутствует в исходном файле и может быть определён только по связанным участкам сети.",
    };
  }
  return {
    title: warning.code.replaceAll("_", " ").toLocaleLowerCase("ru-RU"),
    message: warning.message,
  };
}

function groupInputWarnings(warnings: OfficialInputWarning[]): InputWarningGroup[] {
  const groups = new Map<string, InputWarningGroup>();
  warnings.forEach((warning) => {
    const key = `${warning.code}\u0000${warning.message}`;
    const existing = groups.get(key);
    if (existing) {
      existing.count += 1;
      if (warning.feature_id && existing.exampleIds.length < 5 && !existing.exampleIds.includes(warning.feature_id)) {
        existing.exampleIds.push(warning.feature_id);
      }
      return;
    }
    const copy = inputWarningCopy(warning);
    groups.set(key, {
      key,
      ...copy,
      count: 1,
      exampleIds: warning.feature_id ? [warning.feature_id] : [],
      sample: warning,
    });
  });
  return [...groups.values()].sort((left, right) => right.count - left.count || left.title.localeCompare(right.title, "ru"));
}

function variantName(variant: OfficialRouteVariant): string {
  if (variant.strategy === "balanced") return "Оптимальный баланс";
  if (variant.strategy === "cheapest") return "Минимальная стоимость";
  if (variant.strategy === "shortest") return "Минимальная длина";
  return "Вариант сети";
}

function nodeName(node: OfficialRouteNode): string {
  if (node.node_type === "demand_connection") return `ОКС ${node.target_id ?? "—"}`;
  if (node.node_type === "new_branch_chamber") return "Новая камера ветвления";
  if (node.node_type === "new_tie_in_chamber") return `Новая камера врезки · ${node.target_id ?? "—"}`;
  if (node.node_type === "existing_chamber_tie_in") return `Существующая камера · ${node.target_id ?? "—"}`;
  return node.node_type.replaceAll("_", " ");
}

function nodeTone(node: OfficialRouteNode): string {
  if (node.node_type === "demand_connection") return "var(--route-demand)";
  if (node.root) return "var(--route-source)";
  return "var(--route-chamber)";
}

function noRouteReason(reason?: string): string {
  if (reason === "NO_NON_CROSSING_ROUTE") return "Не удалось построить трассу без пересечения ограничений";
  if (reason === "NO_ROUTE") return "Маршрут не найден";
  if (!reason) return "Маршрут не найден";
  return reason.replaceAll("_", " ").toLocaleLowerCase("ru-RU");
}

function noRouteDiagnostics(connection: OfficialCalculationResult["variants"][number]["connections"][number]): string {
  const diagnostics = connection.diagnostics;
  if (!diagnostics) return "";
  const checked = `проверено точек врезки: ${diagnostics.attempted_candidate_count} из ${diagnostics.candidate_count}`;
  const blockers = diagnostics.direct_blockers.length > 0
    ? `; прямой путь блокируют: ${diagnostics.direct_blockers.join(", ")}`
    : "";
  return ` (${checked}, коридор поиска до ${diagnostics.maximum_search_corridor_m} м${blockers})`;
}

function calculationIssueTitle(issue: OfficialCalculationIssue): string {
  if (issue.code === "MAX_CONTINUOUS_LENGTH_EXCEEDED") return "Превышена предельная длина";
  if (issue.code === "FLOW_EXCEEDS_CATALOG") return "Расход выше диапазона диаметров";
  if (issue.code === "RECONSTRUCTION_INPUT_UNAVAILABLE") return "Недостаточно данных для реконструкции";
  if (issue.code === "RECONSTRUCTION_FLOW_EXCEEDS_CATALOG") return "Расход реконструкции выше диапазона диаметров";
  return issue.code.replaceAll("_", " ").toLocaleLowerCase("ru-RU");
}

function calculationIssueMessage(issue: OfficialCalculationIssue): string {
  if (issue.code === "RECONSTRUCTION_INPUT_UNAVAILABLE") {
    return "В исходном наборе нет расхода существующей сети или направления к источнику. Результат реконструкции не рассчитывался.";
  }
  const continuousLength = issue.message.match(/^Continuous DU (\d+) length ([\d.]+) m exceeds ([\d.]+) m$/);
  if (continuousLength) {
    const [, diameter, actual, limit] = continuousLength;
    return `Непрерывный участок ДУ ${diameter}: ${Number(actual).toLocaleString("ru-RU")} м при допустимых ${Number(limit).toLocaleString("ru-RU")} м.`;
  }
  return issue.message;
}

function depthIssueMessage(code: string, fallback: string): string {
  if (code === "NO_VERTICAL_PASSAGE") return "На участке недостаточно длины для безопасного уклона при заданном диапазоне глубин.";
  if (code === "VERTICAL_TRANSITIONS_OVERLAP") return "Зоны уклонов соседних пересечений накладываются друг на друга.";
  if (code === "EXISTING_HEAT_DIAMETER_MISSING") return "Для пересекаемой теплосети не указан диаметр, поэтому её габарит нельзя определить.";
  if (code === "CROSSING_OUTSIDE_EDGE") return "Пикет пересечения находится за пределами рассчитанного участка.";
  return fallback;
}

function RouteNodeGlyph({ node, x, y, selected, onSelect }: {
  node: OfficialRouteNode;
  x: number;
  y: number;
  selected: boolean;
  onSelect: () => void;
}) {
  const color = nodeTone(node);
  const label = nodeName(node);
  const common = {
    fill: color,
    stroke: selected ? "var(--foreground)" : "var(--surface)",
    strokeWidth: selected ? 4 : 3,
  };

  return (
    <g className="route-node" role="button" tabIndex={0} aria-label={label} onClick={onSelect}
      onKeyDown={(event) => (event.key === "Enter" || event.key === " ") && onSelect()}>
      <title>{label}</title>
      {selected && <circle cx={x} cy={y} r={18} fill="none" stroke={color} strokeOpacity=".25" strokeWidth="6" />}
      {node.node_type === "demand_connection" ? (
        <circle cx={x} cy={y} r={8} {...common} />
      ) : node.root ? (
        <rect x={x - 9} y={y - 9} width={18} height={18} rx={5} {...common} />
      ) : (
        <rect x={x - 8} y={y - 8} width={16} height={16} rx={3} transform={`rotate(45 ${x} ${y})`} {...common} />
      )}
      {node.node_type === "demand_connection" && (
        <text x={x} y={y - 14} textAnchor="middle" className="route-node__label">{node.target_id}</text>
      )}
    </g>
  );
}

function crossingName(type: string): string {
  if (type === "gas_pipeline") return "Газопровод";
  if (type === "power_cable") return "Силовой кабель";
  if (type === "heat_network") return "Тепловая сеть";
  return type.replaceAll("_", " ");
}

function DepthProfileView({ edges, selectedId, onSelect }: {
  edges: OfficialRouteEdge[];
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  const edge = edges.find((item) => item.id === selectedId) ?? edges[0];
  const profile = edge?.depth_profile;
  if (!edge || !profile || profile.points.length < 2) {
    return <div className="depth-profile-empty"><Activity size={24} /><strong>Продольный профиль недоступен</strong><p>Для выбранного варианта нет рассчитанных высотных данных.</p></div>;
  }
  const width = 1000;
  const height = 590;
  const left = 76;
  const right = 42;
  const top = 104;
  const bottom = 88;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const maximumDepth = Math.max(4, ...profile.points.map((point) => point.depth_m)) + .5;
  const x = (station: number) => left + (station / Math.max(edge.length_m, 1)) * plotWidth;
  const y = (depth: number) => top + (depth / maximumDepth) * plotHeight;
  const line = profile.points.map((point) => `${x(point.station_m)},${y(point.depth_m)}`).join(" ");
  const area = `${left},${top} ${line} ${x(edge.length_m)},${top}`;
  const depthTicks = Array.from({ length: Math.floor(maximumDepth) + 1 }, (_, index) => index);
  const deepest = Math.max(...profile.points.map((point) => point.depth_m));
  const shallowest = Math.min(...profile.points.map((point) => point.depth_m));
  const profileLength3d = profile.profile_length_3d_m ?? profile.profile_length3d_m ?? edge.length_m;

  return (
    <div className="depth-profile-view">
      <header className="depth-profile-header">
        <div><span>Вертикальная трассировка</span><strong>Продольный профиль</strong></div>
        <label>
          <span>Участок</span>
          <select value={edge.id} onChange={(event) => onSelect(event.target.value)}>
            {edges.map((item, index) => <option value={item.id} key={item.id}>Участок {index + 1} · {formatLength(item.length_m)} · ДУ {item.diameter ?? "—"}</option>)}
          </select>
        </label>
      </header>
      <div className="depth-profile-chart">
        <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`Продольный профиль участка ${edge.id}`}>
          <defs>
            <linearGradient id="depth-profile-fill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="var(--accent)" stopOpacity=".04" />
              <stop offset="1" stopColor="var(--accent)" stopOpacity=".18" />
            </linearGradient>
          </defs>
          <rect x={left} y={top} width={plotWidth} height={plotHeight} rx="12" className="depth-profile-plot" />
          {depthTicks.map((depth) => <g key={depth}>
            <line x1={left} x2={width - right} y1={y(depth)} y2={y(depth)} className={depth === 0 ? "depth-profile-surface" : "depth-profile-grid"} />
            <text x={left - 13} y={y(depth) + 4} textAnchor="end" className="depth-profile-axis-label">{depth} м</text>
          </g>)}
          {profile.crossings.map((crossing, index) => (
            <g key={crossing.crossing_id}>
              <rect x={x(crossing.plateau_start_m)} y={top} width={Math.max(3, x(crossing.plateau_end_m) - x(crossing.plateau_start_m))} height={plotHeight} className="depth-profile-crossing-zone" />
              <line x1={x((crossing.plateau_start_m + crossing.plateau_end_m) / 2)} x2={x((crossing.plateau_start_m + crossing.plateau_end_m) / 2)} y1={top} y2={height - bottom} className="depth-profile-crossing-line" />
              <text x={x((crossing.plateau_start_m + crossing.plateau_end_m) / 2)} y={top - 18 - (index % 2) * 18} textAnchor="middle" className="depth-profile-crossing-label">{crossingName(crossing.crossing_type)}</text>
            </g>
          ))}
          <polygon points={area} fill="url(#depth-profile-fill)" />
          <polyline points={line} className="depth-profile-line" />
          {profile.points.map((point) => <circle key={`${point.station_m}-${point.depth_m}`} cx={x(point.station_m)} cy={y(point.depth_m)} r="4" className="depth-profile-point" />)}
          <text x={left} y={height - 44} className="depth-profile-axis-label">0 м</text>
          <text x={width - right} y={height - 44} textAnchor="end" className="depth-profile-axis-label">{formatLength(edge.length_m)}</text>
        </svg>
      </div>
      <footer className="depth-profile-summary">
        <div><span>Глубина</span><strong>{shallowest.toLocaleString("ru-RU")}–{deepest.toLocaleString("ru-RU")} м</strong></div>
        <div><span>Пересечения</span><strong>{profile.crossings.length}</strong></div>
        <div><span>Длина в 3D</span><strong>{formatLength(profileLength3d)}</strong></div>
        <div className={profile.complete ? "is-success" : "is-danger"}><span>Проверка профиля</span><strong>{profile.complete ? "Пройдена" : "Требует решения"}</strong></div>
      </footer>
    </div>
  );
}

export function RouteVisualization({
  result,
  runId,
  importId,
  warnings = EMPTY_WARNINGS,
}: {
  result: OfficialCalculationResult;
  runId: string;
  importId: string;
  warnings?: OfficialInputWarning[];
}) {
  const defaultVariant = result.variants.find((item) => item.id === result.preferred_variant_id) ?? result.variants[0];
  const [variantId, setVariantId] = useState(defaultVariant?.id ?? "");
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedObject, setSelectedObject] = useState<SelectedMapObject | null>(null);
  const [zoom, setZoom] = useState(1);
  const [viewMode, setViewMode] = useState<"map" | "schematic" | "profile">("map");
  const [depthEdgeId, setDepthEdgeId] = useState("");
  const [validationDialogOpen, setValidationDialogOpen] = useState(false);
  const variant = result.variants.find((item) => item.id === variantId) ?? defaultVariant;

  const layout = useMemo(() => {
    if (!variant || variant.nodes.length === 0) return null;
    const xs = variant.nodes.map((node) => node.coordinate.xm);
    const ys = variant.nodes.map((node) => node.coordinate.ym);
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const rangeX = Math.max(maxX - minX, 1);
    const rangeY = Math.max(maxY - minY, 1);
    const scale = Math.min(
      (CANVAS_WIDTH - CANVAS_PADDING * 2) / rangeX,
      (CANVAS_HEIGHT - CANVAS_PADDING * 2) / rangeY,
    );
    const renderedWidth = rangeX * scale;
    const renderedHeight = rangeY * scale;
    const offsetX = (CANVAS_WIDTH - renderedWidth) / 2;
    const offsetY = (CANVAS_HEIGHT - renderedHeight) / 2;
    const points = new Map(variant.nodes.map((node) => [node.id, {
      x: offsetX + (node.coordinate.xm - minX) * scale,
      y: offsetY + (maxY - node.coordinate.ym) * scale,
    }]));
    return { points };
  }, [variant]);

  const selectMapObject = useCallback((object: SelectedMapObject | null) => {
    setSelectedObject(object);
    setSelectedNodeId(null);
  }, []);
  const inputWarningGroups = useMemo(() => groupInputWarnings(warnings), [warnings]);

  if (!variant || !layout) return null;

  const noRoute = variant.connections.filter((connection) => connection.status === "no_route");
  const reconstructionIssues = variant.reconstruction?.issues ?? [];
  const reconstructionWarnings = reconstructionIssues.filter((issue) => issue.code === "RECONSTRUCTION_INPUT_UNAVAILABLE");
  const primaryReconstructionWarning = reconstructionWarnings[0];
  const calculationIssues = [
    ...variant.validation_issues,
    ...(variant.sizing_issues ?? []),
    ...reconstructionIssues.filter((issue) => issue.code !== "RECONSTRUCTION_INPUT_UNAVAILABLE"),
  ];
  const depthWarnings = variant.edges.flatMap((edge) => (edge.depth_profile?.issues ?? []).map((issue) => ({
    ...issue,
    edgeId: edge.id,
  })));
  const calculationValid = variant.valid && calculationIssues.length === 0;
  const totalWarningCount = warnings.length + depthWarnings.length + (reconstructionWarnings.length > 0 ? 1 : 0);
  const independent = result.variants.find((item) => item.strategy === "shortest");
  const shared = result.variants.find((item) => item.strategy === "balanced");
  const depthEdges = variant.edges
    .filter((edge) => edge.depth_profile)
    .sort((left, right) => (right.depth_profile?.crossings.length ?? 0) - (left.depth_profile?.crossings.length ?? 0));
  const activeDepthEdgeId = depthEdges.some((edge) => edge.id === depthEdgeId)
    ? depthEdgeId
    : depthEdges[0]?.id ?? "";

  function changeVariant(id: string) {
    setVariantId(id);
    setSelectedNodeId(null);
    setSelectedObject(null);
    setZoom(1);
  }

  function handleVariantTabKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>, index: number) {
    let targetIndex: number | undefined;
    if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
      targetIndex = (index - 1 + result.variants.length) % result.variants.length;
    } else if (event.key === "ArrowRight" || event.key === "ArrowDown") {
      targetIndex = (index + 1) % result.variants.length;
    } else if (event.key === "Home") {
      targetIndex = 0;
    } else if (event.key === "End") {
      targetIndex = result.variants.length - 1;
    }
    if (targetIndex === undefined) return;

    event.preventDefault();
    const targetVariant = result.variants[targetIndex];
    if (!targetVariant) return;
    changeVariant(targetVariant.id);
    document.getElementById(`route-variant-tab-${targetVariant.id}`)?.focus();
  }

  function selectSchematicNode(node: OfficialRouteNode) {
    setSelectedNodeId(node.id);
    setSelectedObject({
      title: nodeName(node),
      subtitle: "Узел расчётной схемы",
      details: [
        ["X", `${node.coordinate.xm.toLocaleString("ru-RU")} м`],
        ["Y", `${node.coordinate.ym.toLocaleString("ru-RU")} м`],
      ],
    });
  }

  function clearSelection() {
    setSelectedNodeId(null);
    setSelectedObject(null);
  }

  return (
    <section className={`route-workspace is-${viewMode}`} aria-label="Визуализация рассчитанных маршрутов">
      <div className="route-map-stage">
          <div className="route-variant-tabs route-variant-tabs--floating" role="tablist" aria-label="Варианты маршрута">
            {result.variants.map((item, index) => (
              <button type="button" role="tab" aria-selected={item.id === variant.id}
                id={`route-variant-tab-${item.id}`} tabIndex={item.id === variant.id ? 0 : -1}
                className={item.id === variant.id ? "is-active" : undefined} key={item.id}
                onClick={() => changeVariant(item.id)} onKeyDown={(event) => handleVariantTabKeyDown(event, index)}>
                <span>{variantName(item)}</span>
                <small>{formatLength(item.total_length_m)}</small>
                {item.rank ? <i>место {item.rank}</i> : item.id === result.preferred_variant_id && <i>рекомендуем</i>}
              </button>
            ))}
          </div>

          <div className="route-map-view-controls">
            <div className="route-view-switch" role="group" aria-label="Режим визуализации">
              <button type="button" aria-pressed={viewMode === "map"} className={viewMode === "map" ? "is-active" : undefined} onClick={() => setViewMode("map")}><MapPinned size={15} /> Карта</button>
              <button type="button" aria-pressed={viewMode === "schematic"} className={viewMode === "schematic" ? "is-active" : undefined} onClick={() => setViewMode("schematic")}><Network size={15} /> Схема</button>
              <button type="button" aria-pressed={viewMode === "profile"} className={viewMode === "profile" ? "is-active" : undefined} onClick={() => setViewMode("profile")}><Activity size={15} /> Профиль</button>
            </div>
            {viewMode === "schematic" && (
              <div className="route-zoom-controls">
                <Button variant="ghost" aria-label="Уменьшить" onClick={() => setZoom((value) => Math.max(.75, value - .25))}><ZoomOut size={16} /></Button>
                <span>{Math.round(zoom * 100)}%</span>
                <Button variant="ghost" aria-label="Увеличить" onClick={() => setZoom((value) => Math.min(2.5, value + .25))}><ZoomIn size={16} /></Button>
                <Button variant="ghost" aria-label="Показать всю схему" onClick={() => setZoom(1)}><Focus size={16} /></Button>
              </div>
            )}
          </div>

          {viewMode === "map" ? (
            <Suspense fallback={<div className="official-map-shell official-map-loading">Загружаем карту…</div>}>
              <OfficialRouteMap runId={runId} importId={importId} variant={variant} onSelect={selectMapObject} />
            </Suspense>
          ) : viewMode === "profile" ? (
            <DepthProfileView edges={depthEdges} selectedId={activeDepthEdgeId} onSelect={setDepthEdgeId} />
          ) : (
            <div className="route-canvas-wrap route-canvas-wrap--workspace">
              <svg className="route-canvas" viewBox={`0 0 ${CANVAS_WIDTH} ${CANVAS_HEIGHT}`} role="img" aria-label={`${variantName(variant)}, ${variant.edges.length} участков`}>
                <defs>
                  <pattern id="route-grid-small" width="25" height="25" patternUnits="userSpaceOnUse">
                    <path d="M 25 0 L 0 0 0 25" fill="none" stroke="var(--route-grid)" strokeWidth="1" />
                  </pattern>
                  <pattern id="route-grid" width="100" height="100" patternUnits="userSpaceOnUse">
                    <rect width="100" height="100" fill="url(#route-grid-small)" />
                    <path d="M 100 0 L 0 0 0 100" fill="none" stroke="var(--route-grid-strong)" strokeWidth="1" />
                  </pattern>
                </defs>
                <rect width={CANVAS_WIDTH} height={CANVAS_HEIGHT} fill="url(#route-grid)" />
                <g transform={`translate(${CANVAS_WIDTH / 2} ${CANVAS_HEIGHT / 2}) scale(${zoom}) translate(${-CANVAS_WIDTH / 2} ${-CANVAS_HEIGHT / 2})`}>
                  {variant.edges.map((edge) => {
                    const from = layout.points.get(edge.upstream_node_id);
                    const to = layout.points.get(edge.downstream_node_id);
                    if (!from || !to) return null;
                    const trunk = edge.id.includes(":trunk:");
                    return (
                      <g key={edge.id} className="route-edge">
                        <title>{`${edge.id} · ${formatLength(edge.length_m)}`}</title>
                        <line x1={from.x} y1={from.y} x2={to.x} y2={to.y} className="route-edge__halo" />
                        <line x1={from.x} y1={from.y} x2={to.x} y2={to.y} className={trunk ? "route-edge__line is-trunk" : "route-edge__line"} />
                      </g>
                    );
                  })}
                  {variant.nodes.map((node) => {
                    const point = layout.points.get(node.id);
                    if (!point) return null;
                    return <RouteNodeGlyph key={node.id} node={node} x={point.x} y={point.y} selected={node.id === selectedNodeId} onSelect={() => selectSchematicNode(node)} />;
                  })}
                </g>
              </svg>
              <div className="route-legend" aria-label="Легенда">
                <span><i className="is-demand" /> ОКС</span><span><i className="is-tie" /> Врезка</span>
                <span><i className="is-chamber" /> Камера</span><span><b /> Трасса</span>
              </div>
            </div>
          )}
        <aside className="route-workspace-inspector" aria-label="Информация о выбранном объекте">
          <header>
            <div><span>{selectedObject ? "Выбранный объект" : "Текущий вариант"}</span><h2>{selectedObject?.title ?? variantName(variant)}</h2></div>
            {selectedObject && <button type="button" aria-label="Закрыть карточку объекта" onClick={clearSelection}><X size={18} /></button>}
          </header>

          {selectedObject ? (
            <>
              <div className="route-inspector-kind"><MapPin size={16} /> {selectedObject.subtitle}</div>
              {selectedObject.details.length > 0 ? (
                <dl className="route-inspector-list">
                  {selectedObject.details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
                </dl>
              ) : <p className="route-inspector-empty">Для объекта нет дополнительных атрибутов.</p>}
            </>
          ) : (
            <>
              {variant.id === result.preferred_variant_id && <Badge tone="success">Рекомендуемый вариант</Badge>}
              <p className="route-inspector-summary">{
                variant.strategy === "balanced"
                  ? "Баланс 70% стоимости и 30% длины среди допустимых вариантов общей сети."
                  : variant.strategy === "cheapest"
                    ? "Вариант с приоритетом минимальной итоговой стоимости."
                    : "Вариант с приоритетом минимальной суммарной длины новой сети."
              }</p>
              <dl className="route-inspector-list">
                <div><dt>Длина</dt><dd>{formatLength(variant.total_length_m)}</dd></div>
                <div><dt>Подключено</dt><dd>{variant.connected_demand_count} из {result.demand_count} ОКС</dd></div>
                <div><dt>Участков</dt><dd>{variant.edges.length}</dd></div>
                <div><dt>Камер и врезок</dt><dd>{variant.nodes.filter((node) => node.chamber).length}</dd></div>
                <div><dt>{variant.economics?.complete ? "Стоимость" : "Известная стоимость"}</dt><dd>{variant.economics ? formatMoney(variant.economics.calculated_cost) : "—"}</dd></div>
                <div><dt>Итоговый показатель</dt><dd>{variant.economics?.score != null ? variant.economics.score.toLocaleString("ru-RU", { maximumFractionDigits: 3 }) : "Нужны данные реконструкции"}</dd></div>
              </dl>
            </>
          )}

          {noRoute.length > 0 && (
            <div className="route-inspector-warning"><AlertTriangle size={18} /><div><strong>Есть неподключённые объекты</strong><p>{noRoute.map((connection) => `ОКС ${connection.demand_id}: ${noRouteReason(connection.reason)}${noRouteDiagnostics(connection)}`).join(" · ")}</p></div></div>
          )}
          <div className="route-inspector-hint">Нажмите на трассу или объект на карте, чтобы увидеть его данные.</div>
        </aside>

        <footer className="route-results-drawer">
          <header><strong>Результаты расчёта</strong><span>{variantName(variant)}</span></header>
          <div className="route-result-metrics">
            <article><span>Минимальная длина</span><strong>{formatLength(independent?.total_length_m ?? 0)}</strong><small>{independent?.connected_demand_count ?? 0} ОКС</small></article>
            <article><span>Оптимальный баланс</span><strong>{formatLength(shared?.total_length_m ?? 0)}</strong><small>{shared?.connected_demand_count ?? 0} ОКС</small></article>
            <article><span>{variant.economics?.complete ? "Стоимость" : "Известная стоимость"}</span><strong>{variant.economics ? formatMoney(variant.economics.calculated_cost) : "—"}</strong><small>{variant.economics?.score != null ? `показатель ${variant.economics.score.toLocaleString("ru-RU", { maximumFractionDigits: 3 })}` : "без реконструкции"}</small></article>
            <button
              type="button"
              className={calculationValid ? "is-success" : "is-danger"}
              aria-haspopup="dialog"
              aria-label={`Открыть результаты проверки: ${errorCount(calculationIssues.length)}, ${warningCount(totalWarningCount)}`}
              onClick={() => setValidationDialogOpen(true)}
            >
              <span>Проверка структуры</span>
              <strong>{calculationValid ? "Пройдена" : "Требует проверки"}</strong>
              <small>{errorCount(calculationIssues.length)} · <u>{warningCount(totalWarningCount)}</u></small>
            </button>
          </div>
        </footer>
      </div>
      <Dialog
        open={validationDialogOpen}
        title="Результаты проверки"
        description={`${errorCount(calculationIssues.length)} расчёта · ${warningCount(totalWarningCount)}`}
        onClose={() => setValidationDialogOpen(false)}
      >
        <div className="validation-dialog-content">
          {calculationIssues.length > 0 && (
            <section className="validation-dialog-section">
              <header><strong>Расчёт маршрутов</strong><span>{errorCount(calculationIssues.length)}</span></header>
              <div className="validation-warning-list">
                {calculationIssues.map((issue, index) => (
                  <article key={`${issue.code}-${issue.subject_id ?? issue.edge_id ?? index}`}>
                    <AlertTriangle size={17} />
                    <div>
                      <strong>{calculationIssueTitle(issue)}</strong>
                      <p>{calculationIssueMessage(issue)}</p>
                      {(issue.subject_id || issue.edge_id) && <small>Объект: {issue.subject_id ?? issue.edge_id}</small>}
                    </div>
                  </article>
                ))}
              </div>
            </section>
          )}
          {inputWarningGroups.length > 0 ? (
            <section className="validation-dialog-section">
              <header><strong>Исходные данные</strong><span>{warningCount(warnings.length)}</span></header>
              <div className="validation-warning-list">
                {inputWarningGroups.map((group) => (
                  <article key={group.key}>
                    <AlertTriangle size={17} />
                    <div>
                      <strong>{group.title}</strong>
                      <p>{group.message}</p>
                      <small>
                        {objectCount(group.count)}
                        {group.exampleIds.length > 0 ? ` · примеры ID: ${group.exampleIds.join(", ")}` : ""}
                        {group.count === 1 && group.sample.field ? ` · поле ${group.sample.field}` : ""}
                      </small>
                    </div>
                  </article>
                ))}
              </div>
            </section>
          ) : reconstructionWarnings.length === 0 && depthWarnings.length === 0 ? (
            <div className="validation-dialog-empty">
              <CheckCircle2 size={22} />
              <div><strong>Предупреждений нет</strong><p>Входные данные прошли проверку без замечаний.</p></div>
            </div>
          ) : null}
          {depthWarnings.length > 0 && (
            <section className="validation-dialog-section">
              <header><strong>Вертикальный профиль</strong><span>{warningCount(depthWarnings.length)}</span></header>
              <div className="validation-warning-list">
                {depthWarnings.map((warning, index) => (
                  <article key={`${warning.edgeId}-${warning.crossing_id ?? index}`}>
                    <AlertTriangle size={17} />
                    <div>
                      <strong>Вертикальный профиль требует решения</strong>
                      <p>{depthIssueMessage(warning.code, warning.message)}</p>
                      <small>Участок: {warning.edgeId}{warning.crossing_id ? ` · пересечение ${warning.crossing_id}` : ""}</small>
                    </div>
                  </article>
                ))}
              </div>
            </section>
          )}
          {primaryReconstructionWarning && (
            <section className="validation-dialog-section">
              <header><strong>Реконструкция</strong><span>{warningCount(1)}</span></header>
              <div className="validation-warning-list">
                <article>
                  <AlertTriangle size={17} />
                  <div>
                    <strong>{calculationIssueTitle(primaryReconstructionWarning)}</strong>
                    <p>{calculationIssueMessage(primaryReconstructionWarning)}</p>
                    <small>
                      Затронуто участков: {reconstructionWarnings.length}
                      {reconstructionWarnings.some((issue) => issue.subject_id)
                        ? ` · ID ${reconstructionWarnings.map((issue) => issue.subject_id).filter(Boolean).join(", ")}`
                        : ""}
                    </small>
                  </div>
                </article>
              </div>
            </section>
          )}
          <div className="dialog-actions">
            <Button variant="outline" onClick={() => setValidationDialogOpen(false)}>Закрыть</Button>
          </div>
        </div>
      </Dialog>
    </section>
  );
}
