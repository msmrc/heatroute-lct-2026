from datetime import date

from shapely.geometry import LineString, Point

from heatroute.domain.network import (
    ConnectionCandidate,
    NetworkEdge,
    NetworkGraph,
    NetworkNode,
    screen_candidate,
    screen_candidates,
)


def test_xy_crossing_does_not_create_an_implicit_network_connection() -> None:
    nodes = [
        NetworkNode("A", Point(0, 0)),
        NetworkNode("B", Point(2, 2)),
        NetworkNode("C", Point(0, 2)),
        NetworkNode("D", Point(2, 0)),
    ]
    edges = [
        NetworkEdge("AB", "A", "B", LineString([(0, 0), (2, 2)])),
        NetworkEdge("CD", "C", "D", LineString([(0, 2), (2, 0)])),
    ]

    graph, report = NetworkGraph.build(nodes, edges)

    assert report.valid
    assert report.component_count == 2
    assert {finding.code for finding in report.findings} == {
        "AMBIGUOUS_XY_INTERSECTION"
    }
    assert {edge.source_id for edge in graph.incident_edges("A")} == {"AB"}
    assert {edge.source_id for edge in graph.incident_edges("C")} == {"CD"}


def test_nearby_endpoints_in_different_circuits_are_not_merged() -> None:
    nodes = [
        NetworkNode("S1", Point(0, 0), circuit="supply"),
        NetworkNode("S2", Point(1, 0), circuit="supply"),
        NetworkNode("R1", Point(1.01, 0), circuit="return"),
        NetworkNode("R2", Point(2, 0), circuit="return"),
    ]
    edges = [
        NetworkEdge("S", "S1", "S2", LineString([(0, 0), (1, 0)]), "supply"),
        NetworkEdge("R", "R1", "R2", LineString([(1.01, 0), (2, 0)]), "return"),
    ]

    graph, report = NetworkGraph.build(nodes, edges, endpoint_tolerance_m=0.25)

    assert report.valid
    assert report.component_count == 2
    assert graph.incident_edges("S2")[0].source_id == "S"
    assert graph.incident_edges("R1")[0].source_id == "R"


def test_cycle_is_preserved_while_dangling_and_circuit_errors_are_reported() -> None:
    nodes = [
        NetworkNode("A", Point(0, 0), circuit="supply"),
        NetworkNode("B", Point(1, 0), circuit="supply"),
        NetworkNode("C", Point(0, 1), circuit="supply"),
    ]
    edges = [
        NetworkEdge("AB", "A", "B", LineString([(0, 0), (1, 0)]), circuit="supply"),
        NetworkEdge("BC", "B", "C", LineString([(1, 0), (0, 1)]), circuit="supply"),
        NetworkEdge("CA", "C", "A", LineString([(0, 1), (0, 0)]), circuit="supply"),
        NetworkEdge("MISSING", "A", "Z", LineString([(0, 0), (2, 0)])),
        NetworkEdge("WRONG", "A", "B", LineString([(0, 0), (1, 0)]), circuit="return"),
    ]

    graph, report = NetworkGraph.build(nodes, edges)

    assert report.cycle_count == 1
    assert {finding.code for finding in report.findings} >= {
        "EDGE_ENDPOINT_NOT_FOUND",
        "CIRCUIT_MISMATCH",
    }
    assert set(graph.edges) == {"AB", "BC", "CA"}


def test_capacity_screening_does_not_double_subtract_net_reservations() -> None:
    net = ConnectionCandidate(
        "NET",
        "N1",
        "allowed",
        available_capacity_kw=100,
        capacity_basis="net_available",
        reserved_capacity_kw=60,
    )
    gross = ConnectionCandidate(
        "GROSS",
        "N2",
        "allowed",
        available_capacity_kw=100,
        capacity_basis="gross_with_separate_reservations",
        reserved_capacity_kw=60,
    )

    net_result = screen_candidate(
        net, planning_date=date(2026, 9, 8), requested_load_kw=80, geometry_only=False
    )
    gross_result = screen_candidate(
        gross, planning_date=date(2026, 9, 8), requested_load_kw=80, geometry_only=False
    )

    assert net_result.eligible
    assert net_result.effective_capacity_kw == 100
    assert not gross_result.eligible
    assert gross_result.effective_capacity_kw == 40


def test_requested_load_above_net_available_capacity_is_rejected() -> None:
    result = screen_candidate(
        ConnectionCandidate(
            "NET",
            "N1",
            "allowed",
            available_capacity_kw=100,
            capacity_basis="net_available",
        ),
        planning_date=date(2026, 9, 8),
        requested_load_kw=101,
        geometry_only=False,
    )

    assert not result.eligible
    assert result.effective_capacity_kw == 100
    assert result.reasons == ("CAPACITY_INSUFFICIENT",)


def test_unknown_capacity_is_not_zero_or_infinity_in_geometry_mode() -> None:
    result = screen_candidate(
        ConnectionCandidate("C", "N", "allowed"),
        planning_date=date(2026, 9, 8),
        requested_load_kw=50,
        geometry_only=True,
    )

    assert result.eligible
    assert result.check_status == "insufficient_data"
    assert result.effective_capacity_kw is None
    assert result.reasons == ("CAPACITY_NOT_PROVIDED",)


def test_arbitrary_pipe_location_is_not_an_implicit_connection_candidate() -> None:
    result = screen_candidate(
        ConnectionCandidate("nearest-pipe-point", None, "allowed"),
        planning_date=date(2026, 9, 8),
        requested_load_kw=None,
        geometry_only=True,
    )

    assert not result.eligible
    assert result.reasons == ("EXPLICIT_CONNECTION_NODE_REQUIRED",)


def test_unknown_permission_requires_named_exploratory_assumption() -> None:
    candidate = ConnectionCandidate("C", "N", "unknown")
    strict = screen_candidate(
        candidate,
        planning_date=date(2026, 9, 8),
        requested_load_kw=None,
        geometry_only=True,
    )
    exploratory = screen_candidate(
        candidate,
        planning_date=date(2026, 9, 8),
        requested_load_kw=None,
        geometry_only=True,
        mode="exploratory",
        allowed_assumptions=frozenset({"candidate-permission:C"}),
    )

    assert not strict.eligible
    assert exploratory.eligible
    assert exploratory.check_status == "insufficient_data"


def test_forbidden_nearest_candidate_is_rejected_and_next_eligible_is_selected() -> None:
    report = screen_candidates(
        (
            ConnectionCandidate("near", "N1", "forbidden", connector_length_m=5),
            ConnectionCandidate("far", "N2", "allowed", connector_length_m=12),
        ),
        planning_date=date(2026, 9, 8),
        requested_load_kw=None,
        geometry_only=True,
        limit=1,
    )

    assert [decision.source_id for decision in report.selected] == ["far"]
    assert [decision.source_id for decision in report.rejected] == ["near"]


def test_candidate_after_planning_date_is_not_selected() -> None:
    result = screen_candidate(
        ConnectionCandidate(
            "planned",
            "N1",
            "allowed",
            valid_from=date(2027, 1, 1),
        ),
        planning_date=date(2026, 9, 8),
        requested_load_kw=None,
        geometry_only=True,
    )

    assert not result.eligible
    assert result.reasons == ("NOT_YET_AVAILABLE",)
