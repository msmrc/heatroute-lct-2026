import json
from datetime import date
from pathlib import Path

import pytest
from shapely.geometry import LineString, MultiPolygon, Point, Polygon, box

from heatroute.domain.constraints import (
    CrossingPortal,
    EntryGate,
    MetricFeature,
    RouteConstraintValidator,
)
from heatroute.domain.network import CandidateDecision
from heatroute.domain.rules import RuleDefinition, RuleProfileVersion


def profile_with(*rules: RuleDefinition) -> RuleProfileVersion:
    return RuleProfileVersion(
        id="profile",
        version=1,
        name="test",
        status="demo",
        rules=rules,
        missing_data_policy={},
        geometry_tolerances={},
        source_references=("test",),
        schema_version="1.0",
    )


def rule(rule_type: str, kind: str, **parameters: object) -> RuleDefinition:
    return RuleDefinition.from_dict(
        {
            "id": f"{rule_type}-{kind}",
            "type": rule_type,
            "applies_to_kind": kind,
            "parameters": parameters,
            "severity": "blocker" if rule_type != "soft_exposure" else "warning",
            "missing_policy": "block",
            "source_reference": "test:rule",
        }
    )


def feature(feature_id: str, kind: str, geometry: object) -> MetricFeature:
    return MetricFeature(feature_id, kind, geometry, {}, f"test:{feature_id}")  # type: ignore[arg-type]


def test_checked_demo_profile_uses_supported_evaluator_types() -> None:
    raw = json.loads(Path("examples/rule_profile.demo.json").read_text(encoding="utf-8"))
    profile = RuleProfileVersion.from_dict(raw)

    validator = RouteConstraintValidator()
    validator.registry.assert_profile_supported(profile)

    assert profile.status == "demo"
    assert len(profile.rules) == 4


def test_corridor_not_centerline_is_checked_against_exact_geometry() -> None:
    obstacle = feature("B", "building", box(4, 1.5, 6, 3))
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=4,
        profile=profile_with(rule("clearance", "building", clearance_m=0)),
        features=(obstacle,),
    )

    assert not result.valid
    assert result.findings[0].code == "BUILDING_CORRIDOR_INTERSECTION"


def test_polygon_hole_is_not_replaced_by_bbox_collision() -> None:
    obstacle = feature(
        "RING",
        "forbidden_zone",
        Polygon(
            shell=[(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)],
            holes=[[(3, 3), (7, 3), (7, 7), (3, 7), (3, 3)]],
        ),
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(4, 5), (6, 5)]),
        corridor_width_m=1,
        profile=profile_with(rule("hard_exclusion", "forbidden_zone")),
        features=(obstacle,),
    )

    assert result.valid


def test_multipolygon_uses_exact_parts_instead_of_its_bbox() -> None:
    obstacle = feature(
        "MULTI",
        "forbidden_zone",
        MultiPolygon((box(0, 0, 2, 2), box(8, 0, 10, 2))),
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(4, 1), (6, 1)]),
        corridor_width_m=1,
        profile=profile_with(rule("hard_exclusion", "forbidden_zone")),
        features=(obstacle,),
    )

    assert result.valid


def test_clearance_already_included_in_geometry_is_not_added_twice() -> None:
    prebuffered = MetricFeature(
        "B",
        "building",
        box(4, 2, 6, 4),
        {"clearance_included_m": 2},
        "test:B",
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=2,
        profile=profile_with(rule("clearance", "building", clearance_m=2)),
        features=(prebuffered,),
    )

    assert result.valid


def test_boundary_touch_obeys_profile_touch_policy() -> None:
    obstacle = feature("B", "building", box(4, 1, 6, 3))
    forbid_profile = profile_with(rule("clearance", "building", clearance_m=0))
    allow_profile = RuleProfileVersion(
        id=forbid_profile.id,
        version=forbid_profile.version,
        name=forbid_profile.name,
        status=forbid_profile.status,
        rules=forbid_profile.rules,
        missing_data_policy=forbid_profile.missing_data_policy,
        geometry_tolerances={
            "precision_m": 0.01,
            "touch_policy": "allow_boundary_touch",
        },
        source_references=forbid_profile.source_references,
        schema_version=forbid_profile.schema_version,
    )
    validator = RouteConstraintValidator()
    forbidden = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=2,
        profile=forbid_profile,
        features=(obstacle,),
    )
    allowed = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=2,
        profile=allow_profile,
        features=(obstacle,),
    )

    assert not forbidden.valid
    assert forbidden.findings[0].geometry is not None
    assert allowed.valid


def test_road_crossing_requires_named_approved_portal() -> None:
    road = feature("ROAD", "road", box(4, -3, 6, 3))
    profile = profile_with(rule("crossing_allowed_only_via_portal", "road"))
    validator = RouteConstraintValidator()
    blocked = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile,
        features=(road,),
    )
    portal = CrossingPortal(
        "PORTAL",
        LineString([(3.5, 0), (6.5, 0)]),
        width_m=2,
        applies_to_feature_ids=frozenset({"ROAD"}),
    )
    allowed = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile,
        features=(road,),
        portals=(portal,),
    )

    assert not blocked.valid
    assert blocked.findings[0].code == "ROAD_CROSSING_WITHOUT_PORTAL"
    assert allowed.valid
    assert len(allowed.crossing_events) == 1
    assert allowed.crossing_events[0].portal_id == "PORTAL"
    assert allowed.crossing_events[0].quantity == 1


def test_long_portal_crossing_is_one_event_not_one_per_grid_step() -> None:
    road = feature("ROAD", "road", box(4, -10, 16, 10))
    portal = CrossingPortal(
        "PORTAL",
        LineString([(3, 0), (17, 0)]),
        width_m=2,
        applies_to_feature_ids=frozenset({"ROAD"}),
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (20, 0)]),
        corridor_width_m=1,
        profile=profile_with(rule("crossing_allowed_only_via_portal", "road")),
        features=(road,),
        portals=(portal,),
    )

    assert result.valid
    assert [(event.portal_id, event.quantity) for event in result.crossing_events] == [
        ("PORTAL", 1)
    ]


def test_portal_does_not_override_an_unrelated_building_exclusion() -> None:
    road = feature("ROAD", "road", box(4, -3, 6, 3))
    building = feature("BUILDING", "building", box(5, -1, 5.5, 1))
    portal = CrossingPortal(
        "PORTAL",
        LineString([(3.5, 0), (6.5, 0)]),
        width_m=2,
        applies_to_feature_ids=frozenset({"ROAD"}),
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(
            rule("crossing_allowed_only_via_portal", "road"),
            rule("clearance", "building", clearance_m=0),
        ),
        features=(road, building),
        portals=(portal,),
    )

    assert not result.valid
    assert {finding.code for finding in result.findings} == {
        "BUILDING_CORRIDOR_INTERSECTION"
    }


def test_coverage_unknown_blocks_strict_and_needs_explicit_exploratory_assumption() -> None:
    coverage = MetricFeature(
        "COVERAGE",
        "coverage_area",
        box(0, -2, 6, 2),
        {"covered_kinds": ["utility_line"], "completeness": "known_complete"},
        "test:coverage",
    )
    validator = RouteConstraintValidator()
    profile = profile_with(rule("coverage_required", "utility_line"))
    strict = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile,
        features=(),
        coverage=(coverage,),
    )
    exploratory = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile,
        features=(),
        coverage=(coverage,),
        mode="exploratory",
        allowed_assumptions=frozenset({"coverage:utility_line"}),
    )

    assert not strict.valid
    assert exploratory.valid
    assert exploratory.findings[0].check_status == "insufficient_data"
    assert exploratory.unverified_length_m == pytest.approx(4)


def test_partial_coverage_is_not_treated_as_confirmed_absence() -> None:
    partial = MetricFeature(
        "PARTIAL",
        "coverage_area",
        box(-1, -2, 11, 2),
        {"covered_kinds": ["building"], "completeness": "partial"},
        "test:partial",
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(rule("coverage_required", "building")),
        features=(),
        coverage=(partial,),
    )

    assert not result.valid
    assert result.findings[0].check_status == "failed"
    assert result.unverified_length_m == pytest.approx(10)


def test_overlapping_coverage_gaps_do_not_double_unverified_length() -> None:
    coverage = MetricFeature(
        "COVERAGE",
        "coverage_area",
        box(0, -2, 5, 2),
        {
            "covered_kinds": ["building", "utility_line"],
            "completeness": "known_complete",
        },
        "test:coverage",
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(
            rule("coverage_required", "building"),
            rule("coverage_required", "utility_line"),
        ),
        features=(),
        coverage=(coverage,),
        mode="exploratory",
        allowed_assumptions=frozenset(
            {"coverage:building", "coverage:utility_line"}
        ),
    )

    assert result.valid
    assert result.unverified_length_m == pytest.approx(5)


def test_2d_utility_crossing_without_depth_is_insufficient_data() -> None:
    utility = feature("U1", "utility_line", LineString([(5, -2), (5, 2)]))
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(rule("soft_exposure", "utility_line")),
        features=(utility,),
    )

    assert result.valid
    assert result.findings[0].code == "UTILITY_DEPTH_UNKNOWN"
    assert result.findings[0].check_status == "insufficient_data"


def test_candidate_rule_requires_explicit_screening_result() -> None:
    candidate_rule = rule("candidate_eligibility", "connection_candidate")
    validator = RouteConstraintValidator()
    missing = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(candidate_rule),
        features=(),
    )
    accepted = validator.validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(candidate_rule),
        features=(),
        candidate_decisions=(CandidateDecision("C1", True, "passed", (), 100),),
    )

    assert not missing.valid
    assert missing.findings[0].code == "CANDIDATE_SCREENING_NOT_PROVIDED"
    assert accepted.valid


def test_temporal_rule_rejects_feature_outside_planning_date() -> None:
    planned_node = MetricFeature(
        "N1",
        "network_node",
        Point(5, 0),
        {"available_from": "2027-01-01"},
        "test:N1",
    )
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (10, 0)]),
        corridor_width_m=1,
        profile=profile_with(rule("temporal_availability", "network_node")),
        features=(planned_node,),
        planning_date=date(2026, 9, 8),
    )

    assert not result.valid
    assert result.findings[0].code == "FEATURE_NOT_AVAILABLE_ON_PLANNING_DATE"


def test_entry_connector_must_remain_inside_named_gate_and_length_limit() -> None:
    gate = EntryGate("G", "B", box(8, -1, 10, 1), Point(10, 0), 3)
    profile = profile_with()
    valid = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (8, 0)]),
        corridor_width_m=1,
        profile=profile,
        features=(),
        entry_gate=gate,
        entry_connector=LineString([(8, 0), (10, 0)]),
    )
    invalid = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (8, 0)]),
        corridor_width_m=1,
        profile=profile,
        features=(),
        entry_gate=gate,
        entry_connector=LineString([(8, 0), (12, 0)]),
    )

    assert valid.valid
    assert not invalid.valid
    assert invalid.findings[0].code == "ENTRY_CONNECTOR_TOO_LONG"


def test_entry_connector_is_part_of_corridor_and_cannot_cross_another_building() -> None:
    gate = EntryGate("G", "TARGET", box(8, -1, 10, 1), Point(10, 0), 3)
    other_building = feature("OTHER", "building", box(8.5, -0.5, 9, 0.5))
    result = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (8, 0)]),
        corridor_width_m=0.5,
        profile=profile_with(rule("clearance", "building", clearance_m=0)),
        features=(other_building,),
        entry_gate=gate,
        entry_connector=LineString([(8, 0), (10, 0)]),
    )

    assert not result.valid
    assert result.findings[0].code == "BUILDING_CORRIDOR_INTERSECTION"


def test_named_entry_gate_is_a_local_exception_for_target_building_only() -> None:
    target = feature("TARGET", "building", box(9, -2, 12, 2))
    gate = EntryGate("G", "TARGET", box(8.5, -0.5, 10.5, 0.5), Point(10, 0), 2)
    profile = profile_with(
        rule("clearance", "building", clearance_m=0, allow_named_entry_gate=True)
    )
    local_entry = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (8.5, 0)]),
        corridor_width_m=0.5,
        profile=profile,
        features=(target,),
        entry_gate=gate,
        entry_connector=LineString([(8.5, 0), (10, 0)]),
    )
    through_building = RouteConstraintValidator().validate(
        centerline=LineString([(0, 0), (8.5, 0)]),
        corridor_width_m=0.5,
        profile=profile,
        features=(target,),
        entry_gate=gate,
        entry_connector=LineString([(8.5, 0), (11, 0)]),
    )

    assert local_entry.valid
    assert not through_building.valid


def test_rule_profiles_reject_unknown_code_and_template_placeholders() -> None:
    with pytest.raises(ValueError, match="unsupported rule type"):
        rule("arbitrary_python", "building")
    with pytest.raises(ValueError, match="placeholders"):
        RuleDefinition.from_dict(
            {
                "id": "unsafe",
                "type": "hard_exclusion",
                "applies_to_kind": "building",
                "parameters": {},
                "severity": "blocker",
                "missing_policy": "block",
                "source_reference": "test",
                "message_template": "{__import__}",
            }
        )
