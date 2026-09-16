import { useQuery } from "@tanstack/react-query";
import { Braces, FileCheck2, Moon, Sun } from "lucide-react";
import { useEffect } from "react";
import { NavLink, Outlet } from "react-router-dom";

import { fetchReadiness } from "../shared/api";
import { useUiStore } from "../shared/store";

export function AppShell() {
  const theme = useUiStore((state) => state.theme);
  const setTheme = useUiStore((state) => state.setTheme);
  const readiness = useQuery({
    queryKey: ["java-readiness"],
    queryFn: ({ signal }) => fetchReadiness(signal),
    refetchInterval: 15_000,
  });

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
  }, [theme]);

  const apiOk = readiness.data?.status === "ready";

  return (
    <main className="app-shell">
      <aside className="app-sidebar">
        <NavLink className="app-brand" to="/" aria-label="HeatRoute">
          <span className="app-brand__mark">H</span>
          <span><strong>HeatRoute</strong><small>official workspace</small></span>
        </NavLink>

        <nav className="app-nav" aria-label="Основная навигация">
          <p>Рабочая область</p>
          <NavLink to="/" end className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
            <FileCheck2 size={17} /><span>Официальный GeoJSON</span>
          </NavLink>
          <p>Разработчикам</p>
          <a className="app-nav__link" href="/swagger-ui.html" target="_blank" rel="noreferrer">
            <Braces size={17} /><span>Swagger API</span>
          </a>
        </nav>

        <div className="sidebar-footer">
          <button
            type="button"
            className="theme-toggle"
            onClick={() => setTheme(theme === "light" ? "dark" : "light")}
            aria-label={theme === "light" ? "Включить тёмную тему" : "Включить светлую тему"}
          >
            {theme === "light" ? <Moon size={16} /> : <Sun size={16} />}
            <span>{theme === "light" ? "Тёмная тема" : "Светлая тема"}</span>
          </button>
          <div className="api-state">
            <i className={readiness.isPending ? "is-pending" : apiOk ? "" : "is-error"} />
            <span>{readiness.isPending ? "Подключаем Java API…" : apiOk ? "Java API готов" : "Java API недоступен"}</span>
          </div>
          <small className="sidebar-project-meta">Java 11 · Spring Boot 2.6.3</small>
        </div>
      </aside>
      <section className="app-content"><Outlet /></section>
    </main>
  );
}
