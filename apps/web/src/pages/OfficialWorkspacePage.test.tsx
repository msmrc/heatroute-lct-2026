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
        input_profile: "strict_official",
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
    expect(screen.queryByText("Проверка данных")).toBeNull();
    expect(api.createOfficialRun).toHaveBeenCalledWith("import-1");
    await waitFor(() => expect(localStorage.getItem("heatroute.officialRunId")).toBe("run-1"));
  });
});
