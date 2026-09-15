from datetime import date
from decimal import Decimal
from random import Random

import pytest
from shapely.geometry import LineString, box

from heatroute.domain.constraints import CrossingEvent, CrossingPortal, MetricFeature
from heatroute.domain.costing import (
    CatalogRate,
    CostCatalogDefinition,
    build_quantity_report,
    compare_costs,
    price_quantities,
)
from heatroute.domain.routing import ComputationBudget, GridRoutingRequest, solve_grid
from heatroute.domain.routing.grid import CostZone, GridGraph
from heatroute.services.route_pipeline import RouteEndpoint, execute_route_pipeline


def test_heading_and_ordered_waypoints_are_part_of_search_state() -> None:
    request = GridRoutingRequest(
        bounds=(0, 0, 20, 20),
        start=(2, 2),
        goal=(18, 2),
        waypoints=((10, 14), (14, 8)),
        corridor_width_m=0.5,
        resolution_m=2,
        turn_cost=1,
    )
    graph = GridGraph(request)
    first_step_states = [state for state, _ in graph.neighbors(graph.start_state)]

    assert len({state[2] for state in first_step_states}) > 1
    result = solve_grid(graph, algorithm="astar")
    assert result.outcome == "routes_found"
    assert result.path.index((10, 14)) < result.path.index((14, 8))


@pytest.mark.parametrize("seed", range(6))
def test_seeded_small_graph_astar_matches_dijkstra(seed: int) -> None:
    random = Random(seed)
    blocked = {(random.randint(2, 10), random.randint(2, 10)) for _ in range(12)}
    obstacles = tuple((x - 0.2, y - 0.2, x + 0.2, y + 0.2) for x, y in sorted(blocked))
    request = GridRoutingRequest(
        bounds=(0, 0, 12, 12),
        start=(1, 1),
        goal=(11, 11),
        corridor_width_m=0.1,
        resolution_m=1,
        forbidden_rectangles=obstacles,
    )
    astar = solve_grid(GridGraph(request), algorithm="astar")
    dijkstra = solve_grid(GridGraph(request), algorithm="dijkstra")

    assert astar.outcome == dijkstra.outcome
    if astar.cost is None:
        assert dijkstra.cost is None
    else:
        assert dijkstra.cost is not None
        assert astar.cost == pytest.approx(dijkstra.cost)


def test_cost_objective_can_choose_a_longer_but_cheaper_route() -> None:
    expensive = box(7, 8, 13, 12)
    common = dict(
        bounds=(0, 0, 20, 20),
        start=(2, 10),
        goal=(18, 10),
        corridor_width_m=0.5,
        resolution_m=1,
        cost_zones=(CostZone(expensive, 50),),
    )
    shortest = solve_grid(GridGraph(GridRoutingRequest(**common)), algorithm="astar")
    cheapest = solve_grid(
        GridGraph(
            GridRoutingRequest(
                **common,
                objective="estimated_cost",
                default_cost_per_m=1,
                minimum_cost_per_m=1,
            )
        ),
        algorithm="astar",
    )

    assert shortest.path == ((2, 10), (18, 10)) or len(shortest.path) > 2
    assert LineString(cheapest.path).length > LineString(shortest.path).length
    assert not LineString(cheapest.path).intersects(expensive.buffer(-0.1))


def test_unknown_cost_lower_bound_uses_zero_and_negative_costs_are_rejected() -> None:
    graph = GridGraph(
        GridRoutingRequest(
            bounds=(0, 0, 10, 10),
            start=(1, 1),
            goal=(9, 9),
            corridor_width_m=0.2,
            resolution_m=1,
            objective="estimated_cost",
            minimum_cost_per_m=None,
        )
    )
    assert graph.heuristic(graph.start_state) == 0
    with pytest.raises(ValueError, match="non-negative"):
        CostZone(box(0, 0, 1, 1), -1)


def test_time_and_memory_budgets_are_diagnostic_not_no_route() -> None:
    graph = GridGraph(
        GridRoutingRequest(
            bounds=(0, 0, 100, 100),
            start=(1, 1),
            goal=(99, 99),
            corridor_width_m=0.2,
            resolution_m=1,
        )
    )
    memory = solve_grid(
        graph,
        algorithm="dijkstra",
        budget=ComputationBudget(max_memory_mb=0.00001),
    )
    wall = solve_grid(
        graph,
        algorithm="dijkstra",
        budget=ComputationBudget(max_wall_time_s=0.0000001),
    )
    assert (memory.outcome, memory.diagnostic) == ("budget_exceeded", "MAX_MEMORY")
    assert (wall.outcome, wall.diagnostic) == ("budget_exceeded", "MAX_WALL_TIME")


def test_uniform_objectives_deduplicate_into_one_tagged_alternative() -> None:
    snapshot = {
        "corridor_width_m": 1,
        "circuit_layout": "paired",
        "construction_methods": ["open_trench"],
        "objective_profiles": ["shortest", "estimated_cost"],
        "explicit_assumptions": ["TEST_ONLY"],
        "search_settings": {
            "resolution_m": 1,
            "search_buffer_m": 5,
            "max_alternatives": 1,
            "budget": {"max_expanded_states": 10_000},
        },
    }
    result = execute_route_pipeline(
        snapshot,
        "astar",
        working_crs="EPSG:32637",
        endpoints=(RouteEndpoint(None, (500_000, 6_200_000), (500_010, 6_200_000)),),
        cost_catalog_definition=_catalog(),
    )

    assert len(result.alternatives) == 1
    assert result.alternatives[0]["objective_tags"] == ["shortest", "estimated_cost"]
    assert result.alternatives[0]["validation_report"]["explanations"]


def test_two_real_corridors_produce_two_not_an_invented_third() -> None:
    snapshot = {
        "corridor_width_m": 1,
        "construction_methods": ["open_trench"],
        "objective_profiles": ["shortest"],
        "search_settings": {
            "resolution_m": 1,
            "search_buffer_m": 8,
            "max_alternatives": 3,
            "budget": {"max_expanded_states": 100_000},
        },
    }
    profile = {
        "id": "two-corridors",
        "version": 1,
        "name": "two corridors",
        "status": "demo",
        "rules": [
            {
                "id": "building-block",
                "type": "hard_exclusion",
                "applies_to_kind": "building",
                "parameters": {"clearance_m": 0},
                "severity": "blocker",
                "missing_policy": "block",
                "source_reference": "fixture",
            }
        ],
        "geometry_tolerances": {"precision_m": 0.01},
    }
    barrier = MetricFeature(
        "B",
        "building",
        box(500_008, 6_199_996, 500_012, 6_200_004),
        {},
        "fixture",
    )
    result = execute_route_pipeline(
        snapshot,
        "astar",
        working_crs="EPSG:32637",
        endpoints=(RouteEndpoint(None, (500_000, 6_200_000), (500_020, 6_200_000)),),
        rule_profile_definition=profile,
        constraint_features=(barrier,),
    )

    assert len(result.alternatives) == 2
    assert len({item["geometry_hash"] for item in result.alternatives}) == 2
    assert all(
        "penalty" not in line.get("rate_code", "").lower()
        for item in result.alternatives
        for line in item["cost_breakdown"].get("lines", [])
    )


def test_refinement_is_recorded_when_coarse_lattice_blocks_a_passage() -> None:
    snapshot = {
        "corridor_width_m": 0.2,
        "construction_methods": ["open_trench"],
        "objective_profiles": ["shortest"],
        "search_settings": {
            "resolution_m": 2,
            "refinement_resolution_m": 0.5,
            "search_buffer_m": 0.2,
            "max_alternatives": 1,
            "budget": {"max_expanded_states": 200_000},
        },
    }
    profile = {
        "id": "narrow-passage",
        "version": 1,
        "name": "narrow passage",
        "status": "demo",
        "rules": [
            {
                "id": "block",
                "type": "hard_exclusion",
                "applies_to_kind": "building",
                "parameters": {"clearance_m": 0},
                "severity": "blocker",
                "missing_policy": "block",
                "source_reference": "fixture",
            }
        ],
        "geometry_tolerances": {"precision_m": 0.01},
    }
    features = (
        MetricFeature("lower", "building", box(8, -100, 12, 0.75), {}, "fixture"),
        MetricFeature("upper", "building", box(8, 1.25, 12, 100), {}, "fixture"),
    )
    result = execute_route_pipeline(
        snapshot,
        "astar",
        working_crs="EPSG:32631",
        endpoints=(RouteEndpoint(None, (0, 0), (20, 0)),),
        rule_profile_definition=profile,
        constraint_features=features,
    )

    refinements = [
        search["refinement"] for search in result.statistics["searches"] if "refinement" in search
    ]
    assert refinements == [{"attempted": True, "from_resolution_m": 2.0, "to_resolution_m": 0.5}]
    assert result.outcome == "routes_found"


def test_paired_quantities_segments_and_decimal_cost_are_explicit() -> None:
    centerline = LineString([(0, 0), (100, 0)])
    portal = CrossingPortal(
        "road-1",
        LineString([(40, 0), (60, 0)]),
        width_m=2,
        applies_to_feature_ids=frozenset({"road"}),
        method="drilling",
    )
    event = CrossingEvent("crossing", "rule", "road", portal.id, portal.geometry)
    quantities = build_quantity_report(
        centerline,
        circuit_layout="paired",
        default_method="open_trench",
        portals=(portal,),
        crossing_events=(event,),
        corridor_width_m=2,
    )

    assert quantities.route_length_m == Decimal("100.000")
    assert sum(item.quantity or 0 for item in quantities.items if item.per == "pipe_m") == Decimal(
        "200.000"
    )
    assert sum(
        item.quantity or 0 for item in quantities.items if item.per == "corridor_m"
    ) == Decimal("100.000")
    assert (
        sum(item.quantity or 0 for item in quantities.items if item.key.startswith("portal-event"))
        == 1
    )
    for left, right in zip(quantities.segments, quantities.segments[1:], strict=False):
        assert left.end_chainage_m == right.start_chainage_m

    report = price_quantities(quantities, _catalog_definition())
    assert report.status == "complete"
    assert report.total == Decimal("2620.00")


def test_missing_rate_is_partial_and_baseline_comparison_is_safe() -> None:
    quantities = build_quantity_report(
        LineString([(0, 0), (10, 0)]),
        circuit_layout="single",
        default_method="open_trench",
        corridor_width_m=1,
    )
    incomplete = CostCatalogDefinition(
        "incomplete",
        1,
        "RUB",
        date(2026, 1, 1),
        "synthetic",
        "demo",
        "excluded",
        "ROUND_HALF_UP_2_DECIMALS",
        (),
        (
            CatalogRate(
                "TRENCH",
                "Trench",
                "m",
                "corridor_m",
                "open_trench",
                Decimal("1.005"),
                None,
                None,
                "fixture",
            ),
        ),
    )
    report = price_quantities(quantities, incomplete)
    comparison = compare_costs(report, report, same_model=True)
    assert report.status == "partial"
    assert report.unpriced_items
    assert not comparison.comparable
    assert comparison.percentage is None


def test_zero_baseline_and_changed_catalog_are_not_misleading() -> None:
    empty = price_quantities(
        build_quantity_report(
            LineString([(0, 0), (1, 0)]),
            circuit_layout="single",
            default_method="open_trench",
            corridor_width_m=1,
        ),
        CostCatalogDefinition(
            "zero",
            1,
            "RUB",
            date(2026, 1, 1),
            "synthetic",
            "demo",
            "excluded",
            "ROUND_HALF_UP_2_DECIMALS",
            (),
            tuple(
                CatalogRate(code, code, unit, per, method, Decimal(0), None, None, "fixture")
                for code, unit, per, method in (
                    ("T", "m", "corridor_m", "open_trench"),
                    ("P", "m", "pipe_m", "all"),
                    ("R", "m2", "m2", "open_trench"),
                    ("I", "event", "event", "demo_tie_in"),
                )
            ),
        ),
    )
    assert empty.status == "complete"
    assert compare_costs(empty, empty, same_model=True).percentage is None
    assert not compare_costs(empty, empty, same_model=False).comparable


def test_geometry_replacement_recomputes_quantities_without_stale_items() -> None:
    short = build_quantity_report(
        LineString([(0, 0), (10, 0)]),
        circuit_layout="single",
        default_method="open_trench",
        corridor_width_m=1,
    )
    long = build_quantity_report(
        LineString([(0, 0), (20, 0)]),
        circuit_layout="single",
        default_method="open_trench",
        corridor_width_m=1,
    )
    short_cost = price_quantities(short, _catalog_definition())
    long_cost = price_quantities(long, _catalog_definition())

    assert short.route_length_m == Decimal("10.000")
    assert long.route_length_m == Decimal("20.000")
    assert {item.key for item in long.items} == {
        "corridor:segment-1:route",
        "pipe:segment-1:route:1",
        "tie-in",
        "restoration-area",
    }
    assert long_cost.total is not None and short_cost.total is not None
    assert long_cost.total > short_cost.total


def _catalog() -> dict[str, object]:
    definition = _catalog_definition()
    return {
        "id": definition.id,
        "version": definition.version,
        "currency": definition.currency,
        "price_date": definition.price_date.isoformat(),
        "estimate_status": definition.estimate_status,
        "region_scope": definition.region_scope,
        "tax_policy": definition.tax_policy,
        "rounding_policy": definition.rounding_policy,
        "exclusions": [],
        "items": [
            {
                "code": item.code,
                "description": item.description,
                "quantity_unit": item.quantity_unit,
                "per": item.per,
                "applies_to_method": item.applies_to_method,
                "rate": str(item.rate),
                "source_reference": item.source_reference,
            }
            for item in definition.items
        ],
    }


def _catalog_definition() -> CostCatalogDefinition:
    rates = (
        CatalogRate(
            "T", "Trench", "m", "corridor_m", "open_trench", Decimal("10"), None, None, "fixture"
        ),
        CatalogRate(
            "D", "Drill", "m", "corridor_m", "drilling", Decimal("20"), None, None, "fixture"
        ),
        CatalogRate("P", "Pipe", "m", "pipe_m", "all", Decimal("5"), None, None, "fixture"),
        CatalogRate("R", "Restore", "m2", "m2", "open_trench", Decimal("1"), None, None, "fixture"),
        CatalogRate(
            "X", "Crossing", "event", "event", "drilling", Decimal("100"), None, None, "fixture"
        ),
        CatalogRate(
            "I", "Tie-in", "event", "event", "demo_tie_in", Decimal("100"), None, None, "fixture"
        ),
    )
    return CostCatalogDefinition(
        "demo-costs",
        1,
        "RUB",
        date(2026, 1, 1),
        "synthetic",
        "demo",
        "excluded",
        "ROUND_HALF_UP_2_DECIMALS",
        (),
        rates,
    )
