import { FileCheck2, Info, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { useState } from "react";
import { NavLink, Outlet } from "react-router-dom";

const SIDEBAR_STATE_KEY = "heatroute.sidebarCollapsed";

export function AppShell() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => localStorage.getItem(SIDEBAR_STATE_KEY) === "true",
  );

  function toggleSidebar() {
    setSidebarCollapsed((value) => {
      const nextValue = !value;
      localStorage.setItem(SIDEBAR_STATE_KEY, String(nextValue));
      return nextValue;
    });
  }

  return (
    <main className={sidebarCollapsed ? "app-shell is-sidebar-collapsed" : "app-shell"}>
      <aside className="app-sidebar">
        <button
          className="app-sidebar-toggle"
          type="button"
          aria-label={sidebarCollapsed ? "Развернуть боковое меню" : "Свернуть боковое меню"}
          title={sidebarCollapsed ? "Развернуть меню" : "Свернуть меню"}
          onClick={toggleSidebar}
        >
          {sidebarCollapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
        </button>
        <NavLink className="app-brand" to="/" aria-label="HeatRoute">
          <span className="app-brand__mark">H</span>
          <span><strong>HeatRoute</strong></span>
        </NavLink>

        <nav className="app-nav" aria-label="Основная навигация">
          <p>Работа</p>
          <NavLink to="/" end title="Маршруты" className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
            <FileCheck2 size={17} /><span>Маршруты</span>
          </NavLink>
          <p>О продукте</p>
          <NavLink to="/system" title="Системная информация" className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
            <Info size={17} /><span>Системная информация</span>
          </NavLink>
        </nav>
      </aside>
      <section className="app-content"><Outlet /></section>
    </main>
  );
}
