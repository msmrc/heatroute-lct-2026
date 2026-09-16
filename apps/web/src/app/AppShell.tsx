import { FileCheck2, Info } from "lucide-react";
import { NavLink, Outlet } from "react-router-dom";

export function AppShell() {
  return (
    <main className="app-shell">
      <aside className="app-sidebar">
        <NavLink className="app-brand" to="/" aria-label="HeatRoute">
          <span className="app-brand__mark">H</span>
          <span><strong>HeatRoute</strong></span>
        </NavLink>

        <nav className="app-nav" aria-label="Основная навигация">
          <p>Работа</p>
          <NavLink to="/" end className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
            <FileCheck2 size={17} /><span>Маршруты</span>
          </NavLink>
          <p>О продукте</p>
          <NavLink to="/system" className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
            <Info size={17} /><span>Системная информация</span>
          </NavLink>
        </nav>
      </aside>
      <section className="app-content"><Outlet /></section>
    </main>
  );
}
