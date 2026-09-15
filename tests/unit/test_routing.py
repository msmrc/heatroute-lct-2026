import json
from math import isclose
from pathlib import Path
from typing import Any

import pytest
from pyproj import Transformer
from shapely.geometry import LineString, Polygon, box
from shapely.ops import transform

from heatroute.domain.constraints import CrossingPortal, MetricFeature
from heatroute.domain.routing import (
    ComputationBudget,
    GridRoutingRequest,
    RouteOutcome,
    solve_grid,
    validate_route,
)
from heatroute.domain.routing.demo import compute_demo_route
from heatroute.domain.routing.grid import GridGraph

FIXTURE_PATH = Path(__file__).parents[1] / "fixtures" / "algorithm_cases.json"


def load_cases() -> list[dict[str, Any]]:
    payload = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    return list(payload["cases"])


def request_from_case(case: dict[str, Any]) -> GridRoutingRequest:
    return GridRoutingRequest(
        bounds=tuple(case["bounds_m"]),
        start=tuple(case["start_m"]),
        goal=tuple(case["goal_m"]),
        corridor_width_m=case["corridor_width_m"],
        resolution_m=case["resolution_m"],
        forbidden_rectangles=tuple(
            tuple(rectangle) for rectangle in case["forbidden_rectangles_m"]
        ),
    )


@pytest.mark.parametrize("case", load_cases(), ids=lambda case: str(case["id"]))
def test_algorithm_fixtures(case: dict[str, Any]) -> None:
    request = request_from_case(case)
    result = solve_grid(GridGraph(request), algorithm="astar")
    expected = case["expected"]
    assert result.outcome == expected["outcome"]
    if result.outcome == RouteOutcome.ROUTES_FOUND:
        assert result.cost is not None
        assert validate_route(result.path, request).valid
        if "length_m" in expected:
            assert isclose(result.cost, expected["length_m"], abs_tol=expected["tolerance_m"])
        if "length_greater_than_m" in expected:
            assert result.cost > expected["length_greater_than_m"]


@pytest.mark.parametrize("case", load_cases())
def test_astar_matches_dijkstra(case: dict[str, Any]) -> None:
    request = request_from_case(case)
    astar = solve_grid(GridGraph(request), algorithm="astar")
    dijkstra = solve_grid(GridGraph(request), algorithm="dijkstra")
    assert astar.outcome == dijkstra.outcome
    if astar.cost is not None and dijkstra.cost is not None:
        assert isclose(astar.cost, dijkstra.cost, rel_tol=0, abs_tol=1e-9)


def test_budget_exhaustion_is_not_no_route() -> None:
    request = GridRoutingRequest(
        bounds=(0, 0, 100, 100),
        start=(10, 50),
        goal=(90, 50),
        corridor_width_m=2,
        resolution_m=5,
    )
    result = solve_grid(
        GridGraph(request),
        algorithm="dijkstra",
        budget=ComputationBudget(max_expanded_states=2),
    )
    assert result.outcome == RouteOutcome.BUDGET_EXCEEDED
    assert result.diagnostic == "MAX_EXPANDED_STATES"


def test_solver_cooperatively_stops_when_cancellation_is_requested() -> None:
    request = GridRoutingRequest(
        bounds=(0, 0, 1_000, 1_000),
        start=(10, 500),
        goal=(990, 500),
        corridor_width_m=2,
        resolution_m=2,
    )
    checks = 0

    def should_cancel() -> bool:
        nonlocal checks
        checks += 1
        return checks >= 2

    result = solve_grid(
        GridGraph(request),
        algorithm="dijkstra",
        should_cancel=should_cancel,
    )
    assert result.outcome == RouteOutcome.CANCELLED
    assert result.search_completion == "cancelled"
    assert result.diagnostic == "CANCEL_REQUESTED"
    assert result.expanded_states == 256


def test_independent_validator_rejects_a_thin_line_through_narrow_gap() -> None:
    case = next(case for case in load_cases() if case["id"] == "centerline_fits_corridor_does_not")
    request = request_from_case(case)
    report = validate_route(((10, 50), (90, 50)), request)
    assert report.valid is False
    assert report.finding_codes == ("FORBIDDEN_CORRIDOR_INTERSECTION",)


def test_diagonal_corner_cutting_between_touching_obstacles_is_rejected() -> None:
    request = GridRoutingRequest(
        bounds=(0, 0, 20, 20),
        start=(2, 2),
        goal=(18, 18),
        corridor_width_m=1,
        resolution_m=1,
        forbidden_rectangles=((8, 0, 10, 10), (10, 10, 12, 20)),
    )

    report = validate_route(((2, 2), (18, 18)), request)

    assert not report.valid
    assert "FORBIDDEN_CORRIDOR_INTERSECTION" in report.finding_codes


def test_off_grid_goal_connector_through_obstacle_is_rejected() -> None:
    request = GridRoutingRequest(
        bounds=(0, 0, 20, 20),
        start=(2, 10),
        goal=(10, 10),
        corridor_width_m=1,
        resolution_m=1,
        forbidden_rectangles=((10.25, 9, 11.25, 11),),
    )

    report = validate_route(((2, 10), (10, 10), (12, 10)), request)

    assert not report.valid
    assert report.finding_codes == ("FORBIDDEN_CORRIDOR_INTERSECTION",)


def test_invalid_smoothed_shortcut_does_not_replace_valid_original_path() -> None:
    request = GridRoutingRequest(
        bounds=(0, 0, 20, 20),
        start=(2, 10),
        goal=(18, 10),
        corridor_width_m=1,
        resolution_m=1,
        forbidden_rectangles=((8, 7, 12, 13),),
    )
    original = ((2, 10), (7, 6), (13, 6), (18, 10))
    smoothed = tuple(LineString(original).simplify(10).coords)

    assert validate_route(original, request).valid
    assert not validate_route(smoothed, request).valid


def test_wgs84_demo_route_is_computed_and_independently_validated() -> None:
    result = compute_demo_route(
        {
            "entry_point_wgs84": [37.61, 55.752],
            "goal_point_wgs84": [37.64, 55.752],
            "forbidden_rectangles_wgs84": [[37.623, 55.748, 37.628, 55.756]],
            "corridor_width_m": 8,
            "search_settings": {
                "resolution_m": 20,
                "search_buffer_m": 400,
                "budget": {"max_expanded_states": 100_000},
            },
        },
        "astar",
    )
    assert result.outcome == "routes_found"
    assert result.alternative is not None
    assert result.alternative["validation_report"]["valid"] is True
    assert result.alternative["metrics"]["route_length_m"] > 1_800


def test_empty_route_length_includes_the_bounded_entry_connector() -> None:
    result = compute_demo_route(
        {
            "corridor_width_m": 1,
            "search_settings": {
                "resolution_m": 1,
                "search_buffer_m": 10,
                "budget": {"max_expanded_states": 10_000},
            },
        },
        "astar",
        working_crs="EPSG:32637",
        metric_start=(500_000, 6_200_000),
        metric_goal=(500_010, 6_200_000),
        entry_connector=LineString(
            [(500_010, 6_200_000), (500_012, 6_200_000)]
        ),
    )

    assert result.outcome == "routes_found"
    assert result.alternative is not None
    assert result.alternative["metrics"]["route_length_m"] == pytest.approx(12)


def test_canonical_building_constraint_is_used_during_search_and_final_validation() -> None:
    profile = json.loads(Path("examples/rule_profile.demo.json").read_text(encoding="utf-8"))
    transformer = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
    building = MetricFeature(
        "B1",
        "building",
        transform(transformer.transform, box(37.623, 55.748, 37.628, 55.756)),
        {"building_role": "existing"},
        "dataset-version:test:feature:B1",
    )
    result = compute_demo_route(
        {
            "entry_point_wgs84": [37.61, 55.752],
            "goal_point_wgs84": [37.64, 55.752],
            "forbidden_rectangles_wgs84": [],
            "corridor_width_m": 8,
            "search_settings": {
                "resolution_m": 20,
                "search_buffer_m": 400,
                "budget": {"max_expanded_states": 100_000},
            },
        },
        "astar",
        rule_profile_definition=profile,
        constraint_features=(building,),
        working_crs="EPSG:32637",
    )

    assert result.outcome == "routes_found"
    assert result.alternative is not None
    assert result.alternative["metrics"]["route_length_m"] > 1_900
    assert result.alternative["validation_report"]["rule_profile"]["findings"] == []


def test_search_uses_exact_canonical_polygon_hole_not_bounding_box() -> None:
    obstacle = Polygon(
        shell=[(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)],
        holes=[[(3, 3), (7, 3), (7, 7), (3, 7), (3, 3)]],
    )
    request = GridRoutingRequest(
        bounds=(3, 3, 7, 7),
        start=(4, 5),
        goal=(6, 5),
        corridor_width_m=0.5,
        resolution_m=1,
        forbidden_geometries=(obstacle,),
    )

    result = solve_grid(GridGraph(request), algorithm="astar")

    assert result.outcome == RouteOutcome.ROUTES_FOUND
    assert result.cost == pytest.approx(2)


def test_search_and_final_validation_use_the_named_portal() -> None:
    road = MetricFeature(
        "ROAD",
        "road",
        box(500_009, 6_200_000, 500_011, 6_200_020),
        {},
        "test:ROAD",
    )
    portal = CrossingPortal(
        "PORTAL",
        LineString([(500_008, 6_200_010), (500_012, 6_200_010)]),
        width_m=4,
        applies_to_feature_ids=frozenset({"ROAD"}),
    )
    profile = {
        "id": "portal-profile",
        "version": 1,
        "name": "portal",
        "status": "demo",
        "rules": [
            {
                "id": "road-portal",
                "type": "crossing_allowed_only_via_portal",
                "applies_to_kind": "road",
                "parameters": {},
                "severity": "blocker",
                "missing_policy": "block",
                "source_reference": "test",
            }
        ],
        "geometry_tolerances": {"precision_m": 0.01},
    }
    result = compute_demo_route(
        {
            "corridor_width_m": 1,
            "search_settings": {
                "resolution_m": 1,
                "search_buffer_m": 5,
                "budget": {"max_expanded_states": 10_000},
            },
        },
        "astar",
        rule_profile_definition=profile,
        constraint_features=(road,),
        constraint_portals=(portal,),
        working_crs="EPSG:32637",
        metric_start=(500_002, 6_200_010),
        metric_goal=(500_018, 6_200_010),
    )

    assert result.outcome == "routes_found"
    assert result.alternative is not None
    assert result.alternative["validation_report"]["rule_profile"][
        "crossing_events"
    ][0]["quantity"] == 1
