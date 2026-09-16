import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { AppShell } from "./AppShell";

afterEach(() => localStorage.clear());

describe("AppShell", () => {
  it("collapses the navigation rail and remembers the choice", () => {
    const { container, unmount } = render(
      <MemoryRouter>
        <Routes>
          <Route element={<AppShell />}>
            <Route index element={<div>Рабочая область</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    const toggle = screen.getByRole("button", { name: "Свернуть боковое меню" });
    fireEvent.click(toggle);

    expect(container.querySelector(".app-shell")?.classList.contains("is-sidebar-collapsed")).toBe(true);
    expect(localStorage.getItem("heatroute.sidebarCollapsed")).toBe("true");

    unmount();
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<AppShell />}>
            <Route index element={<div>Рабочая область</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByRole("button", { name: "Развернуть боковое меню" })).toBeTruthy();
  });
});
