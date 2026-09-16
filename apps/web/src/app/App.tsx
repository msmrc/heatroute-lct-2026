import { Navigate, Route, Routes } from "react-router-dom";

import { OfficialWorkspacePage } from "../pages/OfficialWorkspacePage";
import { AppShell } from "./AppShell";

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<OfficialWorkspacePage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
