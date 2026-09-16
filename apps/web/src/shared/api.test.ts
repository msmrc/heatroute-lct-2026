import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchReadiness, humanFileSize } from "./api";

afterEach(() => vi.restoreAllMocks());

describe("Java API client", () => {
  it("formats uploaded file sizes", () => {
    expect(humanFileSize(1536)).toBe("1.5 КБ");
  });

  it("rejects an unsuccessful readiness response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 503 }));
    await expect(fetchReadiness()).rejects.toThrow("503");
  });
});
