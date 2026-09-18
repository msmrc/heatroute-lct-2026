import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OfficialWorkspacePage } from "./OfficialWorkspacePage";

const api = vi.hoisted(() => ({
  cancelOfficialJob: vi.fn(),
  createOfficialImport: vi.fn(),
  createOfficialRun: vi.fn(),
  createTopologyJob: vi.fn(),
  getOfficialImport: vi.fn(),
  getOfficialJob: vi.fn(),
  getLatestOfficialRun: vi.fn(),
  getOfficialRun: vi.fn(),
  officialExportUrl: vi.fn((runId: string) => `/api/v1/official/runs/${runId}/export`),
}));

vi.mock("../shared/api", () => ({
  ApiError: class ApiError extends Error {},
  ...api,
  humanFileSize: () => "228 КБ",
}));

vi.mock("../components/official/RouteVisualization", () => ({
  RouteVisualization: () => <div>Карта результатов</div>,
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe("OfficialWorkspacePage", () => {
  it("starts calculation immediately after a valid upload and skips the report screen", async () => {
    api.createOfficialImport.mockResolvedValue({
      id: "import-1",
      state: "valid",
      original_filename: "network.geojson",
      input_size_bytes: 233_000,
      created_at: "2026-09-16T00:00:00Z",
      report: {
        contract_version: "1",
        input_profile: "extended_input",
        sha256: "hash",
        feature_count: 10,
        feature_counts: { demand: 10 },
        errors: [],
        warnings: [],
        valid: true,
      },
    });
    api.createOfficialRun.mockResolvedValue({
      id: "run-1",
      import_id: "import-1",
      job_id: "job-1",
      state: "queued",
      algorithm_version: "test",
      input_sha256: "hash",
      created_at: "2026-09-16T00:00:00Z",
    });
    api.getOfficialJob.mockReturnValue(new Promise(() => undefined));
    api.getOfficialRun.mockReturnValue(new Promise(() => undefined));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <OfficialWorkspacePage />
      </QueryClientProvider>,
    );

    const input = container.querySelector<HTMLInputElement>('input[type="file"]');
    expect(input).toBeTruthy();
    fireEvent.change(input!, { target: { files: [new File(["{}"], "network.geojson", { type: "application/geo+json" })] } });

    expect((await screen.findByRole("status")).textContent).toContain("Строим варианты подключения");
    expect(screen.getByRole("button", { name: "Отменить расчёт" })).toBeTruthy();
    expect(screen.queryByText("Проверка данных")).toBeNull();
    expect(api.createOfficialRun).toHaveBeenCalledWith("import-1");
    await waitFor(() => expect(localStorage.getItem("heatroute.officialRunId")).toBe("run-1"));
  });

  it("keeps the official export disabled when reconstruction inputs are incomplete", async () => {
    localStorage.setItem("heatroute.officialImportId", "import-1");
    localStorage.setItem("heatroute.officialRunId", "run-1");
    api.getOfficialImport.mockResolvedValue(completedImport());
    api.getOfficialRun.mockResolvedValue(completedRun(false));

    renderWorkspace();

    const exportButton = await screen.findByRole("button", { name: "Экспорт недоступен" });
    expect((exportButton as HTMLButtonElement).disabled).toBe(true);
    expect(exportButton.getAttribute("title")).toBe(
      "Для экспорта нужны исходные данные реконструкции и итоговый rank",
    );
  });

  it("offers the strict GeoJSON download for a complete ranked result", async () => {
    localStorage.setItem("heatroute.officialImportId", "import-1");
    localStorage.setItem("heatroute.officialRunId", "run-1");
    api.getOfficialImport.mockResolvedValue(completedImport());
    api.getOfficialRun.mockResolvedValue(completedRun(true));

    renderWorkspace();

    const exportButton = await screen.findByRole("button", { name: "Скачать результат" });
    expect((exportButton as HTMLButtonElement).disabled).toBe(false);
    expect(exportButton.getAttribute("title")).toBe("Скачать официальный GeoJSON");
  });

  it("can replace a persisted result with the latest completed run", async () => {
    localStorage.setItem("heatroute.officialImportId", "import-1");
    localStorage.setItem("heatroute.officialRunId", "run-1");
    api.getOfficialImport.mockResolvedValue(completedImport());
    api.getOfficialRun.mockResolvedValue(completedRun(true));
    api.getLatestOfficialRun.mockResolvedValue({ ...completedRun(true), id: "run-2" });

    renderWorkspace();

    fireEvent.click(await screen.findByRole("button", { name: "Последний расчёт" }));

    await waitFor(() => expect(localStorage.getItem("heatroute.officialRunId")).toBe("run-2"));
    expect(api.getLatestOfficialRun).toHaveBeenCalledOnce();
  });
});

function renderWorkspace() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OfficialWorkspacePage />
    </QueryClientProvider>,
  );
}

function completedImport() {
  return {
    id: "import-1",
    state: "valid",
    original_filename: "network.geojson",
    input_size_bytes: 233_000,
    created_at: "2026-09-16T00:00:00Z",
    report: {
      contract_version: "2",
      input_profile: "baseline_input",
      sha256: "hash",
      feature_count: 144,
      feature_counts: { heat_network: 29 },
      errors: [],
      warnings: [],
      valid: true,
    },
  };
}

function completedRun(exportReady: boolean) {
  return {
    id: "run-1",
    import_id: "import-1",
    state: "completed",
    algorithm_version: "test",
    input_sha256: "hash",
    created_at: "2026-09-16T00:00:00Z",
    result: {
      preferred_variant_id: "variant-1",
      variants: [{
        id: "variant-1",
        valid: true,
        rank: exportReady ? 1 : null,
        economics: { complete: exportReady },
        nodes: [],
        edges: [],
        connections: [],
        issues: [],
      }],
    },
  };
}
