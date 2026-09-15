import { afterEach, describe, expect, it, vi } from "vitest";

import { capabilitySummary, fetchCapabilities } from "./api";

afterEach(() => vi.restoreAllMocks());

describe("capabilities client", () => {
  it("keeps unavailable solvers explicit", () => {
    expect(
      capabilitySummary({
        schema_version: "1.0",
        source: "runtime",
        imports: [],
        solvers: [],
        exports: [],
        demo_seed: true,
        engineering: { hydraulics: "not_performed" },
        construction_methods: [],
      }),
    ).toBe("Расчётное ядро ещё не подключено");
  });

  it("rejects an unsuccessful response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 503 }));
    await expect(fetchCapabilities()).rejects.toThrow("503");
  });
});
