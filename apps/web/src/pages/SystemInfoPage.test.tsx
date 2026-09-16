import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SystemInfoPage } from "./SystemInfoPage";

describe("SystemInfoPage", () => {
  it("keeps implementation details out of the route workflow and on the system page", () => {
    render(<SystemInfoPage />);

    expect(screen.getByRole("heading", { name: "Системная информация" })).toBeTruthy();
    expect(screen.getAllByText("Dragons")).toHaveLength(2);
    expect(screen.getByText("Java 11 · Spring Boot 2.6.3")).toBeTruthy();
    expect(screen.getByRole("link", { name: /Swagger API/ }).getAttribute("href")).toBe("/api/v1/swagger-ui.html");
    expect(screen.getByRole("link", { name: /Схема исходных данных/ }).getAttribute("href"))
      .toBe("/api/v1/official/contracts/input.schema.json");
    expect(screen.getByRole("link", { name: /Схема набора постановщика/ }).getAttribute("href"))
      .toBe("/api/v1/official/contracts/provided-dataset.schema.json");
    expect(screen.getByRole("link", { name: /Схема результата/ }).getAttribute("href"))
      .toBe("/api/v1/official/contracts/output.schema.json");
  });
});
