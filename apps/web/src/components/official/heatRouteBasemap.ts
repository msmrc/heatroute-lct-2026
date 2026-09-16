import type { Map as MapLibreMap } from "maplibre-gl";

const configuredStyleUrl: unknown = import.meta.env.VITE_BASEMAP_STYLE_URL;

/** The same vector basemap used by the local GdeBenzin project. */
export const BASEMAP_STYLE_URL = typeof configuredStyleUrl === "string" && configuredStyleUrl.length > 0
  ? configuredStyleUrl
  : "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

const RUSSIAN_LABEL = ["coalesce", ["get", "name:ru"], ["get", "name"], ["get", "name:latin"]];
const ROAD_FILL = { motorway: "#f4a62a", trunk: "#f7b44e", primary: "#fbc96c", secondary: "#fce1a6", minor: "#ffffff" } as const;
const ROAD_CASE = { motorway: "#d8871b", trunk: "#de982f", primary: "#e6b24c", secondary: "#ead59a", minor: "#e4e0d5" } as const;
const ROAD_WIDTH = {
  motorway: [8, 1.6, 12, 4.2, 16, 15],
  trunk: [8, 1.4, 12, 3.6, 16, 13],
  primary: [9, 1.1, 12, 2.9, 16, 11],
  secondary: [10, 0.8, 13, 2.1, 16, 7.5],
  minor: [12, 0.6, 14, 1.5, 17, 6],
} as const;

type RoadTier = keyof typeof ROAD_FILL;
type BasemapLayer = {
  id: string;
  type: string;
  minzoom?: number;
  maxzoom?: number;
  layout?: Record<string, unknown>;
  "source-layer"?: string;
};

function roadTier(id: string): RoadTier {
  if (/(^|_)mot(orway)?(_|$)/i.test(id)) return "motorway";
  if (/trunk/i.test(id)) return "trunk";
  if (/(^|_)pri(mary)?(_|$)/i.test(id)) return "primary";
  if (/(^|_)(sec(ondary)?|ter(tiary)?)(_|$)/i.test(id)) return "secondary";
  return "minor";
}

function expandedCasingWidth(widths: readonly number[]): number[] {
  return widths.map((value, index) => index % 2 === 1 ? value + 2 : value);
}

/** Port of the fast MapLibre styling from GdeBenzin. */
export function applyGdeBenzinBasemapStyle(map: MapLibreMap): void {
  const layers = (map.getStyle().layers ?? []) as BasemapLayer[];

  for (const layer of layers) {
    const sourceLayer = layer["source-layer"];
    const setPaint = (property: string, value: unknown) => map.setPaintProperty(layer.id, property, value);
    const setLayout = (property: string, value: unknown) => map.setLayoutProperty(layer.id, property, value);

    if (layer.type === "background") setPaint("background-color", "#f6f5ef");
    if (sourceLayer === "water" && layer.type === "fill") setPaint("fill-color", "#cfe2ea");
    if (sourceLayer === "waterway" && layer.type === "line") setPaint("line-color", "#bbd4df");
    if (sourceLayer === "park" && layer.type === "fill") {
      setPaint("fill-color", "#d9eac6");
      setPaint("fill-opacity", 0.85);
    }
    if (layer.id === "landuse_residential") {
      setPaint("fill-color", "#efebe1");
      setPaint("fill-opacity", 0.8);
    }
    if (layer.id === "landcover_wood") {
      setPaint("fill-color", "#d2e4bf");
      setPaint("fill-opacity", 0.86);
    }
    if (sourceLayer === "building" && layer.type === "fill") {
      setPaint("fill-color", "#e8e3d6");
      setPaint("fill-outline-color", "#dbd4c4");
      setPaint("fill-opacity", 0.76);
    }

    const isRoad = layer.type === "line"
      && sourceLayer === "transportation"
      && !/rail|ferry|funicular|pier|aeroway|path|steps|cable|track/i.test(layer.id);
    if (isRoad) {
      const tier = roadTier(layer.id);
      const casing = /case|casing|outline/i.test(layer.id);
      const widths = ROAD_WIDTH[tier];
      setPaint("line-color", casing ? ROAD_CASE[tier] : ROAD_FILL[tier]);
      setPaint("line-width", ["interpolate", ["linear"], ["zoom"], ...(casing ? expandedCasingWidth(widths) : widths)]);
      const earlier = tier === "motorway" || tier === "trunk" ? 2 : tier === "primary" ? 1.6 : tier === "secondary" ? 1.2 : 0.6;
      map.setLayerZoomRange(layer.id, Math.max(0, (layer.minzoom ?? 0) - earlier), layer.maxzoom ?? 24);
      setLayout("visibility", "visible");
    }

    if (layer.type === "symbol") {
      const hasText = layer.layout?.["text-field"] !== undefined;
      if (!hasText) continue;
      const shield = /shield|(^|[_\- ])ref([_\- ]|$)|junction|exit/i.test(layer.id);
      if (!shield) setLayout("text-field", RUSSIAN_LABEL);
      setPaint("text-color", sourceLayer === "transportation_name" ? "#5b6068" : "#62676f");
      setPaint("text-halo-color", "#ffffff");
      setPaint("text-halo-width", 1.5);
      setPaint("text-halo-blur", 0.25);
    }
  }
}
