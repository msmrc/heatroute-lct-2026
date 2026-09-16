import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchReadiness, getLatestOfficialRun, humanFileSize } from "./api";

afterEach(() => vi.restoreAllMocks());

describe("Java API client", () => {
  it("formats uploaded file sizes", () => {
    expect(humanFileSize(1536)).toBe("1.5 КБ");
  });

  it("rejects an unsuccessful readiness response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 503 }));
    await expect(fetchReadiness()).rejects.toThrow("503");
  });

  it("loads the latest completed calculation for the visual demo", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ id: "run-1", state: "completed" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));

    await expect(getLatestOfficialRun()).resolves.toMatchObject({ id: "run-1", state: "completed" });
    expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/official\/runs\/latest$/), { signal: undefined });
  });
});
