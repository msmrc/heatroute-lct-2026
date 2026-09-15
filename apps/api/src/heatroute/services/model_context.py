from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from typing import Any, Literal, cast
from uuid import UUID

from pyproj import CRS, Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import nearest_points, transform
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from heatroute.domain.constraints import CrossingPortal, EntryGate, MetricFeature
from heatroute.domain.network import (
    CandidateScreeningReport,
    Circuit,
    ConnectionCandidate,
    NetworkEdge,
    NetworkGraph,
    NetworkNode,
    TopologyReport,
    screen_candidates,
)
from heatroute.models import CanonicalFeature, Dataset, DatasetVersion, Project
from heatroute.services.rule_profiles import definition_hash


@dataclass(frozen=True)
class ModelContextFinding:
    code: str
    severity: str
    blocking: bool
    field: str | None
    message: str
    source_ids: tuple[str, ...] = ()

    def as_preflight(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "severity": self.severity,
            "blocking": self.blocking,
            "field": self.field,
            "message": self.message,
        }


@dataclass(frozen=True)
class VersionSelection:
    versions_snapshot: dict[str, Any]
    versions: tuple[DatasetVersion, ...]
    findings: tuple[ModelContextFinding, ...]


@dataclass(frozen=True)
class CanonicalRecord:
    id: UUID
    dataset_version_id: UUID
    source_id: str
    kind: str
    lifecycle_status: str
    attributes: dict[str, Any]
    geometry_wgs84: dict[str, Any]


@dataclass(frozen=True)
class MaterializedModel:
    network: NetworkGraph
    topology: TopologyReport
    features: tuple[MetricFeature, ...]
    portals: tuple[CrossingPortal, ...]
    coverage: tuple[MetricFeature, ...]
    entry_gates: tuple[EntryGate, ...]
    candidates: tuple[ConnectionCandidate, ...]
    findings: tuple[ModelContextFinding, ...]


@dataclass(frozen=True)
class BuildingRouteEndpoints:
    candidate_source_id: str
    network_node_id: str
    start: Point
    gate_approach: Point
    entry_gate: EntryGate
    entry_connector: LineString


@dataclass(frozen=True)
class EndpointResolution:
    endpoints: BuildingRouteEndpoints | None
    findings: tuple[ModelContextFinding, ...]


def resolve_version_selection(
    session: Session,
    *,
    project: Project,
    selected_version_ids: list[str],
    scenario_revision_id: UUID,
    scenario_input_hash: str,
) -> VersionSelection:
    requested = tuple(UUID(value) for value in selected_version_ids)
    findings: list[ModelContextFinding] = []
    if len(set(requested)) != len(requested):
        findings.append(
            ModelContextFinding(
                "DUPLICATE_DATASET_VERSION_SELECTION",
                "error",
                True,
                "selected_dataset_version_ids",
                "A dataset version can be selected only once.",
            )
        )
    rows = session.execute(
        select(DatasetVersion, Dataset)
        .join(Dataset, Dataset.id == DatasetVersion.dataset_id)
        .where(
            DatasetVersion.id.in_(requested),
            Dataset.project_id == project.id,
            Dataset.workspace_id == project.workspace_id,
        )
    ).all()
    by_id = {version.id: (version, dataset) for version, dataset in rows}
    versions: list[DatasetVersion] = []
    datasets_seen: set[UUID] = set()
    dataset_snapshots: list[dict[str, Any]] = []
    for version_id in requested:
        row = by_id.get(version_id)
        if row is None:
            findings.append(
                ModelContextFinding(
                    "DATASET_VERSION_NOT_FOUND",
                    "error",
                    True,
                    "selected_dataset_version_ids",
                    "A selected dataset version is not available in this project.",
                    (str(version_id),),
                )
            )
            continue
        version, dataset = row
        if dataset.id in datasets_seen:
            findings.append(
                ModelContextFinding(
                    "MULTIPLE_VERSIONS_OF_DATASET_SELECTED",
                    "error",
                    True,
                    "selected_dataset_version_ids",
                    "Only one immutable version of a dataset may be selected.",
                    (str(dataset.id), str(version.id)),
                )
            )
        datasets_seen.add(dataset.id)
        if version.status != "published":
            findings.append(
                ModelContextFinding(
                    "DATASET_VERSION_NOT_PUBLISHED",
                    "error",
                    True,
                    "selected_dataset_version_ids",
                    "Selected dataset versions must be published.",
                    (str(version.id),),
                )
            )
        if version.working_crs != project.working_crs:
            findings.append(
                ModelContextFinding(
                    "DATASET_WORKING_CRS_MISMATCH",
                    "error",
                    True,
                    "selected_dataset_version_ids",
                    "Dataset and project working CRS snapshots do not match.",
                    (str(version.id),),
                )
            )
        item = {
            "id": str(version.id),
            "dataset_id": str(dataset.id),
            "version": version.version,
            "status": version.status,
            "raw_hashes": version.raw_hashes,
            "working_crs": version.working_crs,
            "transform_hash": version.transform_hash,
            "mapping_profile_id": (
                None if version.mapping_profile_id is None else str(version.mapping_profile_id)
            ),
            "mapping_profile_version": version.mapping_profile_version,
            "published_at": (
                None if version.published_at is None else version.published_at.isoformat()
            ),
        }
        item["selection_hash"] = definition_hash(item)
        dataset_snapshots.append(item)
        versions.append(version)
    if project.source_mode != "synthetic" and not requested:
        findings.append(
            ModelContextFinding(
                "DATASET_SELECTION_EMPTY",
                "error",
                True,
                "selected_dataset_version_ids",
                "A non-synthetic project requires explicit published dataset versions.",
            )
        )
    snapshot: dict[str, Any] = {
        "schema_version": "1.0",
        "scenario_revision_id": str(scenario_revision_id),
        "scenario_input_hash": scenario_input_hash,
        "project": {
            "id": str(project.id),
            "revision": project.current_revision,
            "working_crs": project.working_crs,
            "source_mode": project.source_mode,
        },
        "datasets": dataset_snapshots,
    }
    snapshot["manifest_hash"] = definition_hash(snapshot)
    return VersionSelection(snapshot, tuple(versions), tuple(findings))


def load_materialized_model(
    session: Session,
    *,
    version_ids: tuple[UUID, ...],
    working_crs: str,
) -> MaterializedModel:
    rows = session.execute(
        select(CanonicalFeature, func.ST_AsGeoJSON(CanonicalFeature.geometry_wgs84))
        .where(CanonicalFeature.dataset_version_id.in_(version_ids))
        .order_by(CanonicalFeature.dataset_version_id, CanonicalFeature.kind, CanonicalFeature.id)
    ).all()
    records = tuple(
        CanonicalRecord(
            id=feature.id,
            dataset_version_id=feature.dataset_version_id,
            source_id=feature.source_id,
            kind=feature.kind,
            lifecycle_status=feature.lifecycle_status,
            attributes=feature.attributes,
            geometry_wgs84=json.loads(geometry_json),
        )
        for feature, geometry_json in rows
    )
    return materialize_records(records, working_crs=working_crs)


def materialize_records(
    records: tuple[CanonicalRecord, ...], *, working_crs: str
) -> MaterializedModel:
    crs = CRS.from_user_input(working_crs)
    if not crs.is_projected:
        raise ValueError("working CRS must be projected")
    transformer = Transformer.from_crs("EPSG:4326", crs, always_xy=True)
    nodes: list[NetworkNode] = []
    edges: list[NetworkEdge] = []
    features: list[MetricFeature] = []
    portals: list[CrossingPortal] = []
    coverage: list[MetricFeature] = []
    gates: list[EntryGate] = []
    candidates: list[ConnectionCandidate] = []
    findings: list[ModelContextFinding] = []
    for record in records:
        source_ref = (
            f"dataset-version:{record.dataset_version_id}:canonical-feature:{record.id}"
        )
        try:
            geometry = transform(transformer.transform, shape(record.geometry_wgs84))
            attributes = record.attributes
            if record.kind == "network_node":
                if not isinstance(geometry, Point):
                    raise ValueError("network node geometry must be Point")
                nodes.append(
                    NetworkNode(
                        record.source_id,
                        geometry,
                        circuit=_circuit(attributes.get("circuit")),
                        source_network_id=str(attributes.get("source_network_id", "unknown")),
                        lifecycle_status=record.lifecycle_status,
                        availability_date=_date(attributes.get("availability_date")),
                    )
                )
                features.append(
                    MetricFeature(
                        record.source_id,
                        record.kind,
                        geometry,
                        attributes,
                        source_ref,
                    )
                )
            elif record.kind == "network_edge":
                if not isinstance(geometry, LineString):
                    raise ValueError("network edge geometry must be LineString")
                edges.append(
                    NetworkEdge(
                        record.source_id,
                        str(attributes["from_node_id"]),
                        str(attributes["to_node_id"]),
                        geometry,
                        circuit=_circuit(attributes.get("circuit")),
                        source_network_id=str(attributes.get("source_network_id", "unknown")),
                    )
                )
                features.append(
                    MetricFeature(
                        record.source_id,
                        record.kind,
                        geometry,
                        attributes,
                        source_ref,
                    )
                )
            elif record.kind == "crossing_portal":
                if not isinstance(geometry, LineString):
                    raise ValueError("crossing portal geometry must be LineString")
                portals.append(
                    CrossingPortal(
                        record.source_id,
                        geometry,
                        width_m=float(attributes["width_m"]),
                        applies_to_feature_ids=frozenset(
                            str(item) for item in attributes.get("applies_to_feature_ids", [])
                        ),
                        approval_status=str(attributes.get("approval_status", "unknown")),
                        method=str(attributes.get("method", "special_crossing")),
                        quantity_model_key=(
                            None
                            if attributes.get("quantity_model_key") is None
                            else str(attributes["quantity_model_key"])
                        ),
                    )
                )
            elif record.kind == "entry_gate":
                entry_wgs84 = attributes.get(
                    "entry_point", attributes.get("entry_point_wgs84")
                )
                if not isinstance(entry_wgs84, list | tuple) or len(entry_wgs84) != 2:
                    raise ValueError("entry gate point must contain WGS84 longitude/latitude")
                entry_x, entry_y = transformer.transform(
                    float(entry_wgs84[0]), float(entry_wgs84[1])
                )
                gates.append(
                    EntryGate(
                        record.source_id,
                        str(attributes["target_building_id"]),
                        geometry,
                        Point(entry_x, entry_y),
                        float(attributes["max_connector_length_m"]),
                    )
                )
            elif record.kind == "connection_candidate":
                candidates.append(
                    ConnectionCandidate(
                        record.source_id,
                        (
                            None
                            if attributes.get("network_node_id") is None
                            else str(attributes["network_node_id"])
                        ),
                        permission=_permission(attributes.get("permission")),
                        available_capacity_kw=_float(attributes.get("available_capacity_kw")),
                        capacity_basis=_capacity_basis(attributes.get("capacity_basis")),
                        reserved_capacity_kw=_float(attributes.get("reserved_capacity_kw")),
                        valid_from=_date(attributes.get("valid_from")),
                        valid_to=_date(attributes.get("valid_to")),
                        connector_length_m=float(
                            attributes.get("connector_length_m", 0)
                        ),
                    )
                )
            else:
                metric_feature = MetricFeature(
                    record.source_id,
                    record.kind,
                    geometry,
                    attributes,
                    source_ref,
                )
                if record.kind == "coverage_area":
                    coverage.append(metric_feature)
                else:
                    features.append(metric_feature)
        except (KeyError, TypeError, ValueError) as error:
            findings.append(
                ModelContextFinding(
                    "CANONICAL_FEATURE_MATERIALIZATION_FAILED",
                    "error",
                    True,
                    None,
                    str(error),
                    (record.source_id, source_ref),
                )
            )
    network, topology = NetworkGraph.build(nodes, edges)
    return MaterializedModel(
        network=network,
        topology=topology,
        features=tuple(features),
        portals=tuple(portals),
        coverage=tuple(coverage),
        entry_gates=tuple(gates),
        candidates=tuple(candidates),
        findings=tuple(findings),
    )


def screen_materialized_candidates(
    model: MaterializedModel,
    *,
    planning_date: date,
    requested_load_kw: float | None,
    geometry_only: bool,
    limit: int,
    mode: str,
    allowed_assumptions: frozenset[str],
    selected_source_ids: tuple[str, ...] = (),
) -> CandidateScreeningReport:
    normalized_mode: Literal["strict", "exploratory"] = (
        "exploratory" if mode == "exploratory" else "strict"
    )
    selected = (
        model.candidates
        if not selected_source_ids
        else tuple(
            candidate
            for candidate in model.candidates
            if candidate.source_id in selected_source_ids
        )
    )
    return screen_candidates(
        selected,
        planning_date=planning_date,
        requested_load_kw=requested_load_kw,
        geometry_only=geometry_only,
        limit=limit,
        mode=normalized_mode,
        allowed_assumptions=allowed_assumptions,
    )


def resolve_building_route_endpoints(
    model: MaterializedModel,
    *,
    snapshot: dict[str, Any],
    candidate_source_id: str,
    working_crs: str,
    tolerance_m: float = 0.25,
) -> EndpointResolution:
    findings: list[ModelContextFinding] = []
    candidate = next(
        (
            item
            for item in model.candidates
            if item.source_id == candidate_source_id
        ),
        None,
    )
    node = (
        None
        if candidate is None or candidate.network_node_id is None
        else model.network.nodes.get(candidate.network_node_id)
    )
    if node is None:
        findings.append(
            ModelContextFinding(
                "CANDIDATE_NETWORK_NODE_NOT_FOUND",
                "error",
                True,
                "connection_candidate_ids",
                "The selected candidate does not resolve to an explicit network node.",
                (candidate_source_id,),
            )
        )
    gate_id = str(snapshot.get("entry_gate_id") or "")
    gate = next((item for item in model.entry_gates if item.id == gate_id), None)
    if gate is None:
        findings.append(
            ModelContextFinding(
                "ENTRY_GATE_NOT_FOUND",
                "error",
                True,
                "entry_gate_id",
                "The named entry gate was not found in the selected model.",
                (gate_id,),
            )
        )
    target_id = str(snapshot.get("target_building_id") or "")
    target_exists = any(
        feature.id == target_id and feature.kind == "building"
        for feature in model.features
    )
    if not target_exists:
        findings.append(
            ModelContextFinding(
                "TARGET_BUILDING_NOT_FOUND",
                "error",
                True,
                "target_building_id",
                "The target building was not found in the selected model.",
                (target_id,),
            )
        )
    if gate is not None and gate.target_building_id != target_id:
        findings.append(
            ModelContextFinding(
                "ENTRY_GATE_TARGET_MISMATCH",
                "error",
                True,
                "entry_gate_id",
                "The named entry gate belongs to another target building.",
                (gate.id, gate.target_building_id, target_id),
            )
        )
    raw_entry = snapshot.get("entry_point_wgs84")
    if gate is not None and isinstance(raw_entry, list | tuple) and len(raw_entry) == 2:
        transformer = Transformer.from_crs("EPSG:4326", working_crs, always_xy=True)
        requested_entry = Point(
            transformer.transform(float(raw_entry[0]), float(raw_entry[1]))
        )
        if requested_entry.distance(gate.entry_point) > tolerance_m:
            findings.append(
                ModelContextFinding(
                    "ENTRY_POINT_GATE_MISMATCH",
                    "error",
                    True,
                    "entry_point_wgs84",
                    "The requested entry point does not match the named gate entry point.",
                    (gate.id,),
                )
            )
    if findings or node is None or gate is None or candidate is None:
        return EndpointResolution(None, tuple(findings))
    approach = nearest_points(gate.geometry.boundary, node.geometry)[0]
    connector = LineString((approach, gate.entry_point))
    if connector.length > gate.max_connector_length_m or not gate.geometry.covers(connector):
        findings.append(
            ModelContextFinding(
                "ENTRY_CONNECTOR_INVALID",
                "error",
                True,
                "entry_gate_id",
                "The bounded connector cannot reach the gate entry point inside its footprint.",
                (gate.id, target_id),
            )
        )
        return EndpointResolution(None, tuple(findings))
    return EndpointResolution(
        BuildingRouteEndpoints(
            candidate_source_id=candidate.source_id,
            network_node_id=node.source_id,
            start=node.geometry,
            gate_approach=Point(approach),
            entry_gate=gate,
            entry_connector=connector,
        ),
        (),
    )


def _circuit(value: object) -> Circuit:
    normalized = str(value or "unknown")
    if normalized not in {"supply", "return", "paired_corridor", "unknown"}:
        raise ValueError(f"invalid circuit: {normalized}")
    return cast(Circuit, normalized)


def _date(value: object) -> date | None:
    return None if value is None else date.fromisoformat(str(value))


def _float(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, dict):
        value = value.get("value")
    return None if value is None else float(cast(Any, value))


def _permission(value: object) -> Literal["allowed", "forbidden", "unknown"]:
    normalized = str(value or "unknown")
    if normalized not in {"allowed", "forbidden", "unknown"}:
        raise ValueError(f"invalid connection permission: {normalized}")
    return cast(Literal["allowed", "forbidden", "unknown"], normalized)


def _capacity_basis(
    value: object,
) -> Literal["net_available", "gross_with_separate_reservations", "unknown"]:
    normalized = str(value or "unknown")
    if normalized not in {
        "net_available",
        "gross_with_separate_reservations",
        "unknown",
    }:
        raise ValueError(f"invalid capacity basis: {normalized}")
    return cast(
        Literal["net_available", "gross_with_separate_reservations", "unknown"],
        normalized,
    )
