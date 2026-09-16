import { AlertTriangle, CheckCircle2, Focus, MapPinned, Network, Route, ZoomIn, ZoomOut } from "lucide-react";
import { useMemo, useState } from "react";

import { Badge, Card } from "../ui/primitives";
import { Button } from "../ui/button";
import { OfficialRouteMap } from "./OfficialRouteMap";
import type {
  OfficialCalculationResult,
  OfficialRouteNode,
  OfficialRouteVariant,
} from "../../shared/api";

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

export function RouteVisualization({ result, importId }: { result: OfficialCalculationResult; importId: string }) {
  const defaultVariant = result.variants.find((variant) => variant.id === result.preferred_variant_id) ?? result.variants[0];
  const [variantId, setVariantId] = useState(defaultVariant?.id ?? "");
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
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
    return { points, rangeX, rangeY };
  }, [variant]);

  if (!variant || !layout) return null;

  const selectedNode = variant.nodes.find((node) => node.id === selectedNodeId);
  const noRoute = variant.connections.filter((connection) => connection.status === "no_route");

  return (
    <section className="route-visualization" aria-label="Визуализация рассчитанных маршрутов">
      <header className="route-visualization__header">
        <div>
          <span className="eyebrow">Результат расчёта</span>
          <h2>Маршруты на карте</h2>
          <p>Расчётная трасса, существующая теплосеть и ограничения совмещены на OpenStreetMap. Инженерная схема доступна отдельным режимом.</p>
        </div>
        <div className="route-variant-tabs" role="tablist" aria-label="Варианты маршрута">
          {result.variants.map((item) => (
            <button
              type="button"
              role="tab"
              aria-selected={item.id === variant.id}
              className={item.id === variant.id ? "is-active" : undefined}
              key={item.id}
              onClick={() => {
                setVariantId(item.id);
                setSelectedNodeId(null);
                setZoom(1);
              }}
            >
              <span>{variantName(item)}</span>
              <small>{formatLength(item.total_length_m)}</small>
              {item.id === result.preferred_variant_id && <i>лучший</i>}
            </button>
          ))}
        </div>
      </header>

      <div className="route-kpis">
        <article><span>Подключено</span><strong>{variant.connected_demand_count} / {result.demand_count}</strong><small>объектов</small></article>
        <article><span>Длина сети</span><strong>{formatLength(variant.total_length_m)}</strong><small>{variant.edges.length} участков</small></article>
        <article><span>Камер и врезок</span><strong>{variant.nodes.filter((node) => node.chamber).length}</strong><small>узлов</small></article>
        <article className={variant.valid ? "is-success" : "is-danger"}>
          <span>Проверка структуры</span>
          <strong>{variant.valid ? "Пройдена" : "Есть ошибки"}</strong>
          <small>{variant.validation_issues.length} замечаний</small>
        </article>
      </div>

      <Card className="route-canvas-card">
        <div className="route-canvas-toolbar">
          <div className="route-canvas-caption">{viewMode === "map" ? <MapPinned size={16} /> : <Network size={16} />}<span>{viewMode === "map" ? "Географическая карта" : "Расчётная схема"}</span><Badge tone="violet">{viewMode === "map" ? "WGS84" : "EPSG:32637"}</Badge></div>
          <div className="route-canvas-actions">
            <div className="route-view-switch" role="group" aria-label="Режим визуализации">
              <button type="button" className={viewMode === "map" ? "is-active" : undefined} onClick={() => setViewMode("map")}><MapPinned size={14} /> Карта</button>
              <button type="button" className={viewMode === "schematic" ? "is-active" : undefined} onClick={() => setViewMode("schematic")}><Network size={14} /> Схема</button>
            </div>
            {viewMode === "schematic" && <div className="route-zoom-controls">
              <Button variant="ghost" aria-label="Уменьшить" onClick={() => setZoom((value) => Math.max(.75, value - .25))}><ZoomOut size={16} /></Button>
              <span>{Math.round(zoom * 100)}%</span>
              <Button variant="ghost" aria-label="Увеличить" onClick={() => setZoom((value) => Math.min(2.5, value + .25))}><ZoomIn size={16} /></Button>
              <Button variant="ghost" aria-label="Показать всю схему" onClick={() => setZoom(1)}><Focus size={16} /></Button>
            </div>}
          </div>
        </div>

        {viewMode === "map" ? (
          <OfficialRouteMap importId={importId} variant={variant} />
        ) : <div className="route-canvas-wrap">
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
                return <RouteNodeGlyph key={node.id} node={node} x={point.x} y={point.y} selected={node.id === selectedNodeId} onSelect={() => setSelectedNodeId(node.id)} />;
              })}
            </g>
          </svg>

          <div className="route-legend" aria-label="Легенда">
            <span><i className="is-demand" /> ОКС</span>
            <span><i className="is-tie" /> Врезка</span>
            <span><i className="is-chamber" /> Камера</span>
            <span><b /> Трасса</span>
          </div>

          {selectedNode && (
            <aside className="route-node-inspector">
              <button type="button" aria-label="Закрыть карточку узла" onClick={() => setSelectedNodeId(null)}>×</button>
              <span>Выбранный узел</span>
              <strong>{nodeName(selectedNode)}</strong>
              <dl>
                <div><dt>X</dt><dd>{selectedNode.coordinate.xm.toLocaleString("ru-RU")} м</dd></div>
                <div><dt>Y</dt><dd>{selectedNode.coordinate.ym.toLocaleString("ru-RU")} м</dd></div>
              </dl>
            </aside>
          )}
        </div>}

        <footer className="route-canvas-footer">
          <span><Route size={15} /> Охват: {Math.round(layout.rangeX)} × {Math.round(layout.rangeY)} м</span>
          <span>{viewMode === "map" ? "Нажмите на трассу или объект, чтобы увидеть данные" : "Нажмите на узел, чтобы увидеть координаты"}</span>
        </footer>
      </Card>

      {noRoute.length > 0 && (
        <div className="route-warning">
          <AlertTriangle size={18} />
          <div><strong>Не для всех ОКС найден допустимый маршрут</strong><p>{noRoute.map((connection) => `ОКС ${connection.demand_id}: ${connection.reason ?? "причина не указана"}`).join(" · ")}</p></div>
        </div>
      )}

      <div className="route-comparison">
        {result.variants.map((item) => (
          <button type="button" key={item.id} className={item.id === variant.id ? "is-active" : undefined} onClick={() => setVariantId(item.id)}>
            <span className="route-comparison__icon">{item.valid ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}</span>
            <span><strong>{variantName(item)}</strong><small>{item.connected_demand_count} ОКС · {item.edges.length} участков</small></span>
            <b>{formatLength(item.total_length_m)}</b>
          </button>
        ))}
      </div>
    </section>
  );
}
