import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { OfficialRouteVariant } from "../../shared/api";
import { OfficialRouteMap } from "./OfficialRouteMap";

const api = vi.hoisted(() => ({
  getOfficialMap: vi.fn(),
  getOfficialVariantOutput: vi.fn(),
}));

const maplibre = vi.hoisted(() => {
  const layers = new Set<string>();
  let styleLayers: Array<{ id: string; type: string }> = [];
  let styleLoadListener: (() => void) | undefined;
  const map = {
    addControl: vi.fn(),
    addLayer: vi.fn((layer: { id: string; type: string }) => {
      layers.add(layer.id);
      styleLayers.push(layer);
    }),
    addSource: vi.fn(),
    fitBounds: vi.fn(),
    getCanvas: vi.fn(() => ({ style: {} })),
    getLayer: vi.fn((id: string) => layers.has(id) ? { id } : undefined),
    getStyle: vi.fn(() => ({ layers: styleLayers })),
    isStyleLoaded: vi.fn(() => true),
    on: vi.fn((event: string, listener: () => void) => {
      if (event === "style.load") {
        styleLoadListener = listener;
        listener();
      }
      return map;
    }),
    queryRenderedFeatures: vi.fn(() => []),
    remove: vi.fn(),
    resize: vi.fn(),
    setStyle: vi.fn((style: { layers?: Array<{ id: string; type: string }> }) => {
      layers.clear();
      styleLayers = [...(style.layers ?? [])];
      styleLoadListener?.();
      return map;
    }),
    setLayerZoomRange: vi.fn(),
    setLayoutProperty: vi.fn(),
    setPaintProperty: vi.fn(),
  };
  return {
    layers,
    map,
    Map: vi.fn(function Map(options: { style: { layers?: Array<{ id: string; type: string }> } }) {
      styleLayers = [...(options.style.layers ?? [])];
      return map;
    }),
    NavigationControl: vi.fn(function NavigationControl() {}),
    ScaleControl: vi.fn(function ScaleControl() {}),
  };
});

vi.mock("../../shared/api", async (loadOriginal) => ({
  ...await loadOriginal<typeof import("../../shared/api")>(),
  ...api,
}));

vi.mock("maplibre-gl", () => ({
  Map: maplibre.Map,
  NavigationControl: maplibre.NavigationControl,
  ScaleControl: maplibre.ScaleControl,
  setWorkerUrl: vi.fn(),
}));

vi.mock("maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url", () => ({ default: "/maplibre-worker.js" }));

beforeEach(() => {
  maplibre.layers.clear();
  vi.clearAllMocks();
  vi.stubGlobal("ResizeObserver", class ResizeObserver {
    observe() {}
    disconnect() {}
  });
  api.getOfficialMap.mockResolvedValue({ type: "FeatureCollection", features: [], truncated: false });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({
      version: 8,
      sources: {},
      layers: [{ id: "remote-background", type: "background" }],
    }),
  }));
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
    expect(maplibre.map.on).toHaveBeenCalledWith("style.load", expect.any(Function));
    expect(maplibre.map.on).not.toHaveBeenCalledWith("load", expect.any(Function));
    expect(maplibre.layers).toContain("route-line");
    expect(maplibre.map.fitBounds).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(maplibre.map.setStyle).toHaveBeenCalledTimes(1));
    expect(maplibre.layers).toContain("route-line");

    unmount();
  });

  it("keeps calculated routes visible when the external basemap is unavailable", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new Error("offline"));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <OfficialRouteMap runId="run-1" importId="import-1" variant={variant()} />
      </QueryClientProvider>,
    );

    expect(await screen.findByText(/Фоновая карта недоступна/)).toBeTruthy();
    expect(maplibre.layers).toContain("route-line");
    expect(maplibre.map.setStyle).not.toHaveBeenCalled();

    unmount();
  });

  it("keeps technical nodes inspectable without rendering them as chambers", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <OfficialRouteMap runId="run-1" importId="import-1" variant={variantWithTechnicalNode()} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(maplibre.layers).toContain("route-nodes"));
    const routeNodes = maplibre.map.addLayer.mock.calls
      .map(([layer]) => layer as { id: string; paint?: Record<string, unknown> })
      .find((layer) => layer.id === "route-nodes");

    expect(routeNodes?.paint?.["circle-radius"]).toEqual([
      "match",
      ["get", "node_type"],
      "technical_node", 2,
      "demand_connection", 5,
      "new_branch_chamber", 7,
      "new_tie_in_chamber", 6,
      "existing_chamber_tie_in", 6,
      2,
    ]);
    const colorExpression = JSON.stringify(routeNodes?.paint?.["circle-color"]);
    expect(colorExpression).toContain("technical_node");
    expect(colorExpression).toContain("#8b949e");
    expect(colorExpression).toContain("demand_connection");
    expect(colorExpression).toContain("#45a55a");

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

function variantWithTechnicalNode(): OfficialRouteVariant {
  return {
    ...variant(),
    nodes: [
      ...variant().nodes,
      { id: "technical", node_type: "technical_node", coordinate: { xm: 413_050, ym: 6_174_050 }, root: false, chamber: false, base_incident_sections: 0 },
    ],
  };
}
