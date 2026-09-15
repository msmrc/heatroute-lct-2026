from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any, Literal

from shapely import union_all
from shapely.geometry import LineString, Point
from shapely.geometry.base import BaseGeometry

from heatroute.domain.network import CandidateDecision
from heatroute.domain.rules import (
    RuleDefinition,
    RuleEvaluatorRegistry,
    RuleProfileVersion,
)


@dataclass(frozen=True)
class MetricFeature:
    id: str
    kind: str
    geometry: BaseGeometry
    attributes: dict[str, Any]
    source_ref: str


@dataclass(frozen=True)
class CrossingPortal:
    id: str
    geometry: LineString
    width_m: float
    applies_to_feature_ids: frozenset[str]
    approval_status: str = "approved"
    method: str = "special_crossing"
    quantity_model_key: str | None = None

    @property
    def footprint(self) -> BaseGeometry:
        return self.geometry.buffer(self.width_m / 2)


@dataclass(frozen=True)
class EntryGate:
    id: str
    target_building_id: str
    geometry: BaseGeometry
    entry_point: Point
    max_connector_length_m: float


@dataclass(frozen=True)
class ConstraintFinding:
    code: str
    severity: Literal["info", "warning", "error"]
    check_status: Literal["passed", "failed", "insufficient_data"]
    message: str
    rule_id: str
    feature_ids: tuple[str, ...] = ()
    measured: float | None = None
    limit: float | None = None
    unit: str | None = None
    missing_fields: tuple[str, ...] = ()
    recommended_action: str | None = None
    source_refs: tuple[str, ...] = ()
    assumption_id: str | None = None
    geometry: BaseGeometry | None = None


@dataclass(frozen=True)
class CrossingEvent:
    event_type: str
    rule_id: str
    feature_id: str
    portal_id: str
    geometry: BaseGeometry
    quantity: int = 1


@dataclass(frozen=True)
class RuleEvaluationContext:
    centerline: LineString
    route_axis: BaseGeometry
    corridor: BaseGeometry
    features: tuple[MetricFeature, ...]
    portals: tuple[CrossingPortal, ...]
    coverage: tuple[MetricFeature, ...]
    mode: Literal["strict", "exploratory"]
    allowed_assumptions: frozenset[str]
    entry_gate: EntryGate | None = None
    entry_connector: LineString | None = None
    candidate_decisions: tuple[CandidateDecision, ...] = ()
    planning_date: date | None = None
    precision_m: float = 0.01
    touch_policy: str = "forbid_except_named_entry_contact"


@dataclass(frozen=True)
class RouteConstraintReport:
    valid: bool
    corridor: BaseGeometry
    findings: tuple[ConstraintFinding, ...]
    unverified_length_m: float
    crossing_events: tuple[CrossingEvent, ...] = ()


def _rule_severity(rule: RuleDefinition) -> Literal["info", "warning", "error"]:
    return "error" if rule.severity == "blocker" else rule.severity


def _hard_exclusion(
    rule: RuleDefinition, context: RuleEvaluationContext
) -> list[ConstraintFinding]:
    findings: list[ConstraintFinding] = []
    clearance = float(rule.parameters.get("clearance_m", 0))
    for feature in context.features:
        if feature.kind != rule.applies_to_kind:
            continue
        included_clearance = float(feature.attributes.get("clearance_included_m", 0))
        effective_clearance = max(0.0, clearance - included_clearance)
        forbidden = feature.geometry.buffer(effective_clearance)
        conflict = context.corridor.intersection(forbidden)
        gate = context.entry_gate
        if (
            not conflict.is_empty
            and gate is not None
            and feature.id == gate.target_building_id
            and bool(rule.parameters.get("allow_named_entry_gate"))
        ):
            conflict = conflict.difference(gate.geometry)
        boundary_touch_allowed = context.touch_policy == "allow_boundary_touch"
        conflict_is_material = not conflict.is_empty and not (
            boundary_touch_allowed and conflict.area <= context.precision_m**2
        )
        if conflict_is_material:
            code = (
                "BUILDING_CORRIDOR_INTERSECTION"
                if feature.kind == "building"
                else "HARD_EXCLUSION_INTERSECTION"
            )
            findings.append(
                ConstraintFinding(
                    code=code,
                    severity=_rule_severity(rule),
                    check_status="failed",
                    message="The swept corridor intersects a confirmed forbidden geometry.",
                    rule_id=rule.id,
                    feature_ids=(feature.id,),
                    measured=conflict.area,
                    limit=0,
                    unit="m2",
                    source_refs=(feature.source_ref, rule.source_reference),
                    geometry=conflict,
                )
            )
    return findings


def _portal_crossing(
    rule: RuleDefinition, context: RuleEvaluationContext
) -> list[ConstraintFinding]:
    findings: list[ConstraintFinding] = []
    tolerance = float(rule.parameters.get("tolerance_m", 0.01))
    for feature in context.features:
        if feature.kind != rule.applies_to_kind:
            continue
        crossing = context.corridor.intersection(feature.geometry)
        if crossing.is_empty:
            continue
        valid_portal = any(
            portal.approval_status == "approved"
            and feature.id in portal.applies_to_feature_ids
            and portal.footprint.buffer(tolerance).covers(crossing)
            for portal in context.portals
        )
        if not valid_portal:
            findings.append(
                ConstraintFinding(
                    code="ROAD_CROSSING_WITHOUT_PORTAL",
                    severity=_rule_severity(rule),
                    check_status="failed",
                    message="Road crossing is not covered by an approved named portal.",
                    rule_id=rule.id,
                    feature_ids=(feature.id,),
                    source_refs=(feature.source_ref, rule.source_reference),
                    geometry=crossing,
                )
            )
    return findings


def _coverage_required(
    rule: RuleDefinition, context: RuleEvaluationContext
) -> list[ConstraintFinding]:
    applicable = [
        feature.geometry
        for feature in context.coverage
        if rule.applies_to_kind in feature.attributes.get("covered_kinds", [])
        and feature.attributes.get("completeness") in {"known_complete", "synthetic"}
    ]
    covered = union_all(applicable) if applicable else None
    if covered is not None and covered.covers(context.corridor):
        return []
    unverified = context.route_axis if covered is None else context.route_axis.difference(covered)
    assumption_id = f"coverage:{rule.applies_to_kind}"
    assumption_allowed = (
        context.mode == "exploratory" and assumption_id in context.allowed_assumptions
    )
    return [
        ConstraintFinding(
            code="OUTSIDE_DATA_COVERAGE",
            severity="warning" if assumption_allowed else _rule_severity(rule),
            check_status="insufficient_data" if assumption_allowed else "failed",
            message="Part of the corridor is outside asserted data coverage.",
            rule_id=rule.id,
            measured=unverified.length,
            limit=0,
            unit="m",
            missing_fields=("coverage_area",),
            recommended_action="Provide coverage or record an allowed exploratory assumption.",
            source_refs=(rule.source_reference,),
            assumption_id=assumption_id if assumption_allowed else None,
            geometry=unverified,
        )
    ]


def _soft_exposure(rule: RuleDefinition, context: RuleEvaluationContext) -> list[ConstraintFinding]:
    findings: list[ConstraintFinding] = []
    for feature in context.features:
        if feature.kind != rule.applies_to_kind or not context.corridor.intersects(
            feature.geometry
        ):
            continue
        missing = tuple(
            field
            for field in ("elevation_m", "vertical_datum")
            if feature.attributes.get(field) is None
        )
        findings.append(
            ConstraintFinding(
                code="UTILITY_DEPTH_UNKNOWN" if missing else "UTILITY_POTENTIAL_CROSSING",
                severity=_rule_severity(rule),
                check_status="insufficient_data" if missing else "passed",
                message="A 2D utility crossing requires a separate vertical review.",
                rule_id=rule.id,
                feature_ids=(feature.id,),
                missing_fields=missing,
                recommended_action="Obtain elevations in a compatible vertical datum.",
                source_refs=(feature.source_ref, rule.source_reference),
                geometry=context.corridor.intersection(feature.geometry),
            )
        )
    return findings


def _candidate_eligibility(
    rule: RuleDefinition, context: RuleEvaluationContext
) -> list[ConstraintFinding]:
    if not context.candidate_decisions:
        blocks = rule.missing_policy == "block"
        return [
            ConstraintFinding(
                code="CANDIDATE_SCREENING_NOT_PROVIDED",
                severity=_rule_severity(rule) if blocks else "warning",
                check_status="failed" if blocks else "insufficient_data",
                message="Connection candidate screening was not supplied to validation.",
                rule_id=rule.id,
                missing_fields=("candidate_decisions",),
                recommended_action="Screen explicit connection candidates before routing.",
                source_refs=(rule.source_reference,),
            )
        ]
    findings: list[ConstraintFinding] = []
    for decision in context.candidate_decisions:
        if decision.eligible:
            continue
        findings.append(
            ConstraintFinding(
                code="CANDIDATE_NOT_ELIGIBLE",
                severity=_rule_severity(rule),
                check_status="failed",
                message="The selected connection candidate failed screening.",
                rule_id=rule.id,
                feature_ids=(decision.source_id,),
                recommended_action="Select an eligible explicit connection candidate.",
                source_refs=(rule.source_reference,),
            )
        )
    return findings


def _temporal_availability(
    rule: RuleDefinition, context: RuleEvaluationContext
) -> list[ConstraintFinding]:
    if context.planning_date is None:
        blocks = rule.missing_policy == "block"
        return [
            ConstraintFinding(
                code="PLANNING_DATE_NOT_PROVIDED",
                severity=_rule_severity(rule) if blocks else "warning",
                check_status="failed" if blocks else "insufficient_data",
                message="Temporal availability cannot be checked without a planning date.",
                rule_id=rule.id,
                missing_fields=("planning_date",),
                source_refs=(rule.source_reference,),
            )
        ]
    findings: list[ConstraintFinding] = []
    for feature in context.features:
        if feature.kind != rule.applies_to_kind:
            continue
        raw_from = feature.attributes.get("available_from")
        raw_until = feature.attributes.get("available_until")
        if raw_from is None and raw_until is None:
            blocks = rule.missing_policy == "block"
            findings.append(
                ConstraintFinding(
                    code="TEMPORAL_AVAILABILITY_UNKNOWN",
                    severity=_rule_severity(rule) if blocks else "warning",
                    check_status="failed" if blocks else "insufficient_data",
                    message="Feature availability dates are unknown.",
                    rule_id=rule.id,
                    feature_ids=(feature.id,),
                    missing_fields=("available_from", "available_until"),
                    source_refs=(feature.source_ref, rule.source_reference),
                )
            )
            continue
        try:
            available_from = date.min if raw_from is None else date.fromisoformat(str(raw_from))
            available_until = date.max if raw_until is None else date.fromisoformat(str(raw_until))
        except ValueError:
            findings.append(
                ConstraintFinding(
                    code="TEMPORAL_AVAILABILITY_INVALID",
                    severity=_rule_severity(rule),
                    check_status="failed",
                    message="Feature availability date is invalid.",
                    rule_id=rule.id,
                    feature_ids=(feature.id,),
                    source_refs=(feature.source_ref, rule.source_reference),
                )
            )
            continue
        if not available_from <= context.planning_date <= available_until:
            findings.append(
                ConstraintFinding(
                    code="FEATURE_NOT_AVAILABLE_ON_PLANNING_DATE",
                    severity=_rule_severity(rule),
                    check_status="failed",
                    message="Feature is not available on the planning date.",
                    rule_id=rule.id,
                    feature_ids=(feature.id,),
                    source_refs=(feature.source_ref, rule.source_reference),
                )
            )
    return findings


def default_rule_registry() -> RuleEvaluatorRegistry:
    registry = RuleEvaluatorRegistry()
    registry.register("hard_exclusion", _hard_exclusion)
    registry.register("clearance", _hard_exclusion)
    registry.register("coverage_required", _coverage_required)
    registry.register("crossing_allowed_only_via_portal", _portal_crossing)
    registry.register("soft_exposure", _soft_exposure)
    registry.register("candidate_eligibility", _candidate_eligibility)
    registry.register("temporal_availability", _temporal_availability)
    return registry


def build_search_exclusions(
    *,
    profile: RuleProfileVersion,
    features: tuple[MetricFeature, ...],
    portals: tuple[CrossingPortal, ...] = (),
    coverage: tuple[MetricFeature, ...] = (),
    aoi: BaseGeometry | None = None,
    mode: Literal["strict", "exploratory"] = "strict",
    allowed_assumptions: frozenset[str] = frozenset(),
    entry_gate: EntryGate | None = None,
) -> tuple[BaseGeometry, ...]:
    exclusions: list[BaseGeometry] = []
    for rule in profile.rules:
        if rule.type in {"hard_exclusion", "clearance"}:
            clearance = float(rule.parameters.get("clearance_m", 0))
            for feature in features:
                if feature.kind != rule.applies_to_kind:
                    continue
                included = float(feature.attributes.get("clearance_included_m", 0))
                forbidden = feature.geometry.buffer(max(0.0, clearance - included))
                if (
                    entry_gate is not None
                    and feature.id == entry_gate.target_building_id
                    and bool(rule.parameters.get("allow_named_entry_gate"))
                ):
                    forbidden = forbidden.difference(entry_gate.geometry)
                if not forbidden.is_empty:
                    exclusions.append(forbidden)
        elif rule.type == "crossing_allowed_only_via_portal":
            for feature in features:
                if feature.kind != rule.applies_to_kind:
                    continue
                permitted = [
                    portal.footprint
                    for portal in portals
                    if portal.approval_status == "approved"
                    and feature.id in portal.applies_to_feature_ids
                ]
                forbidden = (
                    feature.geometry
                    if not permitted
                    else feature.geometry.difference(union_all(permitted))
                )
                if not forbidden.is_empty:
                    exclusions.append(forbidden)
        elif rule.type == "coverage_required" and aoi is not None:
            assumption_id = f"coverage:{rule.applies_to_kind}"
            assumed = mode == "exploratory" and assumption_id in allowed_assumptions
            if assumed:
                continue
            applicable = [
                item.geometry
                for item in coverage
                if rule.applies_to_kind in item.attributes.get("covered_kinds", [])
                and item.attributes.get("completeness") in {"known_complete", "synthetic"}
            ]
            verified = union_all(applicable) if applicable else None
            unknown_area = aoi if verified is None else aoi.difference(verified)
            if not unknown_area.is_empty:
                exclusions.append(unknown_area)
    return tuple(exclusions)


class RouteConstraintValidator:
    def __init__(self, registry: RuleEvaluatorRegistry | None = None) -> None:
        self.registry = registry or default_rule_registry()

    def validate(
        self,
        *,
        centerline: LineString,
        corridor_width_m: float,
        profile: RuleProfileVersion,
        features: tuple[MetricFeature, ...],
        portals: tuple[CrossingPortal, ...] = (),
        coverage: tuple[MetricFeature, ...] = (),
        mode: Literal["strict", "exploratory"] = "strict",
        allowed_assumptions: frozenset[str] = frozenset(),
        entry_gate: EntryGate | None = None,
        entry_connector: LineString | None = None,
        candidate_decisions: tuple[CandidateDecision, ...] = (),
        planning_date: date | None = None,
    ) -> RouteConstraintReport:
        if corridor_width_m <= 0:
            raise ValueError("corridor_width_m must be positive")
        route_axis = (
            centerline if entry_connector is None else union_all((centerline, entry_connector))
        )
        corridor_parts = [centerline.buffer(corridor_width_m / 2)]
        if entry_connector is not None:
            corridor_parts.append(entry_connector.buffer(corridor_width_m / 2))
        corridor = union_all(corridor_parts)
        context = RuleEvaluationContext(
            centerline=centerline,
            route_axis=route_axis,
            corridor=corridor,
            features=features,
            portals=portals,
            coverage=coverage,
            mode=mode,
            allowed_assumptions=allowed_assumptions,
            entry_gate=entry_gate,
            entry_connector=entry_connector,
            candidate_decisions=candidate_decisions,
            planning_date=planning_date,
            precision_m=float(profile.geometry_tolerances.get("precision_m", 0.01)),
            touch_policy=str(
                profile.geometry_tolerances.get("touch_policy", "forbid_except_named_entry_contact")
            ),
        )
        findings: list[ConstraintFinding] = []
        for rule in profile.rules:
            findings.extend(self.registry.evaluate(rule, context))
        if entry_gate is not None:
            findings.extend(_validate_entry(entry_gate, entry_connector))
        failed = any(finding.check_status == "failed" for finding in findings)
        unverified_geometries: list[BaseGeometry] = []
        for rule in profile.rules:
            if rule.type != "coverage_required":
                continue
            applicable = [
                feature.geometry
                for feature in coverage
                if rule.applies_to_kind in feature.attributes.get("covered_kinds", [])
                and feature.attributes.get("completeness") in {"known_complete", "synthetic"}
            ]
            covered = union_all(applicable) if applicable else None
            if covered is None:
                unverified_geometries.append(route_axis)
            elif not covered.covers(corridor):
                unverified_geometries.append(route_axis.difference(covered))
        unverified_length = union_all(unverified_geometries).length if unverified_geometries else 0
        return RouteConstraintReport(
            valid=not failed,
            corridor=corridor,
            findings=tuple(findings),
            unverified_length_m=unverified_length,
            crossing_events=_collect_crossing_events(profile, context),
        )


def _collect_crossing_events(
    profile: RuleProfileVersion, context: RuleEvaluationContext
) -> tuple[CrossingEvent, ...]:
    """Return one named crossing event per feature/portal, independent of grid steps."""
    events: list[CrossingEvent] = []
    seen: set[tuple[str, str, str]] = set()
    for rule in profile.rules:
        if rule.type != "crossing_allowed_only_via_portal":
            continue
        tolerance = float(rule.parameters.get("tolerance_m", context.precision_m))
        for feature in context.features:
            if feature.kind != rule.applies_to_kind:
                continue
            crossing = context.corridor.intersection(feature.geometry)
            if crossing.is_empty:
                continue
            for portal in context.portals:
                key = (rule.id, feature.id, portal.id)
                if (
                    key not in seen
                    and portal.approval_status == "approved"
                    and feature.id in portal.applies_to_feature_ids
                    and portal.footprint.buffer(tolerance).covers(crossing)
                ):
                    seen.add(key)
                    events.append(
                        CrossingEvent(
                            event_type="portal_crossing",
                            rule_id=rule.id,
                            feature_id=feature.id,
                            portal_id=portal.id,
                            geometry=crossing,
                        )
                    )
                    break
    return tuple(events)


def _validate_entry(gate: EntryGate, connector: LineString | None) -> list[ConstraintFinding]:
    if connector is None:
        return [
            ConstraintFinding(
                code="ENTRY_CONNECTOR_MISSING",
                severity="error",
                check_status="failed",
                message="A named entry gate requires an explicit connector.",
                rule_id="entry-gate",
                feature_ids=(gate.id, gate.target_building_id),
                geometry=gate.geometry,
            )
        ]
    if connector.length > gate.max_connector_length_m:
        return [
            ConstraintFinding(
                code="ENTRY_CONNECTOR_TOO_LONG",
                severity="error",
                check_status="failed",
                message="Entry connector exceeds the gate limit.",
                rule_id="entry-gate",
                feature_ids=(gate.id, gate.target_building_id),
                measured=connector.length,
                limit=gate.max_connector_length_m,
                unit="m",
                geometry=connector,
            )
        ]
    if (
        not gate.geometry.covers(connector)
        or Point(connector.coords[-1]).distance(gate.entry_point) > 0.01
    ):
        return [
            ConstraintFinding(
                code="ENTRY_CONNECTOR_OUTSIDE_GATE",
                severity="error",
                check_status="failed",
                message="Entry connector is not contained by the named gate or entry point.",
                rule_id="entry-gate",
                feature_ids=(gate.id, gate.target_building_id),
                geometry=connector.difference(gate.geometry),
            )
        ]
    return []
