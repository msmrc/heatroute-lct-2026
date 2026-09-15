import { useQuery } from "@tanstack/react-query";
import {
  BriefcaseBusiness,
  ChevronDown,
  Database,
  FolderKanban,
  Layers3,
  ListChecks,
  Moon,
  ReceiptText,
  Route,
  Settings2,
  Sun,
} from "lucide-react";
import { useEffect } from "react";
import { NavLink, Outlet, useLocation, useNavigate, useParams } from "react-router-dom";

import { capabilitySummary, fetchCapabilities, listProjects } from "../shared/api";
import { useUiStore } from "../shared/store";

export function AppShell() {
  const { projectId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((state) => state.theme);
  const setTheme = useUiStore((state) => state.setTheme);
  const capabilities = useQuery({ queryKey: ["capabilities"], queryFn: ({ signal }) => fetchCapabilities(signal) });
  const projects = useQuery({ queryKey: ["projects"], queryFn: ({ signal }) => listProjects(signal) });
  const project = projects.data?.find((item) => item.id === projectId);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
  }, [theme]);

  const projectLinks = projectId
    ? [
        { to: `/projects/${projectId}/workspace`, label: "Маршрут", icon: Route },
        { to: `/projects/${projectId}/data`, label: "Данные", icon: Database },
        { to: `/projects/${projectId}/quality`, label: "Качество", icon: ListChecks },
        { to: `/projects/${projectId}/settings`, label: "Настройки", icon: Settings2 },
      ]
    : [];
  const globalLinks = [
    { to: "/projects", label: "Проекты", icon: FolderKanban },
    { to: "/jobs", label: "Задачи", icon: BriefcaseBusiness },
    { to: "/catalogs/rules", label: "Правила", icon: Layers3 },
    { to: "/catalogs/costs", label: "Стоимость", icon: ReceiptText },
  ];

  return (
    <main className="app-shell">
      <aside className="app-sidebar">
        <NavLink className="app-brand" to="/projects" aria-label="HeatRoute — проекты">
          <span className="app-brand__mark">H</span>
          <span><strong>HeatRoute</strong><small>planning workspace</small></span>
        </NavLink>

        <nav className="app-nav" aria-label="Основная навигация">
          <p>Рабочая область</p>
          {globalLinks.slice(0, 1).map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className={({ isActive }) => isActive && location.pathname === "/projects" ? "app-nav__link is-active" : "app-nav__link"}>
              <Icon size={17} /><span>{label}</span>
            </NavLink>
          ))}
          {projectLinks.length > 0 && (
            <>
              <div className="project-switch">
                <select
                  aria-label="Текущий проект"
                  value={projectId}
                  onChange={(event) => void navigate(`/projects/${event.target.value}/workspace`)}
                >
                  {(projects.data ?? []).slice().reverse().slice(0, 50).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                </select>
                <ChevronDown size={14} />
              </div>
              {projectLinks.map(({ to, label, icon: Icon }) => (
                <NavLink key={to} to={to} className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
                  <Icon size={17} /><span>{label}</span>
                </NavLink>
              ))}
            </>
          )}
          <p>Система</p>
          {globalLinks.slice(1).map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className={({ isActive }) => isActive ? "app-nav__link is-active" : "app-nav__link"}>
              <Icon size={17} /><span>{label}</span>
            </NavLink>
          ))}
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
            <i className={capabilities.isError ? "is-error" : capabilities.isPending ? "is-pending" : ""} />
            <span>{capabilities.data ? capabilitySummary(capabilities.data) : capabilities.isError ? "API недоступен" : "Подключаем API…"}</span>
          </div>
          {project && <small className="sidebar-project-meta">rev {project.current_revision} · {project.working_crs ?? "CRS не задана"}</small>}
        </div>
      </aside>
      <section className="app-content"><Outlet /></section>
    </main>
  );
}
