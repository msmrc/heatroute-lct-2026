import { AlertTriangle, Focus, MapPin, MapPinned, Network, X, ZoomIn, ZoomOut } from "lucide-react";
import { lazy, Suspense, useCallback, useMemo, useState } from "react";

import type {
  OfficialCalculationResult,
  OfficialRouteNode,
  OfficialRouteVariant,
} from "../../shared/api";
import { Button } from "../ui/button";
import { Badge } from "../ui/primitives";
import type { SelectedMapObject } from "./OfficialRouteMap";

const OfficialRouteMap = lazy(async () => {
  const module = await import("./OfficialRouteMap");
  return { default: module.OfficialRouteMap };
});

const CANVAS_WIDTH = 1000;
const CANVAS_HEIGHT = 590;
const CANVAS_PADDING = 58;

function formatLength(value: number): string {
  return value >= 1_000
    ? `${(value / 1_000).toLocaleString("ru-RU", { maximumFractionDigits: 2 })} км`
    : `${Math.round(value).toLocaleString("ru-RU")} м`;
}

function variantName(variant: OfficialRouteVariant): string {
  return variant.strategy === "shared_trunk" ? "Общая сеть" : "Раздельные трассы";
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

export function RouteVisualization({
  result,
  importId,
  warningCount = 0,
}: {
  result: OfficialCalculationResult;
  importId: string;
  warningCount?: number;
}) {
  const defaultVariant = result.variants.find((item) => item.id === result.preferred_variant_id) ?? result.variants[0];
  const [variantId, setVariantId] = useState(defaultVariant?.id ?? "");
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedObject, setSelectedObject] = useState<SelectedMapObject | null>(null);
  const [zoom, setZoom] = useState(1);
  const [viewMode, setViewMode] = useState<"map" | "schematic">("map");
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

  if (!variant || !layout) return null;

  const noRoute = variant.connections.filter((connection) => connection.status === "no_route");
  const independent = result.variants.find((item) => item.strategy === "independent");
  const shared = result.variants.find((item) => item.strategy === "shared_trunk");

  function changeVariant(id: string) {
    setVariantId(id);
    setSelectedNodeId(null);
    setSelectedObject(null);
    setZoom(1);
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
    <section className="route-workspace" aria-label="Визуализация рассчитанных маршрутов">
      <div className="route-workspace-main">
        <div className="route-map-stage">
          <div className="route-variant-tabs route-variant-tabs--floating" role="tablist" aria-label="Варианты маршрута">
            {result.variants.map((item) => (
              <button type="button" role="tab" aria-selected={item.id === variant.id}
                className={item.id === variant.id ? "is-active" : undefined} key={item.id}
                onClick={() => changeVariant(item.id)}>
                <span>{variantName(item)}</span>
                <small>{formatLength(item.total_length_m)}</small>
                {item.id === result.preferred_variant_id && <i>рекомендуем</i>}
              </button>
            ))}
          </div>

          <div className="route-map-view-controls">
            <div className="route-view-switch" role="group" aria-label="Режим визуализации">
              <button type="button" className={viewMode === "map" ? "is-active" : undefined} onClick={() => setViewMode("map")}><MapPinned size={15} /> Карта</button>
              <button type="button" className={viewMode === "schematic" ? "is-active" : undefined} onClick={() => setViewMode("schematic")}><Network size={15} /> Схема</button>
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
              <OfficialRouteMap importId={importId} variant={variant} onSelect={selectMapObject} />
            </Suspense>
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
        </div>

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
              <p className="route-inspector-summary">{variant.strategy === "shared_trunk" ? "Общий ствол сокращает суммарную длину сети и подключает все доступные ОКС." : "Каждый объект подключается отдельной трассой к подходящей точке врезки."}</p>
              <dl className="route-inspector-list">
                <div><dt>Длина</dt><dd>{formatLength(variant.total_length_m)}</dd></div>
                <div><dt>Подключено</dt><dd>{variant.connected_demand_count} из {result.demand_count} ОКС</dd></div>
                <div><dt>Участков</dt><dd>{variant.edges.length}</dd></div>
                <div><dt>Камер и врезок</dt><dd>{variant.nodes.filter((node) => node.chamber).length}</dd></div>
              </dl>
            </>
          )}

          {noRoute.length > 0 && (
            <div className="route-inspector-warning"><AlertTriangle size={18} /><div><strong>Есть неподключённые объекты</strong><p>{noRoute.map((connection) => `ОКС ${connection.demand_id}: ${noRouteReason(connection.reason)}`).join(" · ")}</p></div></div>
          )}
          <div className="route-inspector-hint">Нажмите на трассу или объект на карте, чтобы увидеть его данные.</div>
        </aside>
      </div>

      <footer className="route-results-drawer">
        <header><strong>Результаты расчёта</strong><span>{variantName(variant)}</span></header>
        <div className="route-result-metrics">
          <article><span>Раздельные трассы</span><strong>{formatLength(independent?.total_length_m ?? 0)}</strong><small>{independent?.connected_demand_count ?? 0} ОКС</small></article>
          <article><span>Общая сеть</span><strong>{formatLength(shared?.total_length_m ?? 0)}</strong><small>{shared?.connected_demand_count ?? 0} ОКС</small></article>
          <article><span>Камер и врезок</span><strong>{variant.nodes.filter((node) => node.chamber).length}</strong><small>{variant.edges.length} участков</small></article>
          <article className={variant.valid ? "is-success" : "is-danger"}><span>Проверка структуры</span><strong>{variant.valid ? "Пройдена" : "Есть ошибки"}</strong><small>{variant.validation_issues.length} ошибок · {warningCount} предупреждений</small></article>
        </div>
      </footer>
    </section>
  );
}
