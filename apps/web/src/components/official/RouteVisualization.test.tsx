import { fireEvent, render, screen, within } from "@testing-library/react";
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
      strategy: "engineering",
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
  it("initially shows the server preference when fully connected and explicitly free of engineering issues", async () => {
    const ranked = rankedResult();
    ranked.variants = ranked.variants.map((variant) => ({ ...variant, engineering_issues: [] }));
    render(<RouteVisualization result={ranked} runId="run-preferred" importId="import-1" />);

    expect(await screen.findByText("Интерактивная карта cheapest")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Приоритет стоимости.*место 1/ }).getAttribute("aria-selected")).toBe("true");
    expect(ranked.variants.map((variant) => variant.rank)).toEqual([1, 2]);
  });

  it.each(["invalid", "fewer-connections", "missing-assessment", "missing"])("keeps the engineering fallback when preference is %s", async (scenario) => {
    const ranked = rankedResult();
    ranked.variants = ranked.variants.flatMap((variant) => {
      if (variant.id !== ranked.preferred_variant_id) return [variant];
      if (scenario === "missing") return [];
      return [{
        ...variant,
        valid: scenario !== "invalid",
        connected_demand_count: scenario === "fewer-connections" ? 1 : 2,
        engineering_issues: scenario === "missing-assessment" ? undefined : [],
      }];
    });
    render(<RouteVisualization result={ranked} runId="run-preferred" importId="import-1" />);

    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Инженерная трасса/ }).getAttribute("aria-selected")).toBe("true");
  });

  it("initially shows full engineering geometry without changing the server ranking", async () => {
    const ranked = rankedResult();
    render(<RouteVisualization result={ranked} runId="run-ranked" importId="import-1" />);

    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Инженерная трасса.*место 2/ }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByRole("tab", { name: /Приоритет стоимости.*место 1/ })).toBeTruthy();
    expect(screen.queryByText("Лучший по целевому показателю")).toBeNull();
    expect(screen.getByText("Вариант с приоритетом инженерной геометрии. Оставшиеся замечания приведены ниже. Глобальный минимум стоимости или длины не гарантируется.")).toBeTruthy();
    expect(screen.getByText(/3 поворотов вне экспертного диапазона/)).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: /Приоритет стоимости/ }));

    expect(await screen.findByText("Интерактивная карта cheapest")).toBeTruthy();
    expect(screen.getByText("Лучший по целевому показателю")).toBeTruthy();
    expect(screen.getByText(/17 поворотов вне экспертного диапазона/)).toBeTruthy();
    expect(ranked.preferred_variant_id).toBe("cheapest");
    expect(ranked.variants.map((variant) => variant.rank)).toEqual([1, 2]);
  });

  it.each(["invalid", "fewer-connections", "missing"])("falls back to the server preference when engineering is %s", async (scenario) => {
    const ranked = rankedResult();
    ranked.variants = ranked.variants.flatMap((variant) => {
      if (variant.strategy !== "engineering") return [variant];
      if (scenario === "missing") return [];
      return [{
        ...variant,
        valid: scenario !== "invalid",
        connected_demand_count: scenario === "fewer-connections" ? 1 : 2,
      }];
    });

    render(<RouteVisualization result={ranked} runId="run-ranked" importId="import-1" />);

    expect(await screen.findByText("Интерактивная карта cheapest")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Приоритет стоимости/ }).getAttribute("aria-selected")).toBe("true");
  });

  it("does not imply engineering regularity when a cost-priority result has no reported issues", async () => {
    const ranked = rankedResult();
    ranked.variants = ranked.variants.map((variant) => ({ ...variant, engineering_issues: [] }));
    render(<RouteVisualization result={ranked} runId="run-ranked" importId="import-1" />);

    fireEvent.click(screen.getByRole("tab", { name: /Приоритет стоимости/ }));

    expect(await screen.findByText("Интерактивная карта cheapest")).toBeTruthy();
    expect(screen.getByText(/углы могут быть нерегулярными\. Отсутствие ошибок обязательной проверки не гарантирует инженерную регулярность/)).toBeTruthy();
    expect(screen.getByText(/Глобальный минимум стоимости или длины не гарантируется/)).toBeTruthy();
    expect(screen.getByRole("button", { name: /0 ошибок, 0 предупреждений/ })).toBeTruthy();
    expect(screen.queryByText("Есть инженерные замечания")).toBeNull();
    expect(screen.queryByRole("tab", { name: /Самый дешёвый|Самый короткий/ })).toBeNull();
    expect(screen.getByText("Лучший по целевому показателю")).toBeTruthy();
  });

  it("preserves a manual choice on same-run refetch and resets it for a new keyed run", async () => {
    const ranked = rankedResult();
    ranked.variants = ranked.variants.map((variant) => ({ ...variant, engineering_issues: [] }));
    const { rerender } = render(
      <RouteVisualization key="run-1" result={ranked} runId="run-1" importId="import-1" />,
    );
    expect(await screen.findByText("Интерактивная карта cheapest")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Инженерная трасса/ }));

    rerender(<RouteVisualization key="run-1" result={structuredClone(ranked)} runId="run-1" importId="import-1" />);

    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Инженерная трасса/ }).getAttribute("aria-selected")).toBe("true");

    rerender(<RouteVisualization key="run-2" result={structuredClone(ranked)} runId="run-2" importId="import-1" />);

    expect(await screen.findByText("Интерактивная карта cheapest")).toBeTruthy();
  });

  it("distinguishes chambers, connection sites and charged rays without counting technical nodes", () => {
    const balanced = result.variants[1]!;
    const root = balanced.nodes[0]!;
    const metricResult: OfficialCalculationResult = {
      ...result,
      variants: [{
        ...balanced,
        nodes: [
          ...balanced.nodes,
          { ...root, id: "branch", root: false, node_type: "new_branch_chamber" },
          { ...root, id: "technical", root: false, chamber: false, node_type: "technical_node" },
          { ...root, id: "new-tie-a", target_id: "same-network-section", node_type: "new_tie_in_chamber", coordinate: { xm: 20, ym: 0 } },
          { ...root, id: "new-tie-b", target_id: "same-network-section", node_type: "new_tie_in_chamber", coordinate: { xm: 40, ym: 0 } },
          { ...root, id: "unused-root", target_id: "unused-chamber" },
        ],
        edges: [
          { id: "existing-ray-1", length_m: 10, upstream_node_id: root.id, downstream_node_id: "branch" },
          { id: "existing-ray-2", length_m: 10, upstream_node_id: root.id, downstream_node_id: "demand" },
          { id: "branch-ray", length_m: 10, upstream_node_id: "branch", downstream_node_id: "technical" },
          { id: "new-ray-a", length_m: 10, upstream_node_id: "new-tie-a", downstream_node_id: "demand" },
          { id: "new-ray-b", length_m: 10, upstream_node_id: "new-tie-b", downstream_node_id: "demand" },
        ],
      }],
    };

    render(<RouteVisualization result={metricResult} runId="run-1" importId="import-1" />);

    function metric(label: string) {
      return screen.getByText(label, { selector: "dt" }).parentElement!.querySelector("dd")!.textContent;
    }
    expect(metric("Камеры ветвления")).toBe("1");
    expect(metric("Новые камеры врезки")).toBe("2");
    expect(metric("Места подключения")).toBe("3");
    expect(metric("Врезки — новые лучи")).toBe("4");
    expect(screen.queryByText("Камер и врезок")).toBeNull();
    expect(screen.getByText("Одно место подключения может иметь несколько новых лучей.")).toBeTruthy();
  });

  it("opens on the preferred variant and exposes no-route diagnostics when switched", async () => {
    const resultWithEngineeringWarning: OfficialCalculationResult = {
      ...result,
      variants: result.variants.map((variant) => variant.id !== "balanced" ? variant : {
        ...variant,
        engineering_issues: [{
          code: "EXPERT_BEND_ANGLE_OUT_OF_RANGE",
          subject_id: "balanced:trunk:1",
          message: "2 bend angles are outside the expert 90-135 degree range",
        }],
      }),
    };
    const { container } = render(
      <RouteVisualization
        result={resultWithEngineeringWarning}
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

    expect(screen.getByRole("tab", { name: /Инженерная трасса/ }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByText(/2 из 2 ОКС/)).toBeTruthy();
    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    const resultsDrawer = container.querySelector(".route-results-drawer");
    expect(resultsDrawer?.parentElement?.classList.contains("route-map-stage")).toBe(true);
    expect(within(resultsDrawer as HTMLElement).getByText("Длина сети")).toBeTruthy();
    expect(within(resultsDrawer as HTMLElement).getByText("900 м")).toBeTruthy();
    expect(within(resultsDrawer as HTMLElement).getByText("2 из 2")).toBeTruthy();
    expect(within(resultsDrawer as HTMLElement).queryByText("0 м")).toBeNull();
    expect(container.querySelector(".route-workspace-inspector")?.parentElement?.classList.contains("route-map-stage")).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: /Профиль/ }));
    expect(screen.getByText("Продольный профиль")).toBeTruthy();
    expect(screen.getByRole("img", { name: /Продольный профиль участка balanced:trunk:1/ })).toBeTruthy();
    expect(screen.getByText("Газопровод")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Карта/ }));
    expect(await screen.findByText("Интерактивная карта balanced")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Приоритет длины/ }));
    expect(await screen.findByText("Интерактивная карта shortest")).toBeTruthy();
    expect(screen.getByText(/После инженерной обработки он может быть длиннее других\. Глобальный минимум/)).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Инженерная трасса/ }));

    const validationTrigger = screen.getByRole("button", { name: /Открыть результаты проверки/ });
    validationTrigger.focus();
    fireEvent.click(validationTrigger);
    expect(screen.getByRole("dialog", { name: "Результаты проверки" })).toBeTruthy();
    expect(screen.getByText("Инженерная геометрия")).toBeTruthy();
    expect(screen.getAllByText(/2 поворотов вне экспертного диапазона/)).toHaveLength(2);
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

    const selectedVariantTab = screen.getByRole("tab", { name: /Инженерная трасса/ });
    selectedVariantTab.focus();
    fireEvent.keyDown(selectedVariantTab, { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: /Приоритет длины/ }).getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(screen.getByRole("tab", { name: /Приоритет длины/ }));
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

function rankedResult(): OfficialCalculationResult {
  const engineering = result.variants[1]!;
  return {
    ...result,
    preferred_variant_id: "cheapest",
    variants: [
      {
        ...engineering,
        id: "cheapest",
        strategy: "cheapest",
        rank: 1,
        engineering_issues: [{ code: "EXPERT_BEND_ANGLE_OUT_OF_RANGE", message: "17 bend angles are outside the expert 90-135 degree range" }],
      },
      {
        ...engineering,
        rank: 2,
        engineering_issues: [{ code: "EXPERT_BEND_ANGLE_OUT_OF_RANGE", message: "3 bend angles are outside the expert 90-135 degree range" }],
      },
    ],
  };
}
