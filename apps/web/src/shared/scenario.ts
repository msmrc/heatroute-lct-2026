import type { ScenarioDraft } from "./api";

export type WorkspaceScenarioDraft = ScenarioDraft & {
  forbidden_rectangles_wgs84: NonNullable<ScenarioDraft["forbidden_rectangles_wgs84"]>;
  user_forbidden_zones: NonNullable<ScenarioDraft["user_forbidden_zones"]>;
  waypoints_wgs84: NonNullable<ScenarioDraft["waypoints_wgs84"]>;
  construction_methods: NonNullable<ScenarioDraft["construction_methods"]>;
  objective_profiles: NonNullable<ScenarioDraft["objective_profiles"]>;
  selected_dataset_version_ids: NonNullable<ScenarioDraft["selected_dataset_version_ids"]>;
  search_settings: NonNullable<ScenarioDraft["search_settings"]>;
};

export function defaultScenarioDraft(withConstraint = true): WorkspaceScenarioDraft {
  return {
    input_mode: "point_to_point_demo",
    planning_date: new Date().toISOString().slice(0, 10),
    entry_point_wgs84: [37.61, 55.752],
    goal_point_wgs84: [37.64, 55.752],
    forbidden_rectangles_wgs84: withConstraint ? [[37.623, 55.748, 37.628, 55.756]] : [],
    user_forbidden_zones: [],
    waypoints_wgs84: [],
    corridor_width_m: 8,
    circuit_layout: "paired",
    construction_methods: ["open_trench"],
    construction_method_inputs: {},
    preferred_corridors: [],
    // A fresh demo has no immutable price catalog yet, so the economic objective
    // would be rejected by preflight. It becomes valid after a catalog is selected.
    objective_profiles: ["shortest", "least_unverified"],
    search_settings: {
      resolution_m: 20,
      search_buffer_m: 500,
      budget: {
        max_expanded_states: 100_000,
        max_wall_time_s: 120,
        max_memory_mb: 4096,
      },
      max_alternatives: 3,
    },
    explicit_assumptions: [
      "Синтетическая 2D-модель: вертикальные отметки и гидравлика не проверяются",
    ],
    validation_mode: "exploratory",
    selected_dataset_version_ids: [],
    connection_candidate_ids: [],
    candidate_limit: 5,
  };
}

export function draftFromSnapshot(snapshot: Record<string, unknown>): WorkspaceScenarioDraft {
  const fallback = defaultScenarioDraft(false);
  return {
    ...fallback,
    ...snapshot,
    entry_point_wgs84:
      Array.isArray(snapshot.entry_point_wgs84) && snapshot.entry_point_wgs84.length === 2
        ? [Number(snapshot.entry_point_wgs84[0]), Number(snapshot.entry_point_wgs84[1])]
        : fallback.entry_point_wgs84,
    goal_point_wgs84:
      Array.isArray(snapshot.goal_point_wgs84) && snapshot.goal_point_wgs84.length === 2
        ? [Number(snapshot.goal_point_wgs84[0]), Number(snapshot.goal_point_wgs84[1])]
        : fallback.goal_point_wgs84,
  };
}
