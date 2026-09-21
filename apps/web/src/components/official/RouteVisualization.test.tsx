import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { OfficialCalculationResult } from "../../shared/api";
import { RouteVisualization } from "./RouteVisualization";

vi.mock("./OfficialRouteMap", () => ({
  OfficialRouteMap: ({ variant }: { variant: { id: string } }) => <div>Интерактивная карта {variant.id}</div>,
}));

const result: OfficialCalculationResult = {
  algorithm_version: "test",
  demand_count: 2,
  preferred_variant_id: "balanced",
  variants: [
    {
      id: "shortest",
      strategy: "shortest",
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
      id: "balanced",
      strategy: "balanced",
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
      edges: [{
        id: "balanced:trunk:1",
        length_m: 900,
        upstream_node_id: "root",
        downstream_node_id: "demand",
        diameter: 100,
        depth_profile: {
          complete: true,
          points: [
            { station_m: 0, depth_m: 3 },
            { station_m: 440, depth_m: 3 },
            { station_m: 448, depth_m: 3.8 },
            { station_m: 452, depth_m: 3.8 },
            { station_m: 460, depth_m: 3 },
            { station_m: 900, depth_m: 3 },
          ],
          crossings: [{
            crossing_id: "gas-1",
            crossing_type: "gas_pipeline",
            passage: "below",
            depth_m: 3.8,
            ramp_start_m: 440,
            plateau_start_m: 448,
            plateau_end_m: 452,
            ramp_end_m: 460,
            vertical_clearance_m: .6,
            required_clearance_m: .2,
          }],
          issues: [],
          profile_length_3d_m: 900.08,
          depth_adjusted_cost_meters: 901.4,
        },
      }],
    },
  ],
};

describe("RouteVisualization", () => {
  it("opens on the preferred variant and exposes no-route diagnostics when switched", async () => {
    const { container } = render(
      <RouteVisualization
        result={result}
        runId="run-1"
        importId="import-1"
        warnings={[{
          code: "FIELD_DEFAULTED",
          feature_index: 7,
          feature_id: "oks-7",
          field: "height",
          message: "Значение высоты восстановлено по умолчанию",
        }]}
      />,
    );

    expect(screen.getByRole("tab", { name: /Общая сеть/ }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByText(/2 из 2 ОКС/)).toBeTruthy();
    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    expect(container.querySelector(".route-results-drawer")?.parentElement?.classList.contains("route-map-stage")).toBe(true);
    expect(container.querySelector(".route-workspace-inspector")?.parentElement?.classList.contains("route-map-stage")).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: /Профиль/ }));
    expect(screen.getByText("Продольный профиль")).toBeTruthy();
    expect(screen.getByRole("img", { name: /Продольный профиль участка balanced:trunk:1/ })).toBeTruthy();
    expect(screen.getByText("Газопровод")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Карта/ }));
    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Раздельные трассы/ }));
    expect(await screen.findByText("Интерактивная карта shortest")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Общая сеть/ }));

    const validationTrigger = screen.getByRole("button", { name: /Открыть результаты проверки/ });
    validationTrigger.focus();
    fireEvent.click(validationTrigger);
    expect(screen.getByRole("dialog", { name: "Результаты проверки" })).toBeTruthy();
    expect(screen.getByText("Значение высоты восстановлено по умолчанию")).toBeTruthy();
    expect(screen.getByText(/1 объект · примеры ID: oks-7 · поле height/)).toBeTruthy();
    const closeButtons = screen.getAllByRole("button", { name: "Закрыть" });
    expect(document.activeElement).toBe(closeButtons[0]);
    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(closeButtons.at(-1));
    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(closeButtons[0]);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(validationTrigger);

    const selectedVariantTab = screen.getByRole("tab", { name: /Общая сеть/ });
    selectedVariantTab.focus();
    fireEvent.keyDown(selectedVariantTab, { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: /Раздельные трассы/ }).getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(screen.getByRole("tab", { name: /Раздельные трассы/ }));
    expect(screen.getByText(/ОКС 2: Маршрут не найден/)).toBeTruthy();
  });

  it("groups missing reconstruction inputs into one localized warning", () => {
    const withReconstructionWarnings: OfficialCalculationResult = {
      ...result,
      variants: result.variants.map((variant) => variant.id !== "balanced" ? variant : {
        ...variant,
        reconstruction: {
          available: false,
          network_sections: [],
          chambers: [],
          issues: [
            { code: "RECONSTRUCTION_INPUT_UNAVAILABLE", subject_id: "101", message: "missing" },
            { code: "RECONSTRUCTION_INPUT_UNAVAILABLE", subject_id: "102", message: "missing" },
          ],
        },
      }),
    };

    render(<RouteVisualization result={withReconstructionWarnings} runId="run-1" importId="import-1" />);

    fireEvent.click(screen.getByRole("button", { name: /1 предупреждение/ }));
    expect(screen.getByText("Недостаточно данных для реконструкции")).toBeTruthy();
    expect(screen.getByText(/Результат реконструкции не рассчитывался/)).toBeTruthy();
    expect(screen.getByText(/Затронуто участков: 2 · ID 101, 102/)).toBeTruthy();
  });

  it("groups repeated actionable input warnings", () => {
    render(
      <RouteVisualization
        result={result}
        runId="run-1"
        importId="import-1"
        warnings={[1, 2, 3].map((id) => ({
          code: "MISSING_EXISTING_NETWORK_LINK",
          feature_index: id - 1,
          feature_id: String(id),
          field: "upstream_object_id",
          message: "upstream_object_id is absent; connectivity will be inferred geometrically",
        }))}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /3 предупреждения/ }));
    expect(screen.getByText("Нет направления существующей сети")).toBeTruthy();
    expect(screen.getByText(/3 объекта · примеры ID: 1, 2, 3/)).toBeTruthy();
    expect(screen.queryByText("Numeric identifier is normalized to its decimal string representation")).toBeNull();
  });
});
