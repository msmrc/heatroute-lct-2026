import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";

import { StateView } from "../components/ui/primitives";
import { AppShell } from "./AppShell";

const CatalogsPage = lazy(() => import("../pages/CatalogsPage").then((module) => ({ default: module.CatalogsPage })));
const DataPage = lazy(() => import("../pages/DataPage").then((module) => ({ default: module.DataPage })));
const ImportPage = lazy(() => import("../pages/ImportPage").then((module) => ({ default: module.ImportPage })));
const JobsPage = lazy(() => import("../pages/JobsPage").then((module) => ({ default: module.JobsPage })));
const ProjectsPage = lazy(() => import("../pages/ProjectsPage").then((module) => ({ default: module.ProjectsPage })));
const QualityPage = lazy(() => import("../pages/QualityPage").then((module) => ({ default: module.QualityPage })));
const RunPage = lazy(() => import("../pages/RunPage").then((module) => ({ default: module.RunPage })));
const SettingsPage = lazy(() => import("../pages/SettingsPage").then((module) => ({ default: module.SettingsPage })));
const WorkspacePage = lazy(() => import("../pages/WorkspacePage").then((module) => ({ default: module.WorkspacePage })));

export function App() {
  return (
    <Suspense fallback={<StateView state="loading" title="Открываем раздел" />}>
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/projects" replace />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId/workspace" element={<WorkspacePage />} />
          <Route path="/projects/:projectId/data" element={<DataPage />} />
          <Route path="/projects/:projectId/imports/:importId" element={<ImportPage />} />
          <Route path="/projects/:projectId/runs/:runId" element={<RunPage />} />
          <Route path="/projects/:projectId/quality" element={<QualityPage />} />
          <Route path="/projects/:projectId/settings" element={<SettingsPage />} />
          <Route path="/catalogs/rules" element={<CatalogsPage />} />
          <Route path="/catalogs/costs" element={<CatalogsPage />} />
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="*" element={<Navigate to="/projects" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
