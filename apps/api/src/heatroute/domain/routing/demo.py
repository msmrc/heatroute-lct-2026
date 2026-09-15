import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from math import ceil, floor
from typing import Any, Literal

from pyproj import CRS, Transformer
from shapely import union_all
from shapely.geometry import LineString, box, mapping, shape
from shapely.geometry.base import BaseGeometry
from shapely.ops import transform

from heatroute.domain.constraints import (
    CrossingPortal,
    EntryGate,
    MetricFeature,
    RouteConstraintValidator,
    build_search_exclusions,
)
from heatroute.domain.network import CandidateDecision
from heatroute.domain.routing.grid import CostZone, GridGraph, GridRoutingRequest
from heatroute.domain.routing.optimization import optimize_validated_shortcuts
from heatroute.domain.routing.solver import solve_grid
from heatroute.domain.routing.types import ComputationBudget, RouteOutcome
from heatroute.domain.routing.validation import validate_route
from heatroute.domain.rules import RuleProfileVersion


@dataclass(frozen=True)
class DemoComputation:
    outcome: str
    search_completion: str
    statistics: dict[str, Any]
    alternative: dict[str, Any] | None = None
    error_code: str | None = None


def compute_demo_route(
    snapshot: dict[str, Any],
    algorithm: str,
    *,
    rule_profile_definition: dict[str, Any] | None = None,
    constraint_features: tuple[MetricFeature, ...] = (),
    constraint_portals: tuple[CrossingPortal, ...] = (),
    constraint_coverage: tuple[MetricFeature, ...] = (),
    candidate_decisions: tuple[CandidateDecision, ...] = (),
    planning_date: date | None = None,
    working_crs: str | None = None,
    metric_start: tuple[float, float] | None = None,
    metric_goal: tuple[float, float] | None = None,
    entry_gate: EntryGate | None = None,
    entry_connector: LineString | None = None,
    objective: Literal["shortest", "estimated_cost", "least_unverified"] = "shortest",
    construction_mode: str = "open_trench",
    default_cost_per_m: float = 1.0,
    minimum_cost_per_m: float | None = 1.0,
    method_cost_per_m: dict[str, float] | None = None,
    reuse_penalty_geometries: tuple[BaseGeometry, ...] = (),
    reuse_penalty_per_m: float = 0.0,
    should_cancel: Callable[[], bool] | None = None,
    on_progress: Callable[[int], None] | None = None,
) -> DemoComputation:
    start_wgs84 = None if metric_start is not None else _coordinate(snapshot["entry_point_wgs84"])
    goal_wgs84 = None if metric_goal is not None else _coordinate(snapshot["goal_point_wgs84"])
    resolution = float(snapshot["search_settings"]["resolution_m"])
    search_buffer = float(snapshot["search_settings"]["search_buffer_m"])
    corridor_width = float(snapshot["corridor_width_m"])
    max_states = int(snapshot["search_settings"]["budget"]["max_expanded_states"])
    max_wall_time = float(snapshot["search_settings"]["budget"].get("max_wall_time_s", 120))
    max_memory_mb = float(snapshot["search_settings"]["budget"].get("max_memory_mb", 4_096))
    profile = (
        None
        if rule_profile_definition is None
        else RuleProfileVersion.from_dict(rule_profile_definition)
    )
    validation_mode: Literal["strict", "exploratory"] = (
        "exploratory" if snapshot.get("validation_mode") == "exploratory" else "strict"
    )
    allowed_assumptions = frozenset(str(item) for item in snapshot.get("explicit_assumptions", []))

    if working_crs is None:
        assert start_wgs84 is not None
        start_lon, start_lat = start_wgs84
        zone = max(1, min(60, int((start_lon + 180) // 6) + 1))
        metric_crs = CRS.from_epsg((32600 if start_lat >= 0 else 32700) + zone)
    else:
        metric_crs = CRS.from_user_input(working_crs)
    to_metric = Transformer.from_crs("EPSG:4326", metric_crs, always_xy=True)
    to_wgs84 = Transformer.from_crs(metric_crs, "EPSG:4326", always_xy=True)
    if metric_start is None:
        assert start_wgs84 is not None
        start = to_metric.transform(start_wgs84[0], start_wgs84[1])
    else:
        start = metric_start
    if metric_goal is None:
        assert goal_wgs84 is not None
        exact_goal = to_metric.transform(goal_wgs84[0], goal_wgs84[1])
    else:
        exact_goal = metric_goal
    goal = (
        start[0] + round((exact_goal[0] - start[0]) / resolution) * resolution,
        start[1] + round((exact_goal[1] - start[1]) / resolution) * resolution,
    )

    metric_obstacles: list[tuple[float, float, float, float]] = []
    for rectangle in snapshot.get("forbidden_rectangles_wgs84", []):
        west, south, east, north = (float(value) for value in rectangle)
        corners = [
            to_metric.transform(west, south),
            to_metric.transform(west, north),
            to_metric.transform(east, south),
            to_metric.transform(east, north),
        ]
        metric_obstacles.append(
            (
                min(point[0] for point in corners),
                min(point[1] for point in corners),
                max(point[0] for point in corners),
                max(point[1] for point in corners),
            )
        )
    metric_user_exclusions = tuple(
        transform(to_metric.transform, shape(raw_geometry))
        for raw_geometry in snapshot.get("user_forbidden_zones", [])
    )
    exact_waypoints = tuple(
        to_metric.transform(*_coordinate(raw_waypoint))
        for raw_waypoint in snapshot.get("waypoints_wgs84", [])
    )
    waypoints = tuple(
        (
            start[0] + round((point[0] - start[0]) / resolution) * resolution,
            start[1] + round((point[1] - start[1]) / resolution) * resolution,
        )
        for point in exact_waypoints
    )

    relevant_x = [start[0], goal[0], exact_goal[0]]
    relevant_y = [start[1], goal[1], exact_goal[1]]
    relevant_x.extend(point[0] for point in waypoints)
    relevant_y.extend(point[1] for point in waypoints)
    for west, south, east, north in metric_obstacles:
        relevant_x.extend((west, east))
        relevant_y.extend((south, north))
    for exclusion in metric_user_exclusions:
        west, south, east, north = exclusion.bounds
        relevant_x.extend((west, east))
        relevant_y.extend((south, north))
    preliminary_exclusions = (
        ()
        if profile is None
        else build_search_exclusions(
            profile=profile,
            features=constraint_features,
            portals=constraint_portals,
            coverage=constraint_coverage,
            mode=validation_mode,
            allowed_assumptions=allowed_assumptions,
            entry_gate=entry_gate,
        )
    )
    for exclusion in preliminary_exclusions:
        west, south, east, north = exclusion.bounds
        relevant_x.extend((west, east))
        relevant_y.extend((south, north))
    min_x = start[0] + floor((min(relevant_x) - search_buffer - start[0]) / resolution) * resolution
    min_y = start[1] + floor((min(relevant_y) - search_buffer - start[1]) / resolution) * resolution
    max_x = start[0] + ceil((max(relevant_x) + search_buffer - start[0]) / resolution) * resolution
    max_y = start[1] + ceil((max(relevant_y) + search_buffer - start[1]) / resolution) * resolution

    bounds = (min_x, min_y, max_x, max_y)
    canonical_exclusions = (
        ()
        if profile is None
        else build_search_exclusions(
            profile=profile,
            features=constraint_features,
            portals=constraint_portals,
            coverage=constraint_coverage,
            aoi=box(*bounds),
            mode=validation_mode,
            allowed_assumptions=allowed_assumptions,
            entry_gate=entry_gate,
        )
    )
    unverified_areas: list[BaseGeometry] = []
    if profile is not None:
        for rule in profile.rules:
            if rule.type != "coverage_required":
                continue
            applicable = [
                feature.geometry
                for feature in constraint_coverage
                if rule.applies_to_kind in feature.attributes.get("covered_kinds", [])
                and feature.attributes.get("completeness") in {"known_complete", "synthetic"}
            ]
            covered = union_all(applicable) if applicable else None
            unknown = box(*bounds) if covered is None else box(*bounds).difference(covered)
            if not unknown.is_empty:
                unverified_areas.append(unknown)
    active_method_costs = method_cost_per_m or {}
    cost_zones = [
        CostZone(
            portal.footprint,
            active_method_costs.get(portal.method, default_cost_per_m),
        )
        for portal in constraint_portals
    ]
    for preferred in snapshot.get("preferred_corridors", []):
        preferred_geometry = transform(to_metric.transform, shape(preferred["geometry"]))
        cost_zones.append(
            CostZone(
                preferred_geometry,
                default_cost_per_m * float(preferred.get("cost_multiplier", 1)),
            )
        )
    request = GridRoutingRequest(
        bounds=bounds,
        start=start,
        goal=goal,
        resolution_m=resolution,
        corridor_width_m=corridor_width,
        forbidden_rectangles=tuple(metric_obstacles),
        forbidden_geometries=(*canonical_exclusions, *metric_user_exclusions),
        waypoints=waypoints,
        construction_mode=construction_mode,
        objective=objective,
        default_cost_per_m=default_cost_per_m,
        minimum_cost_per_m=minimum_cost_per_m,
        cost_zones=tuple(cost_zones),
        unverified_geometries=tuple(unverified_areas),
        reuse_penalty_geometries=reuse_penalty_geometries,
        reuse_penalty_per_m=reuse_penalty_per_m,
    )
    result = solve_grid(
        GridGraph(request),
        algorithm=algorithm,
        budget=ComputationBudget(
            max_expanded_states=max_states,
            max_wall_time_s=max_wall_time,
            max_memory_mb=max_memory_mb,
        ),
        should_cancel=should_cancel,
        on_progress=on_progress,
    )
    statistics = {
        "expanded_states": result.expanded_states,
        "working_crs": metric_crs.to_string(),
        "resolution_m": resolution,
        "objective": objective,
        "construction_mode": construction_mode,
        "waypoint_count": len(waypoints),
        "heuristic": (
            "euclidean_lower_bound"
            if objective == "shortest"
            else (
                "scaled_euclidean_lower_bound"
                if objective == "estimated_cost" and minimum_cost_per_m is not None
                else "zero"
            )
        ),
    }
    if result.outcome is not RouteOutcome.ROUTES_FOUND:
        statistics["model_diagnostic"] = {
            "cause": result.diagnostic,
            "resolution_m": resolution,
            "aoi_bounds_metric": [round(value, 3) for value in bounds],
            "conclusion_scope": "bounded_discrete_model",
        }
        return DemoComputation(
            outcome=result.outcome.value,
            search_completion=result.search_completion.value,
            statistics=statistics,
            error_code=result.diagnostic,
        )

    path = result.path
    if path[-1] != exact_goal:
        path = (*path, exact_goal)
    validation = validate_route(path, request)
    if not validation.valid:
        return DemoComputation(
            outcome="invalid_input",
            search_completion=result.search_completion.value,
            statistics=statistics,
            error_code=validation.finding_codes[0],
        )
    original_path = path
    simplified_path = _remove_collinear(path)
    simplified_validation = validate_route(simplified_path, request)
    if simplified_validation.valid:
        path = simplified_path
        validation = simplified_validation
    shortcut_result = optimize_validated_shortcuts(
        path,
        is_valid=lambda candidate: validate_route(candidate, request).valid,
        mandatory_points=frozenset((start, *waypoints, exact_goal)),
    )
    path = shortcut_result.path
    validation = validate_route(path, request)

    centerline_metric = LineString(path)
    corridor_metric = centerline_metric.buffer(corridor_width / 2)
    constraint_report = None
    profile_identity: tuple[str, int] | None = None
    if profile is not None:
        profile_identity = (profile.id, profile.version)
        scenario_features = tuple(
            MetricFeature(
                id=f"forbidden-zone-{index}",
                kind="forbidden_zone",
                geometry=box(*rectangle),
                attributes={},
                source_ref=f"scenario:forbidden_rectangles_wgs84:{index}",
            )
            for index, rectangle in enumerate(metric_obstacles)
        )
        scenario_features += tuple(
            MetricFeature(
                id=f"user-forbidden-zone-{index}",
                kind="forbidden_zone",
                geometry=geometry,
                attributes={},
                source_ref=f"scenario:user_forbidden_zones:{index}",
            )
            for index, geometry in enumerate(metric_user_exclusions)
        )
        constraint_report = RouteConstraintValidator().validate(
            centerline=centerline_metric,
            corridor_width_m=corridor_width,
            profile=profile,
            features=(*constraint_features, *scenario_features),
            portals=constraint_portals,
            coverage=constraint_coverage,
            candidate_decisions=candidate_decisions,
            planning_date=planning_date,
            mode=validation_mode,
            allowed_assumptions=allowed_assumptions,
            entry_gate=entry_gate,
            entry_connector=entry_connector,
        )
        if not constraint_report.valid:
            failed = next(
                finding
                for finding in constraint_report.findings
                if finding.check_status == "failed"
            )
            return DemoComputation(
                outcome="invalid_input",
                search_completion=result.search_completion.value,
                statistics=statistics,
                error_code=failed.code,
            )
    full_centerline_metric = centerline_metric
    if entry_connector is not None:
        full_centerline_metric = LineString(
            (*centerline_metric.coords, *tuple(entry_connector.coords)[1:])
        )
    if constraint_report is not None:
        corridor_metric = constraint_report.corridor
    elif entry_connector is not None:
        corridor_metric = full_centerline_metric.buffer(corridor_width / 2)
    centerline_wgs84 = mapping(transform(to_wgs84.transform, full_centerline_metric))
    corridor_wgs84 = mapping(transform(to_wgs84.transform, corridor_metric))
    geometry_json = json.dumps(centerline_wgs84, sort_keys=True, separators=(",", ":"))
    alternative = {
        "centerline_wgs84": centerline_wgs84,
        "corridor_wgs84": corridor_wgs84,
        "geometry_hash": hashlib.sha256(geometry_json.encode()).hexdigest(),
        "geometry_status": "valid_in_model",
        "metrics": {
            "route_length_m": round(full_centerline_metric.length, 3),
            "expanded_states": result.expanded_states,
            "search_score": round(float(result.cost or 0), 3),
            "objective": objective,
            "construction_mode": construction_mode,
            "waypoint_count": len(waypoints),
            "waypoint_visits_wgs84": [
                list(to_wgs84.transform(point[0], point[1])) for point in waypoints
            ],
            "postprocessing": {
                "method": "remove_collinear+validated_shortcuts",
                "input_vertices": len(original_path),
                "output_vertices": len(path),
                "candidate_shortcuts": shortcut_result.candidate_shortcuts,
                "accepted_shortcuts": shortcut_result.accepted_shortcuts,
                "removed_vertices": shortcut_result.removed_vertices,
                "revalidated": True,
            },
        },
        "validation_report": {
            "valid": True,
            "corridor_area_m2": round(validation.corridor_area_m2, 3),
            "finding_codes": list(validation.finding_codes),
            "rule_profile": None
            if constraint_report is None or profile_identity is None
            else {
                "definition_id": profile_identity[0],
                "definition_version": profile_identity[1],
                "findings": [
                    _finding_dict(finding, to_wgs84.transform)
                    for finding in constraint_report.findings
                ],
                "unverified_length_m": round(constraint_report.unverified_length_m, 3),
                "crossing_events": [
                    {
                        "event_type": event.event_type,
                        "rule_id": event.rule_id,
                        "feature_id": event.feature_id,
                        "portal_id": event.portal_id,
                        "quantity": event.quantity,
                        "geometry_wgs84": mapping(transform(to_wgs84.transform, event.geometry)),
                    }
                    for event in constraint_report.crossing_events
                ],
            },
        },
    }
    return DemoComputation(
        outcome="routes_found",
        search_completion=result.search_completion.value,
        statistics=statistics,
        alternative=alternative,
    )


def _coordinate(value: object) -> tuple[float, float]:
    if not isinstance(value, list | tuple) or len(value) != 2:
        raise ValueError("coordinate must contain longitude and latitude")
    return float(value[0]), float(value[1])


def _finding_dict(finding: object, transformer: Callable[..., object]) -> dict[str, Any]:
    from heatroute.domain.constraints import ConstraintFinding

    assert isinstance(finding, ConstraintFinding)
    geometry: BaseGeometry | None = finding.geometry
    return {
        "code": finding.code,
        "severity": finding.severity,
        "check_status": finding.check_status,
        "message": finding.message,
        "rule_id": finding.rule_id,
        "feature_ids": list(finding.feature_ids),
        "measured": finding.measured,
        "limit": finding.limit,
        "unit": finding.unit,
        "missing_fields": list(finding.missing_fields),
        "recommended_action": finding.recommended_action,
        "source_refs": list(finding.source_refs),
        "assumption_id": finding.assumption_id,
        "geometry_wgs84": (None if geometry is None else mapping(transform(transformer, geometry))),
    }


def _remove_collinear(
    path: tuple[tuple[float, float], ...], *, tolerance: float = 1e-9
) -> tuple[tuple[float, float], ...]:
    if len(path) <= 2:
        return path
    result = [path[0]]
    for index in range(1, len(path) - 1):
        left = result[-1]
        middle = path[index]
        right = path[index + 1]
        cross = (middle[0] - left[0]) * (right[1] - middle[1]) - (middle[1] - left[1]) * (
            right[0] - middle[0]
        )
        if abs(cross) > tolerance:
            result.append(middle)
    result.append(path[-1])
    return tuple(result)
