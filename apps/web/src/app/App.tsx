import { Navigate, Route, Routes } from "react-router-dom";

import { OfficialWorkspacePage } from "../pages/OfficialWorkspacePage";
import { SystemInfoPage } from "../pages/SystemInfoPage";
import { AppShell } from "./AppShell";

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<OfficialWorkspacePage />} />
        <Route path="system" element={<SystemInfoPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
