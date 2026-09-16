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
import { Check, Layers3, LoaderCircle } from "lucide-react";
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
    const color = restrictionType === "water" ? "#5794c9" : restrictionType === "railway" ? "#b58a50" : "#8b8f96";
    return new Style({
      fill: new Fill({ color: `${color}0c` }),
      stroke: new Stroke({ color: `${color}78`, width: 1, lineDash: [5, 5] }),
    });
  }
  if (objectType === "heat_network") {
    return [
      new Style({ stroke: new Stroke({ color: "rgba(255,255,255,.9)", width: 5 }) }),
      new Style({ stroke: new Stroke({ color: "#238577", width: 2.6 }) }),
    ];
  }
  if (objectType === "source") {
    return new Style({
      image: new CircleStyle({ radius: 8, fill: new Fill({ color: "#ef6b3b" }), stroke: new Stroke({ color: "#fff", width: 3 }) }),
    });
  }
  if (objectType === "heat_chamber") {
    return new Style({
      image: new CircleStyle({ radius: 4.5, fill: new Fill({ color: "#34363b" }), stroke: new Stroke({ color: "#fff", width: 2 }) }),
    });
  }
  return new Style({
    image: new CircleStyle({ radius: 3.5, fill: new Fill({ color: "#4d9e68" }), stroke: new Stroke({ color: "#fff", width: 1.8 }) }),
  });
}

function calculatedStyle(feature: FeatureLike): Style | Style[] {
  if (feature.get("map_layer") === "calculated_route") {
    const trunk = feature.get("route_kind") === "trunk";
    return [
      new Style({ stroke: new Stroke({ color: "rgba(255,255,255,.94)", width: trunk ? 8 : 6.5 }) }),
      new Style({ stroke: new Stroke({ color: trunk ? "#5e4be2" : "#7464e8", width: trunk ? 4.5 : 3.5 }) }),
    ];
  }
  const nodeType = feature.get("node_type") as string;
  const color = nodeType === "demand_connection" ? "#45a55a" : feature.get("root") ? "#ed6a3b" : "#7357f6";
  return new Style({
    image: new CircleStyle({ radius: nodeType === "demand_connection" ? 5 : 6, fill: new Fill({ color }), stroke: new Stroke({ color: "#fff", width: 2.4 }) }),
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
  const [layers, setLayers] = useState<MapLayersState>({ base: true, restrictions: false, network: true, route: true });
  const [layerMenuOpen, setLayerMenuOpen] = useState(false);
  const layerVisibilityRef = useRef(layers);
  const mapLayersRef = useRef<Record<keyof MapLayersState, { setVisible: (visible: boolean) => void }> | null>(null);
  const bounds = useMemo(() => routeMapBounds(variant), [variant]);
  const context = useQuery({
    queryKey: ["official-map", importId, bounds],
    queryFn: ({ signal }) => getOfficialMap(importId, bounds, signal),
    staleTime: Number.POSITIVE_INFINITY,
    retry: 1,
  });

  useEffect(() => {
    layerVisibilityRef.current = layers;
  }, [layers]);

  useEffect(() => {
    if (!targetRef.current) return;
    const contextFeatures = context.data
      ? new GeoJSON().readFeatures(context.data, { dataProjection: WGS84_CRS, featureProjection: WEB_CRS }) as Feature[]
      : [];
    const restrictions = contextFeatures.filter((feature) => feature.get("object_type") === "restriction");
    const infrastructure = contextFeatures.filter((feature) => feature.get("object_type") !== "restriction");
    const routeSource = new VectorSource({ features: routeFeatures(variant) });
    const baseLayer = new TileLayer({ className: "simple-basemap", opacity: .72, visible: layerVisibilityRef.current.base, source: new OSM({ url: tileUrl }) });
    const restrictionLayer = new VectorLayer({ visible: layerVisibilityRef.current.restrictions, source: new VectorSource({ features: restrictions }), style: contextStyle });
    const networkLayer = new VectorLayer({ visible: layerVisibilityRef.current.network, source: new VectorSource({ features: infrastructure }), style: contextStyle });
    const routeLayer = new VectorLayer({ visible: layerVisibilityRef.current.route, source: routeSource, style: calculatedStyle });
    mapLayersRef.current = { base: baseLayer, restrictions: restrictionLayer, network: networkLayer, route: routeLayer };
    const map = new OlMap({
      target: targetRef.current,
      layers: [baseLayer, restrictionLayer, networkLayer, routeLayer],
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
    return () => {
      mapLayersRef.current = null;
      map.setTarget(undefined);
    };
  }, [context.data, onSelect, variant]);

  useEffect(() => {
    const mapLayers = mapLayersRef.current;
    if (!mapLayers) return;
    (Object.keys(layers) as Array<keyof MapLayersState>).forEach((layer) => mapLayers[layer].setVisible(layers[layer]));
  }, [layers]);

  function toggleLayer(layer: keyof MapLayersState) {
    setLayers((current) => ({ ...current, [layer]: !current[layer] }));
  }

  return (
    <div className="official-map-shell">
      <div ref={targetRef} className="official-route-map" aria-label="Карта рассчитанных маршрутов и исходных ограничений" />
      <div className="official-map-layer-control">
        <button
          type="button"
          className={layerMenuOpen ? "official-map-layer-button is-open" : "official-map-layer-button"}
          aria-label="Слои карты"
          aria-expanded={layerMenuOpen}
          onClick={() => setLayerMenuOpen((value) => !value)}
        >
          <Layers3 size={19} />
        </button>
        {layerMenuOpen && (
          <div className="official-map-layer-panel" role="menu" aria-label="Слои карты">
            <strong>Слои карты</strong>
            {([
              ["base", "Карта"],
              ["network", "Теплосеть"],
              ["restrictions", "Ограничения"],
              ["route", "Маршруты"],
            ] as const).map(([layer, label]) => (
              <button type="button" role="menuitemcheckbox" aria-checked={layers[layer]} key={layer} onClick={() => toggleLayer(layer)}>
                <span className={layers[layer] ? "is-checked" : undefined}>{layers[layer] && <Check size={13} />}</span>
                {label}
              </button>
            ))}
          </div>
        )}
      </div>
      {context.isPending && <div className="official-map-state"><LoaderCircle className="is-spinning" size={16} /> Загружаем инженерные слои…</div>}
      {context.isError && <div className="official-map-state is-error">Подложка доступна, исходные слои не загрузились</div>}
      {context.data?.truncated && <div className="official-map-state is-warning">Показаны первые 10 000 объектов в окне</div>}
    </div>
  );
}
