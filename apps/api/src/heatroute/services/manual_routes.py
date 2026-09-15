from __future__ import annotations

from datetime import date
from typing import Any
from uuid import UUID

from pyproj import Transformer
from shapely.geometry import LineString, Point, box, mapping, shape
from shapely.geometry.base import BaseGeometry
from shapely.ops import transform
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.domain.constraints import (
    ConstraintFinding,
    MetricFeature,
    RouteConstraintValidator,
)
from heatroute.domain.network import CandidateDecision
from heatroute.domain.rules import RuleProfileVersion as DomainRuleProfileVersion
from heatroute.models import (
    Project,
    RuleProfile,
    RuleProfileVersion,
    Scenario,
    ScenarioRevision,
)
from heatroute.services.model_context import (
    load_materialized_model,
    resolve_building_route_endpoints,
    resolve_version_selection,
    screen_materialized_candidates,
)


def validate_manual_route(
    session: Session,
    *,
    revision: ScenarioRevision,
    centerline_wgs84: dict[str, Any],
) -> dict[str, Any]:
    scenario = session.get(Scenario, revision.scenario_id)
    if scenario is None:
        raise LookupError("scenario not found")
    project = session.get(Project, scenario.project_id)
    if project is None or project.working_crs is None:
        raise ValueError("project working CRS is required")
    raw_line = shape(centerline_wgs84)
    if not isinstance(raw_line, LineString) or len(raw_line.coords) < 2:
        raise ValueError("centerline_wgs84 must be a LineString with at least two points")
    to_metric = Transformer.from_crs("EPSG:4326", project.working_crs, always_xy=True)
    to_wgs84 = Transformer.from_crs(project.working_crs, "EPSG:4326", always_xy=True)
    line = transform(to_metric.transform, raw_line)
    snapshot = revision.input_snapshot
    selection = resolve_version_selection(
        session,
        project=project,
        selected_version_ids=[
            str(value) for value in snapshot.get("selected_dataset_version_ids", [])
        ],
        scenario_revision_id=revision.id,
        scenario_input_hash=revision.input_hash,
    )
    model = load_materialized_model(
        session,
        version_ids=tuple(version.id for version in selection.versions),
        working_crs=project.working_crs,
    )
    findings: list[ConstraintFinding] = []
    for item in (*selection.findings, *model.findings):
        findings.append(
            ConstraintFinding(
                code=item.code,
                severity="error" if item.severity == "error" else "warning",
                check_status="failed" if item.blocking else "insufficient_data",
                message=item.message,
                rule_id="model-context",
                feature_ids=item.source_ids,
            )
        )
    profile = None
    raw_profile_id = snapshot.get("rule_profile_version_id")
    if raw_profile_id is not None:
        stored_profile = session.scalar(
            select(RuleProfileVersion)
            .join(RuleProfile, RuleProfile.id == RuleProfileVersion.profile_id)
            .where(
                RuleProfileVersion.id == UUID(str(raw_profile_id)),
                RuleProfile.project_id == project.id,
                RuleProfile.workspace_id == project.workspace_id,
            )
        )
        if stored_profile is None:
            raise ValueError("selected rule profile version is missing")
        profile = DomainRuleProfileVersion.from_dict(stored_profile.definition)

    scenario_features = _scenario_features(snapshot, to_metric)
    candidate_decisions: tuple[CandidateDecision, ...] = ()
    planning_date = (
        None
        if snapshot.get("planning_date") is None
        else date.fromisoformat(str(snapshot["planning_date"]))
    )
    selected_candidate_ids = tuple(
        str(item) for item in snapshot.get("connection_candidate_ids", [])
    )
    if selected_candidate_ids and planning_date is not None:
        candidate_decisions = screen_materialized_candidates(
            model,
            planning_date=planning_date,
            requested_load_kw=(
                None
                if snapshot.get("requested_load_kw") is None
                else float(snapshot["requested_load_kw"])
            ),
            geometry_only=snapshot.get("requested_load_kw") is None,
            limit=int(snapshot.get("candidate_limit", 5)),
            mode=str(snapshot.get("validation_mode", "strict")),
            allowed_assumptions=frozenset(
                str(item) for item in snapshot.get("explicit_assumptions", [])
            ),
            selected_source_ids=selected_candidate_ids,
        ).selected

    route_line = line
    entry_gate = None
    entry_connector = None
    precision_m = 0.01 if profile is None else float(
        profile.geometry_tolerances.get("precision_m", 0.01)
    )
    if snapshot.get("input_mode") == "building_to_network":
        if not candidate_decisions:
            findings.append(
                _endpoint_finding(
                    "MANUAL_ROUTE_CANDIDATE_INVALID",
                    "The edited route has no eligible explicit connection candidate.",
                    line,
                )
            )
        else:
            resolution = resolve_building_route_endpoints(
                model,
                snapshot=snapshot,
                candidate_source_id=candidate_decisions[0].source_id,
                working_crs=project.working_crs,
            )
            if resolution.endpoints is None:
                findings.extend(
                    ConstraintFinding(
                        code=item.code,
                        severity="error",
                        check_status="failed",
                        message=item.message,
                        rule_id="manual-endpoints",
                        feature_ids=item.source_ids,
                    )
                    for item in resolution.findings
                )
            else:
                endpoints = resolution.endpoints
                if Point(line.coords[0]).distance(endpoints.start) > precision_m:
                    findings.append(
                        _endpoint_finding(
                            "MANUAL_ROUTE_START_MISMATCH",
                            "The edited route must start at the selected explicit network node.",
                            Point(line.coords[0]),
                        )
                    )
                if Point(line.coords[-1]).distance(endpoints.entry_gate.entry_point) > precision_m:
                    findings.append(
                        _endpoint_finding(
                            "MANUAL_ROUTE_ENTRY_MISMATCH",
                            "The edited route must end at the named entry point.",
                            Point(line.coords[-1]),
                        )
                    )
                if len(line.coords) < 3:
                    findings.append(
                        _endpoint_finding(
                            "MANUAL_ROUTE_CONNECTOR_MISSING",
                            "The edited building route must retain a separate gate connector.",
                            line,
                        )
                    )
                else:
                    route_line = LineString(line.coords[:-1])
                    entry_connector = LineString(line.coords[-2:])
                    entry_gate = endpoints.entry_gate
    else:
        expected_start = _metric_point(snapshot["entry_point_wgs84"], to_metric)
        expected_goal = _metric_point(snapshot["goal_point_wgs84"], to_metric)
        if Point(line.coords[0]).distance(expected_start) > precision_m:
            findings.append(
                _endpoint_finding(
                    "MANUAL_ROUTE_START_MISMATCH",
                    "The edited route must retain the scenario start point.",
                    Point(line.coords[0]),
                )
            )
        if Point(line.coords[-1]).distance(expected_goal) > precision_m:
            findings.append(
                _endpoint_finding(
                    "MANUAL_ROUTE_GOAL_MISMATCH",
                    "The edited route must retain the scenario goal point.",
                    Point(line.coords[-1]),
                )
            )

    report = None
    if profile is not None:
        report = RouteConstraintValidator().validate(
            centerline=route_line,
            corridor_width_m=float(snapshot["corridor_width_m"]),
            profile=profile,
            features=(*model.features, *scenario_features),
            portals=model.portals,
            coverage=model.coverage,
            mode=(
                "exploratory"
                if snapshot.get("validation_mode") == "exploratory"
                else "strict"
            ),
            allowed_assumptions=frozenset(
                str(item) for item in snapshot.get("explicit_assumptions", [])
            ),
            entry_gate=entry_gate,
            entry_connector=entry_connector,
            candidate_decisions=candidate_decisions,
            planning_date=planning_date,
        )
        findings.extend(report.findings)
    else:
        corridor = line.buffer(float(snapshot["corridor_width_m"]) / 2)
        for feature in scenario_features:
            conflict = corridor.intersection(feature.geometry)
            if not conflict.is_empty:
                findings.append(
                    ConstraintFinding(
                        code="HARD_EXCLUSION_INTERSECTION",
                        severity="error",
                        check_status="failed",
                        message="The edited corridor intersects a scenario exclusion.",
                        rule_id="scenario-forbidden-zone",
                        feature_ids=(feature.id,),
                        geometry=conflict,
                    )
                )

    valid = not any(item.check_status == "failed" for item in findings)
    insufficient = any(item.check_status == "insufficient_data" for item in findings)
    status = "valid_in_model" if valid else "invalid_in_model"
    if valid and insufficient:
        status = "insufficient_data"
    return {
        "scenario_revision_id": revision.id,
        "geometry_status": status,
        "validation_report": {
            "valid": valid,
            "findings": [_serialize_finding(item, to_wgs84) for item in findings],
            "unverified_length_m": 0 if report is None else report.unverified_length_m,
            "crossing_events": []
            if report is None
            else [
                {
                    "event_type": event.event_type,
                    "rule_id": event.rule_id,
                    "feature_id": event.feature_id,
                    "portal_id": event.portal_id,
                    "quantity": event.quantity,
                    "geometry_wgs84": mapping(
                        transform(to_wgs84.transform, event.geometry)
                    ),
                }
                for event in report.crossing_events
            ],
        },
    }


def _scenario_features(
    snapshot: dict[str, Any], transformer: Transformer
) -> tuple[MetricFeature, ...]:
    features: list[MetricFeature] = []
    for index, rectangle in enumerate(snapshot.get("forbidden_rectangles_wgs84", [])):
        geometry = transform(transformer.transform, box(*map(float, rectangle)))
        features.append(
            MetricFeature(
                f"scenario-rectangle-{index}",
                "forbidden_zone",
                geometry,
                {},
                f"scenario:forbidden_rectangles_wgs84:{index}",
            )
        )
    for index, raw_geometry in enumerate(snapshot.get("user_forbidden_zones", [])):
        geometry = transform(transformer.transform, shape(raw_geometry))
        features.append(
            MetricFeature(
                f"user-forbidden-zone-{index}",
                "forbidden_zone",
                geometry,
                {},
                f"scenario:user_forbidden_zones:{index}",
            )
        )
    return tuple(features)


def _metric_point(value: object, transformer: Transformer) -> Point:
    if not isinstance(value, list | tuple) or len(value) != 2:
        raise ValueError("scenario endpoint is invalid")
    return Point(transformer.transform(float(value[0]), float(value[1])))


def _endpoint_finding(
    code: str, message: str, geometry: BaseGeometry
) -> ConstraintFinding:
    return ConstraintFinding(
        code=code,
        severity="error",
        check_status="failed",
        message=message,
        rule_id="manual-endpoints",
        geometry=geometry,
    )


def _serialize_finding(
    finding: ConstraintFinding, transformer: Transformer
) -> dict[str, Any]:
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
        "geometry_wgs84": None
        if finding.geometry is None
        else mapping(transform(transformer.transform, finding.geometry)),
    }
