from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ConstructionMethod:
    code: str
    label: str
    trenchless: bool
    applicable_to: tuple[str, ...]
    required_inputs: tuple[str, ...]


CONSTRUCTION_METHODS: dict[str, ConstructionMethod] = {
    method.code: method
    for method in (
        ConstructionMethod(
            "open_trench",
            "Open trench",
            False,
            ("ordinary", "road_with_opening_permission"),
            ("trench_depth_m", "trench_width_m", "surface_restoration_class"),
        ),
        ConstructionMethod(
            "horizontal_directional_drilling",
            "Horizontal directional drilling",
            True,
            ("road", "railway", "watercourse"),
            ("entry_angle_deg", "exit_angle_deg", "minimum_bend_radius_m", "geology_class"),
        ),
        ConstructionMethod(
            "microtunneling",
            "Microtunneling",
            True,
            ("road", "railway", "dense_urban"),
            ("launch_shaft", "reception_shaft", "casing_diameter_mm", "geology_class"),
        ),
        ConstructionMethod(
            "pipe_jacking",
            "Pipe jacking",
            True,
            ("road", "railway", "dense_urban"),
            ("launch_shaft", "reception_shaft", "casing_diameter_mm", "jacking_length_m"),
        ),
        ConstructionMethod(
            "bridge_attachment",
            "Bridge attachment",
            False,
            ("bridge",),
            ("owner_approval", "support_spacing_m", "thermal_movement_solution"),
        ),
        ConstructionMethod(
            "existing_duct",
            "Existing duct or collector",
            False,
            ("existing_duct",),
            ("owner_approval", "clear_internal_diameter_mm", "condition_survey"),
        ),
    )
}


def construction_method(code: str) -> ConstructionMethod:
    try:
        return CONSTRUCTION_METHODS[code]
    except KeyError as error:
        raise ValueError(f"unsupported construction method: {code}") from error
