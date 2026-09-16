import type { FeatureCollection, LineString, Point } from "geojson";

import type { OfficialOutputFeatureCollection } from "../../shared/api";

type JsonProperties = Record<string, string | number | boolean | null>;
export type MapFeatureCollection = FeatureCollection<Point | LineString, JsonProperties>;

export function officialRouteFeatureCollection(
  output: OfficialOutputFeatureCollection,
  variantId: string,
): MapFeatureCollection {
  const features: MapFeatureCollection["features"] = [];
  for (const candidate of output.features) {
    if (!candidate || typeof candidate !== "object") continue;
    const feature = candidate as {
      type?: unknown;
      geometry?: { type?: unknown; coordinates?: unknown } | null;
      properties?: Record<string, unknown>;
    };
    const properties = feature.properties;
    const geometry = feature.geometry;
    if (feature.type !== "Feature" || !properties || properties.variant_id !== variantId || !geometry) continue;
    if (geometry.type !== "Point" && geometry.type !== "LineString") continue;
    const objectType = typeof properties.object_type === "string" ? properties.object_type : "";
    if (!objectType || objectType === "variant_summary") continue;
    const id = text(properties.id);
    const common: JsonProperties = {
      object_type: objectType,
      feature_id: id,
      cost: number(properties.cost),
      diameter: number(properties.diameter) ?? number(properties.required_diameter),
      existing_diameter: number(properties.existing_diameter),
      flow_tph: number(properties.flow_tph) ?? number(properties.calculated_flow_tph),
      added_flow_tph: number(properties.added_flow_tph),
      length_m: number(properties.length),
    };
    if (geometry.type === "LineString") {
      const reconstruction = objectType === "heat_network_reconstruction";
      features.push({
        type: "Feature",
        geometry: { type: "LineString", coordinates: geometry.coordinates as number[][] },
        properties: {
          ...common,
          map_layer: reconstruction ? "calculated_reconstruction" : "calculated_route",
          route_kind: properties.laying_method === "special"
            ? "special"
            : id.includes(":trunk:") ? "trunk" : "branch",
          label: reconstruction
            ? `Реконструкция участка ${text(properties.existing_object_id) || id}`
            : properties.laying_method === "special" ? "Специальный переход" : "Расчётный участок",
        },
      });
      continue;
    }
    const reconstruction = objectType === "heat_chamber_reconstruction";
    const label = objectType === "tie_in" ? "Точка врезки"
      : objectType === "heat_chamber" ? "Новая тепловая камера"
        : reconstruction ? "Реконструкция тепловой камеры" : "Технический узел";
    features.push({
      type: "Feature",
      geometry: { type: "Point", coordinates: geometry.coordinates as number[] },
      properties: {
        ...common,
        map_layer: reconstruction ? "calculated_reconstruction_chamber" : "calculated_node",
        node_type: objectType,
        label,
        root: objectType === "tie_in",
      },
    });
  }
  return { type: "FeatureCollection", features };
}

function number(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}
