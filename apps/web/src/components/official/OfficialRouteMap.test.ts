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
        feature("heat_chamber_reconstruction", "variant-1", { type: "Point", coordinates: [37.59, 55.69] }, { id: "variant-1:chamber:1", required_diameter: 125, existing_diameter: 80 }),
        feature("variant_summary", "variant-1", null, { id: "summary:variant-1", rank: 1 }),
        feature("heat_network", "variant-2", {
          type: "LineString",
          coordinates: [[38, 56], [38.1, 56.1]],
        }, { id: "variant-2:network:1", laying_method: "base", length: 1 }),
      ],
    };

    const map = officialRouteFeatureCollection(output, "variant-1");

    expect(map.features).toHaveLength(4);
    expect(map.features.map((item) => item.properties.map_layer)).toEqual([
      "calculated_route",
      "calculated_reconstruction",
      "calculated_node",
      "calculated_reconstruction_chamber",
    ]);
    expect(map.features[0]?.properties).toMatchObject({
      route_kind: "special",
      length_m: 120,
      diameter: 100,
      flow_tph: 12,
    });
    expect(map.features[2]?.properties).toMatchObject({ root: true, label: "Точка врезки" });
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
