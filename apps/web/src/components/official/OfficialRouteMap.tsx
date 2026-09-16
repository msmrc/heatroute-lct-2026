import { useQuery } from "@tanstack/react-query";
import type { FeatureCollection } from "geojson";
import { Check, Layers3, LoaderCircle } from "lucide-react";
import {
  Map as MapLibreMap,
  NavigationControl,
  ScaleControl,
  setWorkerUrl,
  type LayerSpecification,
  type MapGeoJSONFeature,
} from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import proj4 from "proj4";
import { useEffect, useMemo, useRef, useState } from "react";
import "maplibre-gl/dist/maplibre-gl.css";

import {
  getOfficialMap,
  getOfficialVariantOutput,
  type OfficialMapBounds,
  type OfficialRouteNode,
  type OfficialRouteVariant,
} from "../../shared/api";
import { applyGdeBenzinBasemapStyle, BASEMAP_STYLE_URL } from "./heatRouteBasemap";
import { officialRouteFeatureCollection, type MapFeatureCollection } from "./officialOutputMap";

const METRIC_CRS = "EPSG:32637";
const WGS84_CRS = "EPSG:4326";
const MAP_PADDING_METERS = 700;
const CONTEXT_SOURCE = "heatroute-context";
const ROUTE_SOURCE = "heatroute-route";

setWorkerUrl(workerUrl);
proj4.defs(METRIC_CRS, "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs +type=crs");

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

const OVERLAY_LAYERS: Record<Exclude<keyof MapLayersState, "base">, string[]> = {
  restrictions: ["restriction-fill", "restriction-line"],
  network: ["network-casing", "network-line", "source-points", "chamber-points", "context-points"],
  route: ["reconstruction-casing", "reconstruction-line", "route-casing", "route-line", "reconstruction-nodes", "route-nodes"],
};
const SELECTABLE_LAYERS = Object.values(OVERLAY_LAYERS).flat();

function nodeTitle(node: OfficialRouteNode): string {
  if (node.node_type === "demand_connection") return `ОКС ${node.target_id ?? "—"}`;
  if (node.node_type === "new_branch_chamber") return "Новая камера ветвления";
  if (node.node_type === "new_tie_in_chamber") return `Новая камера врезки ${node.target_id ?? ""}`.trim();
  if (node.node_type === "existing_chamber_tie_in") return `Существующая камера ${node.target_id ?? ""}`.trim();
  return node.node_type.replaceAll("_", " ");
}

function toWgs84(x: number, y: number): [number, number] {
  const [longitude = 0, latitude = 0] = proj4(METRIC_CRS, WGS84_CRS, [x, y]);
  return [longitude, latitude];
}

function routeMapBounds(variant: OfficialRouteVariant): OfficialMapBounds {
  const routeCoordinates = [
    ...variant.edges.flatMap((edge) => edge.coordinates ?? []),
    ...(variant.reconstruction?.network_sections.flatMap((section) => section.coordinates) ?? []),
    ...(variant.reconstruction?.chambers.map((chamber) => chamber.coordinate) ?? []),
  ];
  const xs = [...variant.nodes.map((node) => node.coordinate.xm), ...routeCoordinates.map((coordinate) => coordinate.xm)];
  const ys = [...variant.nodes.map((node) => node.coordinate.ym), ...routeCoordinates.map((coordinate) => coordinate.ym)];
  const [minLon, minLat] = toWgs84(Math.min(...xs) - MAP_PADDING_METERS, Math.min(...ys) - MAP_PADDING_METERS);
  const [maxLon, maxLat] = toWgs84(Math.max(...xs) + MAP_PADDING_METERS, Math.max(...ys) + MAP_PADDING_METERS);
  return {
    minLon: Number(minLon.toFixed(7)),
    minLat: Number(minLat.toFixed(7)),
    maxLon: Number(maxLon.toFixed(7)),
    maxLat: Number(maxLat.toFixed(7)),
  };
}

function routeFeatureCollection(variant: OfficialRouteVariant): MapFeatureCollection {
  const nodes = new Map<string, OfficialRouteNode>(variant.nodes.map((node) => [node.id, node]));
  const edges: MapFeatureCollection["features"] = variant.edges.flatMap((edge) => {
    const upstream = nodes.get(edge.upstream_node_id);
    const downstream = nodes.get(edge.downstream_node_id);
    if (!upstream || !downstream) return [];
    const fallbackCoordinates = [upstream.coordinate, downstream.coordinate];
    const baseKind = edge.id.includes(":trunk:") ? "trunk" : "branch";
    const sections = edge.sections?.length ? edge.sections : [{
      kind: "base" as const,
      length_m: edge.length_m,
      coordinates: edge.coordinates?.length ? edge.coordinates : fallbackCoordinates,
    }];
    return sections.flatMap((section, sectionIndex) => section.coordinates.length < 2 ? [] : [{
      type: "Feature" as const,
      geometry: {
        type: "LineString" as const,
        coordinates: section.coordinates.map((coordinate) => toWgs84(coordinate.xm, coordinate.ym)),
      },
      properties: {
        map_layer: "calculated_route",
        route_kind: section.kind === "special" ? "special" : baseKind,
        label: section.kind === "special"
          ? `Специальный переход: ${section.restriction_type ?? "препятствие"}`
          : edge.id.includes(":trunk:") ? "Общий ствол" : "Расчётный участок",
        length_m: section.length_m,
        diameter: edge.diameter ?? null,
        flow_tph: edge.flow_tph ?? null,
        crossing_type: section.restriction_type ?? null,
        crossing_angle_degrees: section.crossing_angle_degrees ?? null,
        edge_id: edge.id,
        section_index: sectionIndex,
      },
    }]);
  });
  const points: MapFeatureCollection["features"] = variant.nodes.map((node) => ({
    type: "Feature",
    geometry: { type: "Point", coordinates: toWgs84(node.coordinate.xm, node.coordinate.ym) },
    properties: {
      map_layer: "calculated_node",
      node_type: node.node_type,
      label: nodeTitle(node),
      target_id: node.target_id ?? null,
      root: node.root,
    },
  }));
  const reconstructionSections: MapFeatureCollection["features"] =
    variant.reconstruction?.network_sections.flatMap((section) => section.coordinates.length < 2 ? [] : [{
      type: "Feature" as const,
      geometry: {
        type: "LineString" as const,
        coordinates: section.coordinates.map((coordinate) => toWgs84(coordinate.xm, coordinate.ym)),
      },
      properties: {
        map_layer: "calculated_reconstruction",
        label: `Реконструкция участка ${section.existing_feature_id}`,
        feature_id: section.existing_feature_id,
        length_m: section.length_m,
        flow_tph: section.resulting_flow_tph,
        diameter: section.required_diameter,
        existing_diameter: section.existing_diameter,
        added_flow_tph: section.added_flow_tph,
        partial: section.partial,
      },
    }]) ?? [];
  const reconstructionChambers: MapFeatureCollection["features"] =
    variant.reconstruction?.chambers.map((chamber) => ({
      type: "Feature" as const,
      geometry: { type: "Point" as const, coordinates: toWgs84(chamber.coordinate.xm, chamber.coordinate.ym) },
      properties: {
        map_layer: "calculated_reconstruction_chamber",
        label: `Реконструкция камеры ${chamber.existing_feature_id}`,
        feature_id: chamber.existing_feature_id,
        flow_tph: chamber.resulting_flow_tph,
        diameter: chamber.required_diameter,
        existing_diameter: chamber.existing_diameter,
        added_flow_tph: chamber.added_flow_tph,
      },
    })) ?? [];
  return { type: "FeatureCollection", features: [...reconstructionSections, ...edges, ...reconstructionChambers, ...points] };
}

function addOverlayLayers(map: MapLibreMap, contextData: FeatureCollection, routeData: MapFeatureCollection) {
  map.addSource(CONTEXT_SOURCE, { type: "geojson", data: contextData });
  map.addSource(ROUTE_SOURCE, { type: "geojson", data: routeData });

  const layers: LayerSpecification[] = [
    { id: "restriction-fill", type: "fill", source: CONTEXT_SOURCE, filter: ["==", ["get", "object_type"], "restriction"], paint: { "fill-color": ["match", ["get", "restriction_type"], "water", "#5794c9", "railway", "#b58a50", "#8b8f96"], "fill-opacity": 0.06 } },
    { id: "restriction-line", type: "line", source: CONTEXT_SOURCE, filter: ["==", ["get", "object_type"], "restriction"], paint: { "line-color": ["match", ["get", "restriction_type"], "water", "#5794c9", "railway", "#b58a50", "#8b8f96"], "line-opacity": 0.58, "line-width": 1.2, "line-dasharray": [4, 4] } },
    { id: "network-casing", type: "line", source: CONTEXT_SOURCE, filter: ["==", ["get", "object_type"], "heat_network"], paint: { "line-color": "rgba(255,255,255,.92)", "line-width": 5 } },
    { id: "network-line", type: "line", source: CONTEXT_SOURCE, filter: ["==", ["get", "object_type"], "heat_network"], paint: { "line-color": "#238577", "line-width": 2.6 } },
    { id: "source-points", type: "circle", source: CONTEXT_SOURCE, filter: ["==", ["get", "object_type"], "source"], paint: { "circle-radius": 8, "circle-color": "#ef6b3b", "circle-stroke-color": "#ffffff", "circle-stroke-width": 3 } },
    { id: "chamber-points", type: "circle", source: CONTEXT_SOURCE, filter: ["==", ["get", "object_type"], "heat_chamber"], paint: { "circle-radius": 4.5, "circle-color": "#34363b", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2 } },
    { id: "context-points", type: "circle", source: CONTEXT_SOURCE, filter: ["all", ["==", ["geometry-type"], "Point"], ["!", ["in", ["get", "object_type"], ["literal", ["source", "heat_chamber"]]]]], paint: { "circle-radius": 3.5, "circle-color": "#4d9e68", "circle-stroke-color": "#ffffff", "circle-stroke-width": 1.8 } },
    { id: "reconstruction-casing", type: "line", source: ROUTE_SOURCE, filter: ["==", ["get", "map_layer"], "calculated_reconstruction"], paint: { "line-color": "rgba(255,255,255,.96)", "line-width": 9 } },
    { id: "reconstruction-line", type: "line", source: ROUTE_SOURCE, filter: ["==", ["get", "map_layer"], "calculated_reconstruction"], paint: { "line-color": "#d94f70", "line-width": 5, "line-dasharray": [1.4, 1] } },
    { id: "route-casing", type: "line", source: ROUTE_SOURCE, filter: ["==", ["get", "map_layer"], "calculated_route"], paint: { "line-color": "rgba(255,255,255,.96)", "line-width": ["match", ["get", "route_kind"], "trunk", 8, "special", 9, 6.5] } },
    { id: "route-line", type: "line", source: ROUTE_SOURCE, filter: ["==", ["get", "map_layer"], "calculated_route"], paint: { "line-color": ["match", ["get", "route_kind"], "trunk", "#5e4be2", "special", "#ed6a3b", "#7464e8"], "line-width": ["match", ["get", "route_kind"], "trunk", 4.5, "special", 5, 3.5] } },
    { id: "reconstruction-nodes", type: "circle", source: ROUTE_SOURCE, filter: ["==", ["get", "map_layer"], "calculated_reconstruction_chamber"], paint: { "circle-radius": 7, "circle-color": "#d94f70", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2.4 } },
    { id: "route-nodes", type: "circle", source: ROUTE_SOURCE, filter: ["==", ["get", "map_layer"], "calculated_node"], paint: { "circle-radius": ["match", ["get", "node_type"], "demand_connection", 5, 6], "circle-color": ["case", ["==", ["get", "node_type"], "demand_connection"], "#45a55a", ["==", ["get", "root"], true], "#ed6a3b", "#7357f6"], "circle-stroke-color": "#ffffff", "circle-stroke-width": 2.4 } },
  ];
  layers.forEach((layer) => map.addLayer(layer));
}

function propertiesOf(feature: MapGeoJSONFeature): Record<string, unknown> {
  return feature.properties;
}

function selectedObject(feature: MapGeoJSONFeature): SelectedMapObject {
  const properties = propertiesOf(feature);
  const objectType = typeof properties.object_type === "string" ? properties.object_type : undefined;
  const layer = typeof properties.map_layer === "string" ? properties.map_layer : undefined;
  const details: Array<[string, string]> = [];
  if (typeof properties.length_m === "number") details.push(["Длина", `${Math.round(properties.length_m).toLocaleString("ru-RU")} м`]);
  if (typeof properties.flow_tph === "number") details.push(["Расход", `${properties.flow_tph.toLocaleString("ru-RU")} т/ч`]);
  if (typeof properties.diameter === "number") details.push(["Диаметр", `ДУ ${properties.diameter}`]);
  if (typeof properties.existing_diameter === "number") details.push(["Существующий диаметр", `ДУ ${properties.existing_diameter}`]);
  if (typeof properties.added_flow_tph === "number") details.push(["Добавленный расход", `${properties.added_flow_tph.toLocaleString("ru-RU")} т/ч`]);
  if (typeof properties.crossing_angle_degrees === "number") details.push(["Угол перехода", `${properties.crossing_angle_degrees.toLocaleString("ru-RU")}°`]);
  if (typeof properties.address === "string" && properties.address) details.push(["Адрес", properties.address]);
  if (typeof properties.feature_id === "string" && properties.feature_id) details.push(["ID", properties.feature_id]);
  const fallback = objectType === "heat_network" ? "Существующая теплосеть" : objectType ?? "Объект карты";
  return {
    title: typeof properties.label === "string" ? properties.label : fallback,
    subtitle: layer?.startsWith("calculated") ? "Результат расчёта" : "Исходные данные",
    details,
  };
}

function setLayerVisibility(map: MapLibreMap, ids: string[], visible: boolean) {
  for (const id of ids) {
    if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", visible ? "visible" : "none");
  }
}

export function OfficialRouteMap({ runId, importId, variant, onSelect }: {
  runId: string;
  importId: string;
  variant: OfficialRouteVariant;
  onSelect?: (object: SelectedMapObject | null) => void;
}) {
  const targetRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const baseLayerIdsRef = useRef<string[]>([]);
  const [layers, setLayers] = useState<MapLayersState>({ base: true, restrictions: false, network: true, route: true });
  const [layerMenuOpen, setLayerMenuOpen] = useState(false);
  const layerVisibilityRef = useRef(layers);
  const bounds = useMemo(() => routeMapBounds(variant), [variant]);
  const officialOutputEnabled = variant.valid && variant.rank != null && variant.economics?.complete === true;
  const officialOutput = useQuery({
    queryKey: ["official-output-map", runId, variant.id],
    queryFn: ({ signal }) => getOfficialVariantOutput(runId, variant.id, signal),
    enabled: officialOutputEnabled,
    staleTime: Number.POSITIVE_INFINITY,
    retry: 1,
  });
  const routeData = useMemo(
    () => officialOutput.data
      ? officialRouteFeatureCollection(officialOutput.data, variant.id)
      : routeFeatureCollection(variant),
    [officialOutput.data, variant],
  );
  const context = useQuery({
    queryKey: ["official-map", importId, bounds],
    queryFn: ({ signal }) => getOfficialMap(importId, bounds, signal),
    staleTime: Number.POSITIVE_INFINITY,
    retry: 1,
  });

  useEffect(() => {
    layerVisibilityRef.current = layers;
    const map = mapRef.current;
    if (!map?.isStyleLoaded()) return;
    setLayerVisibility(map, baseLayerIdsRef.current, layers.base);
    (Object.keys(OVERLAY_LAYERS) as Array<keyof typeof OVERLAY_LAYERS>).forEach((key) => {
      setLayerVisibility(map, OVERLAY_LAYERS[key], layers[key]);
    });
  }, [layers]);

  useEffect(() => {
    if (!targetRef.current || context.isPending || (officialOutputEnabled && officialOutput.isPending)) return;
    const emptyContext: FeatureCollection = { type: "FeatureCollection", features: [] };
    const contextData = context.data
      ? context.data as unknown as FeatureCollection
      : emptyContext;
    const map = new MapLibreMap({
      container: targetRef.current,
      style: BASEMAP_STYLE_URL,
      center: [(bounds.minLon + bounds.maxLon) / 2, (bounds.minLat + bounds.maxLat) / 2],
      zoom: 14,
      minZoom: 4,
      maxZoom: 19,
      pitch: 0,
      attributionControl: { compact: true },
    });
    mapRef.current = map;
    map.addControl(new NavigationControl({ showCompass: false }), "bottom-right");
    map.addControl(new ScaleControl({ unit: "metric" }), "bottom-left");

    const resizeObserver = new ResizeObserver(() => map.resize());
    resizeObserver.observe(targetRef.current);

    // Do not wait for every remote basemap tile: the calculated route must appear as soon as the
    // style graph is ready, even on a slow or partially unavailable external tile connection.
    void map.once("style.load", () => {
      applyGdeBenzinBasemapStyle(map);
      baseLayerIdsRef.current = (map.getStyle().layers ?? []).map((layer) => layer.id);
      addOverlayLayers(map, contextData, routeData);
      setLayerVisibility(map, baseLayerIdsRef.current, layerVisibilityRef.current.base);
      (Object.keys(OVERLAY_LAYERS) as Array<keyof typeof OVERLAY_LAYERS>).forEach((key) => {
        setLayerVisibility(map, OVERLAY_LAYERS[key], layerVisibilityRef.current[key]);
      });
      map.fitBounds([[bounds.minLon, bounds.minLat], [bounds.maxLon, bounds.maxLat]], { padding: 64, maxZoom: 17, duration: 450 });
    });

    map.on("click", (event) => {
      if (!map.isStyleLoaded()) return;
      const availableLayers = SELECTABLE_LAYERS.filter((id) => map.getLayer(id));
      const feature = availableLayers.length ? map.queryRenderedFeatures(event.point, { layers: availableLayers })[0] : undefined;
      onSelect?.(feature ? selectedObject(feature) : null);
    });
    map.on("mousemove", (event) => {
      if (!map.isStyleLoaded()) return;
      const availableLayers = SELECTABLE_LAYERS.filter((id) => map.getLayer(id));
      const feature = availableLayers.length ? map.queryRenderedFeatures(event.point, { layers: availableLayers })[0] : undefined;
      map.getCanvas().style.cursor = feature ? "pointer" : "grab";
    });

    return () => {
      resizeObserver.disconnect();
      baseLayerIdsRef.current = [];
      mapRef.current = null;
      map.remove();
    };
  }, [bounds, context.data, context.isPending, officialOutput.isPending, officialOutputEnabled, onSelect, routeData]);

  function toggleLayer(layer: keyof MapLayersState) {
    setLayers((current) => ({ ...current, [layer]: !current[layer] }));
  }

  return (
    <div className="official-map-shell">
      <div ref={targetRef} className="official-route-map" aria-label="Карта рассчитанных маршрутов и исходных ограничений" />
      <div className="official-map-layer-control">
        <button type="button" className={layerMenuOpen ? "official-map-layer-button is-open" : "official-map-layer-button"} aria-label="Слои карты" aria-expanded={layerMenuOpen} onClick={() => setLayerMenuOpen((value) => !value)}>
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
      {officialOutputEnabled && officialOutput.isPending && <div className="official-map-state"><LoaderCircle className="is-spinning" size={16} /> Готовим официальный результат…</div>}
      {officialOutputEnabled && officialOutput.isError && <div className="official-map-state is-warning">Официальный слой не загрузился, показан расчётный preview</div>}
      {context.isError && <div className="official-map-state is-error">Карта доступна, исходные слои не загрузились</div>}
      {context.data?.truncated && <div className="official-map-state is-warning">Показаны первые 10 000 объектов в окне</div>}
    </div>
  );
}
