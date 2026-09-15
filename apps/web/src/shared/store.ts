import { create } from "zustand";
import { persist } from "zustand/middleware";

export type Theme = "light" | "dark";
export type WorkspacePanel = "scenario" | "results" | "inspector";
export type MapLayerKey =
  | "constraints"
  | "datasets"
  | "routes"
  | "corridors"
  | "endpoints"
  | "findings";

interface UiState {
  theme: Theme;
  collapsedPanels: Record<WorkspacePanel, boolean>;
  layers: Record<MapLayerKey, boolean>;
  selectedAlternative: number;
  setTheme: (theme: Theme) => void;
  togglePanel: (panel: WorkspacePanel) => void;
  toggleLayer: (layer: MapLayerKey) => void;
  selectAlternative: (rank: number) => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      theme: "light",
      collapsedPanels: { scenario: false, results: false, inspector: false },
      layers: {
        constraints: true,
        datasets: true,
        routes: true,
        corridors: true,
        endpoints: true,
        findings: true,
      },
      selectedAlternative: 1,
      setTheme: (theme) => set({ theme }),
      togglePanel: (panel) =>
        set((state) => ({
          collapsedPanels: {
            ...state.collapsedPanels,
            [panel]: !state.collapsedPanels[panel],
          },
        })),
      toggleLayer: (layer) =>
        set((state) => ({ layers: { ...state.layers, [layer]: !state.layers[layer] } })),
      selectAlternative: (rank) => set({ selectedAlternative: rank }),
    }),
    { name: "heatroute.ui" },
  ),
);
