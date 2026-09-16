import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { OfficialCalculationResult } from "../../shared/api";
import { RouteVisualization } from "./RouteVisualization";

vi.mock("./OfficialRouteMap", () => ({
  OfficialRouteMap: () => <div>Интерактивная карта</div>,
}));

const result: OfficialCalculationResult = {
  algorithm_version: "test",
  demand_count: 2,
  preferred_variant_id: "shared",
  variants: [
    {
      id: "independent",
      strategy: "independent",
      valid: true,
      total_length_m: 1200,
      connected_demand_count: 1,
      no_route_demand_count: 1,
      validation_issues: [],
      connections: [
        { status: "connected", flow_tph: 10, demand_id: "1", connection_point_id: "1" },
        { status: "no_route", flow_tph: 12, demand_id: "2", connection_point_id: "2", reason: "NO_ROUTE" },
      ],
      nodes: [
        { id: "root", root: true, chamber: true, node_type: "existing_chamber_tie_in", target_id: "100", coordinate: { xm: 0, ym: 0 }, base_incident_sections: 2 },
        { id: "demand", root: false, chamber: false, node_type: "demand_connection", target_id: "1", coordinate: { xm: 100, ym: 100 }, base_incident_sections: 0 },
      ],
      edges: [{ id: "edge", length_m: 1200, upstream_node_id: "root", downstream_node_id: "demand" }],
    },
    {
      id: "shared",
      strategy: "shared_trunk",
      valid: true,
      total_length_m: 900,
      connected_demand_count: 2,
      no_route_demand_count: 0,
      validation_issues: [],
      connections: [
        { status: "connected", flow_tph: 10, demand_id: "1", connection_point_id: "1" },
        { status: "connected", flow_tph: 12, demand_id: "2", connection_point_id: "2" },
      ],
      nodes: [
        { id: "root", root: true, chamber: true, node_type: "existing_chamber_tie_in", target_id: "100", coordinate: { xm: 0, ym: 0 }, base_incident_sections: 2 },
        { id: "demand", root: false, chamber: false, node_type: "demand_connection", target_id: "1", coordinate: { xm: 100, ym: 100 }, base_incident_sections: 0 },
      ],
      edges: [{ id: "shared:trunk:1", length_m: 900, upstream_node_id: "root", downstream_node_id: "demand" }],
    },
  ],
};

describe("RouteVisualization", () => {
  it("opens on the preferred variant and exposes no-route diagnostics when switched", () => {
    render(<RouteVisualization result={result} importId="import-1" />);

    expect(screen.getByRole("tab", { name: /Общая сеть/ }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByText(/2 из 2 ОКС/)).toBeTruthy();
    expect(screen.getByText("Интерактивная карта")).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: /Раздельные трассы/ }));
    expect(screen.getByText(/ОКС 2: Маршрут не найден/)).toBeTruthy();
  });
});
