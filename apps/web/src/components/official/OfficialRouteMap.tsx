import { useQuery } from "@tanstack/react-query";
import Feature, { type FeatureLike } from "ol/Feature";
import OlMap from "ol/Map";
import View from "ol/View";
import { defaults as defaultControls, ScaleLine } from "ol/control";
import GeoJSON from "ol/format/GeoJSON";
import LineString from "ol/geom/LineString";
import Point from "ol/geom/Point";
import TileLayer from "ol/layer/Tile";
import VectorLayer from "ol/layer/Vector";
import { register } from "ol/proj/proj4";
import OSM from "ol/source/OSM";
import VectorSource from "ol/source/Vector";
import { Circle as CircleStyle, Fill, Stroke, Style } from "ol/style";
import proj4 from "proj4";
import { Layers3, LoaderCircle } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import "ol/ol.css";

import {
  getOfficialMap,
  type OfficialMapBounds,
  type OfficialRouteNode,
  type OfficialRouteVariant,
} from "../../shared/api";

const METRIC_CRS = "EPSG:32637";
const WEB_CRS = "EPSG:3857";
const WGS84_CRS = "EPSG:4326";
const MAP_PADDING_METERS = 700;
const configuredTileUrl: unknown = import.meta.env.VITE_OSM_TILE_URL;
const tileUrl = typeof configuredTileUrl === "string" && configuredTileUrl.length > 0
  ? configuredTileUrl
  : "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

proj4.defs(METRIC_CRS, "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs +type=crs");
register(proj4);

export interface SelectedMapObject {
  title: string;
  subtitle: string;
  details: Array<[string, string]>;
}

interface MapLayersState {
  base: boolean;
  restrictions: boolean;
  network: boolean;
  route: boolean;
}

function nodeTitle(node: OfficialRouteNode): string {
  if (node.node_type === "demand_connection") return `ОКС ${node.target_id ?? "—"}`;
  if (node.node_type === "new_branch_chamber") return "Новая камера ветвления";
  if (node.node_type === "new_tie_in_chamber") return `Новая камера врезки ${node.target_id ?? ""}`.trim();
  if (node.node_type === "existing_chamber_tie_in") return `Существующая камера ${node.target_id ?? ""}`.trim();
  return node.node_type.replaceAll("_", " ");
}

function routeMapBounds(variant: OfficialRouteVariant): OfficialMapBounds {
  const xs = variant.nodes.map((node) => node.coordinate.xm);
  const ys = variant.nodes.map((node) => node.coordinate.ym);
  const minX = Math.min(...xs) - MAP_PADDING_METERS;
  const minY = Math.min(...ys) - MAP_PADDING_METERS;
  const maxX = Math.max(...xs) + MAP_PADDING_METERS;
  const maxY = Math.max(...ys) + MAP_PADDING_METERS;
  const [lowerLongitude = 0, lowerLatitude = 0] = proj4(METRIC_CRS, WGS84_CRS, [minX, minY]);
  const [upperLongitude = 0, upperLatitude = 0] = proj4(METRIC_CRS, WGS84_CRS, [maxX, maxY]);
  return {
    minLon: Number(lowerLongitude.toFixed(7)),
    minLat: Number(lowerLatitude.toFixed(7)),
    maxLon: Number(upperLongitude.toFixed(7)),
    maxLat: Number(upperLatitude.toFixed(7)),
  };
}

function routeFeatures(variant: OfficialRouteVariant): Feature[] {
  const nodes = new globalThis.Map<string, OfficialRouteNode>(variant.nodes.map((node) => [node.id, node]));
  const edges = variant.edges.flatMap((edge) => {
    const upstream = nodes.get(edge.upstream_node_id);
    const downstream = nodes.get(edge.downstream_node_id);
    if (!upstream || !downstream) return [];
    const feature = new Feature({
      geometry: new LineString([
        [upstream.coordinate.xm, upstream.coordinate.ym],
        [downstream.coordinate.xm, downstream.coordinate.ym],
      ]).transform(METRIC_CRS, WEB_CRS),
    });
    feature.setProperties({
      map_layer: "calculated_route",
      route_kind: edge.id.includes(":trunk:") ? "trunk" : "branch",
      label: edge.id.includes(":trunk:") ? "Общий ствол" : "Расчётный участок",
      length_m: edge.length_m,
    });
    return [feature];
  });
  const points = variant.nodes.map((node) => {
    const feature = new Feature({
      geometry: new Point([node.coordinate.xm, node.coordinate.ym]).transform(METRIC_CRS, WEB_CRS),
    });
    feature.setProperties({
      map_layer: "calculated_node",
      node_type: node.node_type,
      label: nodeTitle(node),
      target_id: node.target_id,
      root: node.root,
    });
    return feature;
  });
  return [...edges, ...points];
}

function contextStyle(feature: FeatureLike): Style | Style[] {
  const objectType = feature.get("object_type") as string | undefined;
  if (objectType === "restriction") {
    const restrictionType = feature.get("restriction_type") as string | undefined;
    const color = restrictionType === "water" ? "#2f7ee6" : restrictionType === "railway" ? "#d18a20" : "#5e6670";
    return new Style({
      fill: new Fill({ color: `${color}20` }),
      stroke: new Stroke({ color: `${color}9a`, width: 1.35, lineDash: [5, 4] }),
    });
  }
  if (objectType === "heat_network") {
    return [
      new Style({ stroke: new Stroke({ color: "rgba(255,255,255,.92)", width: 6 }) }),
      new Style({ stroke: new Stroke({ color: "#17796b", width: 3.2 }) }),
    ];
  }
  if (objectType === "source") {
    return new Style({
      image: new CircleStyle({ radius: 8, fill: new Fill({ color: "#ef6b3b" }), stroke: new Stroke({ color: "#fff", width: 3 }) }),
    });
  }
  if (objectType === "heat_chamber") {
    return new Style({
      image: new CircleStyle({ radius: 5, fill: new Fill({ color: "#222126" }), stroke: new Stroke({ color: "#fff", width: 2 }) }),
    });
  }
  return new Style({
    image: new CircleStyle({ radius: 4, fill: new Fill({ color: "#58a66a" }), stroke: new Stroke({ color: "#fff", width: 2 }) }),
  });
}

function calculatedStyle(feature: FeatureLike): Style | Style[] {
  if (feature.get("map_layer") === "calculated_route") {
    const trunk = feature.get("route_kind") === "trunk";
    return [
      new Style({ stroke: new Stroke({ color: "rgba(255,255,255,.96)", width: trunk ? 10 : 8 }) }),
      new Style({ stroke: new Stroke({ color: trunk ? "#5b45e8" : "#7b65ff", width: trunk ? 6 : 4 }) }),
    ];
  }
  const nodeType = feature.get("node_type") as string;
  const color = nodeType === "demand_connection" ? "#45a55a" : feature.get("root") ? "#ed6a3b" : "#7357f6";
  return new Style({
    image: new CircleStyle({ radius: nodeType === "demand_connection" ? 6 : 7, fill: new Fill({ color }), stroke: new Stroke({ color: "#fff", width: 3 }) }),
  });
}

function selectedObject(feature: Feature): SelectedMapObject {
  const objectType = feature.get("object_type") as string | undefined;
  const layer = feature.get("map_layer") as string | undefined;
  const length = feature.get("length_m") as number | undefined;
  const details: Array<[string, string]> = [];
  if (length !== undefined) details.push(["Длина", `${Math.round(length).toLocaleString("ru-RU")} м`]);
  const flow = feature.get("flow_tph") as number | undefined;
  if (flow !== undefined) details.push(["Расход", `${flow.toLocaleString("ru-RU")} т/ч`]);
  const address = feature.get("address") as string | undefined;
  if (address) details.push(["Адрес", address]);
  const featureId = feature.get("feature_id") as string | undefined;
  if (featureId) details.push(["ID", featureId]);
  return {
    title: String(feature.get("label") ?? (objectType === "heat_network" ? "Существующая теплосеть" : objectType ?? "Объект карты")),
    subtitle: layer?.startsWith("calculated") ? "Результат расчёта" : "Исходные данные",
    details,
  };
}

export function OfficialRouteMap({
  importId,
  variant,
  onSelect,
}: {
  importId: string;
  variant: OfficialRouteVariant;
  onSelect?: (object: SelectedMapObject | null) => void;
}) {
  const targetRef = useRef<HTMLDivElement>(null);
  const [layers, setLayers] = useState<MapLayersState>({ base: true, restrictions: true, network: true, route: true });
  const bounds = useMemo(() => routeMapBounds(variant), [variant]);
  const context = useQuery({
    queryKey: ["official-map", importId, bounds],
    queryFn: ({ signal }) => getOfficialMap(importId, bounds, signal),
    staleTime: Number.POSITIVE_INFINITY,
    retry: 1,
  });

  useEffect(() => {
    if (!targetRef.current) return;
    const contextFeatures = context.data
      ? new GeoJSON().readFeatures(context.data, { dataProjection: WGS84_CRS, featureProjection: WEB_CRS }) as Feature[]
      : [];
    const restrictions = contextFeatures.filter((feature) => feature.get("object_type") === "restriction");
    const infrastructure = contextFeatures.filter((feature) => feature.get("object_type") !== "restriction");
    const routeSource = new VectorSource({ features: routeFeatures(variant) });
    const map = new OlMap({
      target: targetRef.current,
      layers: [
        new TileLayer({ visible: layers.base, source: new OSM({ url: tileUrl }) }),
        new VectorLayer({ visible: layers.restrictions, source: new VectorSource({ features: restrictions }), style: contextStyle }),
        new VectorLayer({ visible: layers.network, source: new VectorSource({ features: infrastructure }), style: contextStyle }),
        new VectorLayer({ visible: layers.route, source: routeSource, style: calculatedStyle }),
      ],
      view: new View({ projection: WEB_CRS, center: [0, 0], zoom: 15 }),
      controls: defaultControls({ attributionOptions: { collapsible: false } }).extend([new ScaleLine({ units: "metric" })]),
    });
    const routeExtent = routeSource.getExtent();
    if (routeExtent) map.getView().fit(routeExtent, { padding: [70, 70, 70, 70], maxZoom: 18, duration: 450 });
    map.on("singleclick", (event) => {
      const feature = map.forEachFeatureAtPixel(event.pixel, (candidate) => candidate as Feature, { hitTolerance: 7 });
      onSelect?.(feature ? selectedObject(feature) : null);
    });
    map.on("pointermove", (event) => {
      if (targetRef.current) targetRef.current.style.cursor = map.hasFeatureAtPixel(event.pixel, { hitTolerance: 5 }) ? "pointer" : "grab";
    });
    requestAnimationFrame(() => map.updateSize());
    return () => map.setTarget(undefined);
  }, [context.data, layers, onSelect, variant]);

  function toggleLayer(layer: keyof MapLayersState) {
    setLayers((current) => ({ ...current, [layer]: !current[layer] }));
  }

  return (
    <div className="official-map-shell">
      <div ref={targetRef} className="official-route-map" aria-label="Карта рассчитанных маршрутов и исходных ограничений" />
      <div className="official-map-layer-panel">
        <span><Layers3 size={15} /> Слои</span>
        <button type="button" className={layers.base ? "is-active" : undefined} onClick={() => toggleLayer("base")}>OSM</button>
        <button type="button" className={layers.network ? "is-active" : undefined} onClick={() => toggleLayer("network")}>Теплосеть</button>
        <button type="button" className={layers.restrictions ? "is-active" : undefined} onClick={() => toggleLayer("restrictions")}>Ограничения</button>
        <button type="button" className={layers.route ? "is-active" : undefined} onClick={() => toggleLayer("route")}>Расчёт</button>
      </div>
      {context.isPending && <div className="official-map-state"><LoaderCircle className="is-spinning" size={16} /> Загружаем инженерные слои…</div>}
      {context.isError && <div className="official-map-state is-error">Подложка доступна, исходные слои не загрузились</div>}
      {context.data?.truncated && <div className="official-map-state is-warning">Показаны первые 10 000 объектов в окне</div>}
      <div className="official-map-legend">
        <span><i className="is-calculated" /> Расчётная трасса</span>
        <span><i className="is-existing" /> Существующая сеть</span>
        <span><i className="is-restriction" /> Ограничения</span>
        <span><i className="is-demand" /> ОКС</span>
      </div>
    </div>
  );
}
