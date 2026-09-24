import { describe, expect, it } from "vitest";

import type { OfficialOutputFeatureCollection } from "../../shared/api";
import { officialRouteFeatureCollection } from "./officialOutputMap";

describe("officialRouteFeatureCollection", () => {
  it("renders the strict output types and omits the non-spatial summary", () => {
    const output: OfficialOutputFeatureCollection = {
      type: "FeatureCollection",
      features: [
        feature("heat_network", "variant-1", {
          type: "LineString",
          coordinates: [[37.6, 55.7], [37.61, 55.71]],
        }, { id: "variant-1:network:1", laying_method: "special", length: 120, diameter: 100, flow_tph: 12, cost: 10 }),
        feature("heat_network_reconstruction", "variant-1", {
          type: "LineString",
          coordinates: [[37.59, 55.69], [37.6, 55.7]],
        }, { id: "variant-1:reconstruction:1", existing_object_id: "network-1", length: 80, required_diameter: 125, calculated_flow_tph: 15 }),
        feature("tie_in", "variant-1", { type: "Point", coordinates: [37.6, 55.7] }, { id: "variant-1:tie:1", required_diameter: 100 }),
        feature("heat_chamber", "variant-1", { type: "Point", coordinates: [37.605, 55.705] }, { id: "variant-1:chamber:new", diameter: 100 }),
        feature("technical_node", "variant-1", { type: "Point", coordinates: [37.607, 55.707] }, { id: "variant-1:demand:oks-1" }),
        feature("technical_node", "variant-1", { type: "Point", coordinates: [37.608, 55.708] }, { id: "variant-1:depth:20" }),
        feature("technical_node", "variant-1", { type: "Point", coordinates: [37.609, 55.709] }, { id: "variant-1:tie:chamber:chamber-1" }),
        feature("technical_node", "variant-1", { type: "Point", coordinates: [37.61, 55.71] }, { id: "variant-1:technical:corridor:tie:chamber:chamber-1:section:20.000" }),
        feature("heat_chamber_reconstruction", "variant-1", { type: "Point", coordinates: [37.59, 55.69] }, { id: "variant-1:chamber:1", required_diameter: 125, existing_diameter: 80 }),
        feature("variant_summary", "variant-1", null, { id: "summary:variant-1", rank: 1 }),
        feature("heat_network", "variant-2", {
          type: "LineString",
          coordinates: [[38, 56], [38.1, 56.1]],
        }, { id: "variant-2:network:1", laying_method: "base", length: 1 }),
      ],
    };

    const map = officialRouteFeatureCollection(output, "variant-1");

    expect(map.features).toHaveLength(9);
    expect(map.features.map((item) => item.properties.map_layer)).toEqual([
      "calculated_route",
      "calculated_reconstruction",
      "calculated_node",
      "calculated_node",
      "calculated_node",
      "calculated_node",
      "calculated_node",
      "calculated_node",
      "calculated_reconstruction_chamber",
    ]);
    expect(map.features[0]?.properties).toMatchObject({
      route_kind: "special",
      length_m: 120,
      diameter: 100,
      flow_tph: 12,
    });
    expect(map.features[2]?.properties).toMatchObject({ root: true, point_role: "tie_in", label: "Точка врезки" });
    expect(map.features[3]?.properties).toMatchObject({ point_role: "new_chamber", label: "Новая тепловая камера" });
    expect(map.features[4]?.properties).toMatchObject({ point_role: "demand_connection", label: "Подключение ОКС" });
    expect(map.features[5]?.properties).toMatchObject({ point_role: "technical_node", label: "Технический узел" });
    expect(map.features[6]?.properties).toMatchObject({ point_role: "tie_in", label: "Точка врезки" });
    expect(map.features[7]?.properties).toMatchObject({ point_role: "technical_node", label: "Технический узел" });
    expect(map.features[8]?.properties).toMatchObject({ point_role: "reconstruction_chamber" });
  });
});

function feature(
  objectType: string,
  variantId: string,
  geometry: { type: "Point" | "LineString"; coordinates: number[] | number[][] } | null,
  properties: Record<string, string | number>,
) {
  return {
    type: "Feature",
    geometry,
    properties: { object_type: objectType, variant_id: variantId, ...properties },
  };
}
