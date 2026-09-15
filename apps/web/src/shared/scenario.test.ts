import { describe, expect, it } from "vitest";

import { defaultScenarioDraft, draftFromSnapshot } from "./scenario";

describe("scenario draft", () => {
  it("creates a complete, backend-valid demo draft", () => {
    const draft = defaultScenarioDraft(true);
    expect(draft.input_mode).toBe("point_to_point_demo");
    expect(draft.forbidden_rectangles_wgs84).toHaveLength(1);
    expect(draft.search_settings.budget?.max_expanded_states).toBe(100_000);
    expect(draft.validation_mode).toBe("exploratory");
    expect(draft.planning_date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(draft.objective_profiles).toEqual(["shortest", "least_unverified"]);
    expect(draft.cost_catalog_version_id).toBeUndefined();
  });

  it("restores coordinates and optional collections from a server snapshot", () => {
    const restored = draftFromSnapshot({
      entry_point_wgs84: [30.1, 60.2],
      goal_point_wgs84: [30.2, 60.3],
      corridor_width_m: 4,
      forbidden_rectangles_wgs84: [],
    });
    expect(restored.entry_point_wgs84).toEqual([30.1, 60.2]);
    expect(restored.goal_point_wgs84).toEqual([30.2, 60.3]);
    expect(restored.corridor_width_m).toBe(4);
    expect(restored.construction_methods).toEqual(["open_trench"]);
  });
});
