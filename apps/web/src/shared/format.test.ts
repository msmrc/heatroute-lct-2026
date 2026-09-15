import { describe, expect, it } from "vitest";

import { formatMoney } from "./format";

describe("formatMoney", () => {
  it("formats Decimal JSON strings returned by the API", () => {
    expect(formatMoney("110325449.00")).toContain("110");
    expect(formatMoney("110325449.00")).not.toBe("Не оценено");
  });

  it("keeps missing or invalid estimates explicit", () => {
    expect(formatMoney(null)).toBe("Не оценено");
    expect(formatMoney("not-a-number")).toBe("Не оценено");
  });
});
