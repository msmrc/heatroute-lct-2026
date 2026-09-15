import maplibregl, { type GeoJSONSource, type StyleSpecification } from "maplibre-gl";
import type { Feature, FeatureCollection, Geometry, Polygon } from "geojson";
import { useEffect, useRef } from "react";

import { API_BASE, type DatasetLayer, type RouteAlternative } from "../../shared/api";
import type { WorkspaceScenarioDraft } from "../../shared/scenario";
import type { MapLayerKey } from "../../shared/store";

const emptyCollection: FeatureCollection = { type: "FeatureCollection", features: [] };
const neutralStyle: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#efefed" } }],
};

interface WorkspaceMapProps {
  alternatives?: readonly RouteAlternative[];
  selectedRank?: number;
  draft: WorkspaceScenarioDraft;
  layers: Record<MapLayerKey, boolean>;
  tileLayers?: readonly DatasetLayer[];
}

function rectangleFeature(rectangle: readonly number[]): Feature<Polygon> | undefined {
  if (rectangle.length !== 4) return undefined;
  const [west, south, east, north] = rectangle;
  if (west === undefined || south === undefined || east === undefined || north === undefined) {
    return undefined;
  }
  return {
    type: "Feature",
    properties: { source: "scenario_revision" },
    geometry: {
      type: "Polygon",
      coordinates: [[
        [west, south], [east, south], [east, north], [west, north], [west, south],
      ]],
    },
  };
}

function setSource(map: maplibregl.Map, id: string, data: FeatureCollection) {
  map.getSource<GeoJSONSource>(id)?.setData(data);
}

export function WorkspaceMap({ alternatives = [], selectedRank, draft, layers, tileLayers = [] }: WorkspaceMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const datasetSourcesRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    if (!containerRef.current) return;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: neutralStyle,
      center: [37.625, 55.752],
      zoom: 13.7,
      attributionControl: false,
    });
    mapRef.current = map;
    void map.once("load", () => {
      map.addSource("constraints", { type: "geojson", data: emptyCollection });
      map.addLayer({
        id: "constraints-fill",
        source: "constraints",
        type: "fill",
        paint: { "fill-color": "#ef7047", "fill-opacity": 0.14 },
      });
      map.addLayer({
        id: "constraints-line",
        source: "constraints",
        type: "line",
        paint: { "line-color": "#df623b", "line-width": 1.8, "line-dasharray": [2, 2] },
      });
      map.addSource("corridors", { type: "geojson", data: emptyCollection });
      map.addLayer({
        id: "corridors-fill",
        source: "corridors",
        type: "fill",
        paint: {
          "fill-color": ["case", ["==", ["get", "selected"], true], "#6657df", "#9d96ce"],
          "fill-opacity": ["case", ["==", ["get", "selected"], true], 0.2, 0.09],
        },
      });
      map.addSource("routes", { type: "geojson", data: emptyCollection });
      map.addLayer({
        id: "routes-unselected",
        source: "routes",
        type: "line",
        filter: ["==", ["get", "selected"], false],
        paint: { "line-color": "#8e88a6", "line-width": 2.5, "line-opacity": 0.62, "line-dasharray": [2, 2] },
      });
      map.addLayer({
        id: "routes-selected",
        source: "routes",
        type: "line",
        filter: ["==", ["get", "selected"], true],
        paint: { "line-color": "#6554dc", "line-width": 4.5, "line-opacity": 1 },
      });
      map.addSource("endpoints", { type: "geojson", data: emptyCollection });
      map.addLayer({
        id: "endpoint-halo",
        source: "endpoints",
        type: "circle",
        paint: { "circle-radius": 9, "circle-color": "#fff", "circle-stroke-color": "#d8d5df", "circle-stroke-width": 1 },
      });
      map.addLayer({
        id: "endpoint-core",
        source: "endpoints",
        type: "circle",
        paint: { "circle-radius": 4.5, "circle-color": ["match", ["get", "kind"], "start", "#ef774b", "#6554dc"] },
      });
    });
    return () => {
      mapRef.current = null;
      map.remove();
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const sourceIds = tileLayers.map((layer) => `dataset-${layer.id}`);
    const update = () => {
      const desired = new Set(sourceIds);
      for (const sourceId of datasetSourcesRef.current) {
        if (desired.has(sourceId)) continue;
        for (const suffix of ["fill", "line", "point"]) {
          const layerId = `${sourceId}-${suffix}`;
          if (map.getLayer(layerId)) map.removeLayer(layerId);
        }
        if (map.getSource(sourceId)) map.removeSource(sourceId);
      }
      for (const sourceId of sourceIds) {
        if (map.getSource(sourceId)) continue;
        map.addSource(sourceId, {
          type: "vector",
          tiles: [`${API_BASE}/layers/${encodeURIComponent(sourceId.slice(8))}/tiles/{z}/{x}/{y}.mvt`],
          minzoom: 0,
          maxzoom: 22,
        });
        const shared = { source: sourceId, "source-layer": "canonical" } as const;
        map.addLayer({
          id: `${sourceId}-fill`, ...shared, type: "fill",
          filter: ["==", ["geometry-type"], "Polygon"],
          paint: { "fill-color": "#8d84d8", "fill-opacity": 0.14 },
          layout: { visibility: "visible" },
        });
        map.addLayer({
          id: `${sourceId}-line`, ...shared, type: "line",
          filter: ["==", ["geometry-type"], "LineString"],
          paint: { "line-color": "#777184", "line-width": 1.6, "line-opacity": 0.72 },
          layout: { visibility: "visible" },
        });
        map.addLayer({
          id: `${sourceId}-point`, ...shared, type: "circle",
          filter: ["==", ["geometry-type"], "Point"],
          paint: { "circle-color": "#6554dc", "circle-radius": 4, "circle-stroke-color": "#fff", "circle-stroke-width": 1 },
          layout: { visibility: "visible" },
        });
      }
      datasetSourcesRef.current = desired;
    };
    if (map.isStyleLoaded()) update();
    else void map.once("load", update);
    return () => {
      map.off("load", update);
    };
  }, [tileLayers]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const update = () => {
      const constraints: FeatureCollection = {
        type: "FeatureCollection",
        features: [
          ...draft.forbidden_rectangles_wgs84.map(rectangleFeature).filter((item): item is Feature<Polygon> => Boolean(item)),
          ...draft.user_forbidden_zones.map((geometry) => ({
            type: "Feature" as const,
            properties: { source: "user_layer" },
            geometry: geometry as unknown as Geometry,
          })),
        ],
      };
      setSource(map, "constraints", constraints);
      const goal = draft.goal_point_wgs84 ?? [37.64, 55.752];
      setSource(map, "endpoints", {
        type: "FeatureCollection",
        features: [
          { type: "Feature", properties: { kind: "start" }, geometry: { type: "Point", coordinates: [...draft.entry_point_wgs84] } },
          { type: "Feature", properties: { kind: "goal" }, geometry: { type: "Point", coordinates: [...goal] } },
        ],
      });
      setSource(map, "routes", {
        type: "FeatureCollection",
        features: alternatives.map((alternative) => ({
          type: "Feature",
          properties: { rank: alternative.rank, selected: alternative.rank === selectedRank, label: String.fromCharCode(64 + alternative.rank) },
          geometry: alternative.centerline_wgs84 as unknown as Geometry,
        })),
      });
      setSource(map, "corridors", {
        type: "FeatureCollection",
        features: alternatives.map((alternative) => ({
          type: "Feature",
          properties: { rank: alternative.rank, selected: alternative.rank === selectedRank },
          geometry: alternative.corridor_wgs84 as unknown as Geometry,
        })),
      });

      const selected = alternatives.find((item) => item.rank === selectedRank) ?? alternatives[0];
      const coordinates = selected?.centerline_wgs84.coordinates;
      if (Array.isArray(coordinates)) {
        const bounds = new maplibregl.LngLatBounds();
        for (const coordinate of coordinates) {
          if (Array.isArray(coordinate) && coordinate.length >= 2) {
            bounds.extend([Number(coordinate[0]), Number(coordinate[1])]);
          }
        }
        if (!bounds.isEmpty()) {
          map.fitBounds(bounds, {
            padding: 72,
            maxZoom: 15,
            duration: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 420,
          });
        }
      }
    };
    if (map.isStyleLoaded()) update();
    else void map.once("idle", update);
    return () => { map.off("idle", update); };
  }, [alternatives, draft, selectedRank]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const update = () => {
      const visibility = (visible: boolean) => visible ? "visible" : "none";
      for (const id of ["constraints-fill", "constraints-line"]) {
        if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", visibility(layers.constraints));
      }
      for (const layer of tileLayers) {
        for (const suffix of ["fill", "line", "point"]) {
          const id = `dataset-${layer.id}-${suffix}`;
          if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", visibility(layers.datasets));
        }
      }
      if (map.getLayer("corridors-fill")) map.setLayoutProperty("corridors-fill", "visibility", visibility(layers.corridors));
      for (const id of ["routes-selected", "routes-unselected"]) {
        if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", visibility(layers.routes));
      }
      for (const id of ["endpoint-halo", "endpoint-core"]) {
        if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", visibility(layers.endpoints));
      }
    };
    if (map.isStyleLoaded()) update();
    else void map.once("idle", update);
    return () => { map.off("idle", update); };
  }, [layers, tileLayers]);

  return <div ref={containerRef} className="workspace-map" aria-label="Карта проекта с трассами и ограничениями" />;
}
