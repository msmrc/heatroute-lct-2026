import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { OfficialRouteVariant } from "../../shared/api";
import { OfficialRouteMap } from "./OfficialRouteMap";

const api = vi.hoisted(() => ({
  getOfficialMap: vi.fn(),
  getOfficialVariantOutput: vi.fn(),
}));

const maplibre = vi.hoisted(() => {
  const layers = new Set<string>();
  const map = {
    addControl: vi.fn(),
    addLayer: vi.fn((layer: { id: string }) => layers.add(layer.id)),
    addSource: vi.fn(),
    fitBounds: vi.fn(),
    getCanvas: vi.fn(() => ({ style: {} })),
    getLayer: vi.fn((id: string) => layers.has(id) ? { id } : undefined),
    getStyle: vi.fn(() => ({ layers: [] })),
    isStyleLoaded: vi.fn(() => true),
    on: vi.fn(),
    once: vi.fn((event: string, listener: () => void) => {
      if (event === "style.load") listener();
      return map;
    }),
    queryRenderedFeatures: vi.fn(() => []),
    remove: vi.fn(),
    resize: vi.fn(),
    setLayerZoomRange: vi.fn(),
    setLayoutProperty: vi.fn(),
    setPaintProperty: vi.fn(),
  };
  return {
    layers,
    map,
    Map: vi.fn(function Map() { return map; }),
    NavigationControl: vi.fn(function NavigationControl() {}),
    ScaleControl: vi.fn(function ScaleControl() {}),
  };
});

vi.mock("../../shared/api", async (loadOriginal) => ({
  ...await loadOriginal<typeof import("../../shared/api")>(),
  ...api,
}));

vi.mock("maplibre-gl", () => ({
  default: {
    Map: maplibre.Map,
    NavigationControl: maplibre.NavigationControl,
    ScaleControl: maplibre.ScaleControl,
  },
}));

beforeEach(() => {
  maplibre.layers.clear();
  vi.clearAllMocks();
  vi.stubGlobal("ResizeObserver", class ResizeObserver {
    observe() {}
    disconnect() {}
  });
  api.getOfficialMap.mockResolvedValue({ type: "FeatureCollection", features: [], truncated: false });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("OfficialRouteMap lifecycle", () => {
  it("adds the calculated overlay on style.load without waiting for remote tiles", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <OfficialRouteMap runId="run-1" importId="import-1" variant={variant()} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(maplibre.Map).toHaveBeenCalledTimes(1));
    expect(maplibre.map.once).toHaveBeenCalledWith("style.load", expect.any(Function));
    expect(maplibre.map.once).not.toHaveBeenCalledWith("load", expect.any(Function));
    expect(maplibre.layers).toContain("route-line");
    expect(maplibre.map.fitBounds).toHaveBeenCalledTimes(1);

    unmount();
  });
});

function variant(): OfficialRouteVariant {
  return {
    id: "independent",
    strategy: "independent",
    valid: true,
    nodes: [
      { id: "demand", node_type: "demand_connection", coordinate: { xm: 413_000, ym: 6_174_000 }, root: false, chamber: false, base_incident_sections: 0 },
      { id: "tie", node_type: "new_tie_in_chamber", coordinate: { xm: 413_100, ym: 6_174_100 }, root: true, chamber: true, base_incident_sections: 0 },
    ],
    edges: [{
      id: "edge-1",
      upstream_node_id: "tie",
      downstream_node_id: "demand",
      length_m: 141.4,
      coordinates: [{ xm: 413_100, ym: 6_174_100 }, { xm: 413_000, ym: 6_174_000 }],
    }],
    connections: [],
    total_length_m: 141.4,
    validation_issues: [],
    sizing_issues: [],
    connected_demand_count: 1,
    no_route_demand_count: 0,
  };
}
