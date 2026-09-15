from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import date
from typing import Literal

from shapely.geometry import LineString, Point

Circuit = Literal["supply", "return", "paired_corridor", "unknown"]


@dataclass(frozen=True)
class NetworkNode:
    source_id: str
    geometry: Point
    circuit: Circuit = "unknown"
    source_network_id: str = "unknown"
    lifecycle_status: str = "existing"
    availability_date: date | None = None


@dataclass(frozen=True)
class NetworkEdge:
    source_id: str
    from_node_id: str
    to_node_id: str
    geometry: LineString
    circuit: Circuit = "unknown"
    source_network_id: str = "unknown"


@dataclass(frozen=True)
class TopologyFinding:
    code: str
    severity: Literal["info", "warning", "error"]
    source_ids: tuple[str, ...]
    message: str


@dataclass(frozen=True)
class TopologyReport:
    findings: tuple[TopologyFinding, ...]
    component_count: int
    cycle_count: int

    @property
    def valid(self) -> bool:
        return not any(finding.severity == "error" for finding in self.findings)


@dataclass
class NetworkGraph:
    nodes: dict[str, NetworkNode]
    edges: dict[str, NetworkEdge]
    adjacency: dict[str, list[str]] = field(default_factory=dict)

    @classmethod
    def build(
        cls,
        nodes: list[NetworkNode],
        edges: list[NetworkEdge],
        *,
        endpoint_tolerance_m: float = 0.25,
    ) -> tuple[NetworkGraph, TopologyReport]:
        if endpoint_tolerance_m < 0:
            raise ValueError("endpoint_tolerance_m must be non-negative")
        findings: list[TopologyFinding] = []
        node_index: dict[str, NetworkNode] = {}
        for node in nodes:
            if node.source_id in node_index:
                findings.append(
                    TopologyFinding(
                        "DUPLICATE_NODE_ID",
                        "error",
                        (node.source_id,),
                        "Node identity is duplicated; no implicit winner was selected.",
                    )
                )
            else:
                node_index[node.source_id] = node
        edge_index: dict[str, NetworkEdge] = {}
        seen_edge_ids: set[str] = set()
        adjacency: dict[str, list[str]] = defaultdict(list)
        valid_pairs: list[tuple[str, str]] = []
        valid_edges: list[NetworkEdge] = []
        for edge in edges:
            if edge.source_id in seen_edge_ids:
                findings.append(
                    TopologyFinding(
                        "DUPLICATE_EDGE_ID",
                        "error",
                        (edge.source_id,),
                        "Edge identity is duplicated; no implicit winner was selected.",
                    )
                )
                continue
            seen_edge_ids.add(edge.source_id)
            missing = tuple(
                node_id
                for node_id in (edge.from_node_id, edge.to_node_id)
                if node_id not in node_index
            )
            if missing:
                findings.append(
                    TopologyFinding(
                        "EDGE_ENDPOINT_NOT_FOUND",
                        "error",
                        (edge.source_id, *missing),
                        "Edge references an unknown explicit endpoint.",
                    )
                )
                continue
            if edge.from_node_id == edge.to_node_id or edge.geometry.length <= 0:
                findings.append(
                    TopologyFinding(
                        "EDGE_ZERO_LENGTH",
                        "error",
                        (edge.source_id,),
                        "Edge has equal endpoints or zero geometry length.",
                    )
                )
                continue
            from_node = node_index[edge.from_node_id]
            to_node = node_index[edge.to_node_id]
            edge_start = Point(edge.geometry.coords[0])
            edge_end = Point(edge.geometry.coords[-1])
            if (
                edge_start.distance(from_node.geometry) > endpoint_tolerance_m
                or edge_end.distance(to_node.geometry) > endpoint_tolerance_m
            ):
                findings.append(
                    TopologyFinding(
                        "EDGE_GEOMETRY_ENDPOINT_MISMATCH",
                        "error",
                        (edge.source_id, edge.from_node_id, edge.to_node_id),
                        "Edge geometry endpoints do not match explicit nodes within tolerance.",
                    )
                )
                continue
            circuit_valid = True
            for node in (from_node, to_node):
                if (
                    edge.circuit != "unknown"
                    and node.circuit != "unknown"
                    and edge.circuit != node.circuit
                ):
                    circuit_valid = False
                    findings.append(
                        TopologyFinding(
                            "CIRCUIT_MISMATCH",
                            "error",
                            (edge.source_id, node.source_id),
                            "Edge and explicit endpoint belong to different circuits.",
                        )
                    )
            if not circuit_valid:
                continue
            adjacency[edge.from_node_id].append(edge.source_id)
            adjacency[edge.to_node_id].append(edge.source_id)
            edge_index[edge.source_id] = edge
            valid_pairs.append((edge.from_node_id, edge.to_node_id))
            valid_edges.append(edge)

        for index, left in enumerate(valid_edges):
            left_nodes = {left.from_node_id, left.to_node_id}
            for right in valid_edges[index + 1 :]:
                if left_nodes & {right.from_node_id, right.to_node_id}:
                    continue
                if left.geometry.intersects(right.geometry):
                    findings.append(
                        TopologyFinding(
                            "AMBIGUOUS_XY_INTERSECTION",
                            "warning",
                            (left.source_id, right.source_id),
                            "Edges intersect in XY without sharing an explicit node.",
                        )
                    )

        for node_id in node_index:
            adjacency.setdefault(node_id, [])
            if not adjacency[node_id]:
                findings.append(
                    TopologyFinding(
                        "ISOLATED_NODE",
                        "warning",
                        (node_id,),
                        "Node is not referenced by a valid explicit edge.",
                    )
                )

        component_count = _component_count(node_index, valid_pairs)
        cycle_count = max(0, len(valid_pairs) - len(node_index) + component_count)
        graph = cls(
            nodes=node_index,
            edges=edge_index,
            adjacency={key: sorted(value) for key, value in adjacency.items()},
        )
        return graph, TopologyReport(tuple(findings), component_count, cycle_count)

    def incident_edges(self, node_id: str) -> tuple[NetworkEdge, ...]:
        return tuple(self.edges[edge_id] for edge_id in self.adjacency.get(node_id, []))


def _component_count(nodes: dict[str, NetworkNode], pairs: list[tuple[str, str]]) -> int:
    neighbor_nodes: dict[str, list[str]] = defaultdict(list)
    for left, right in pairs:
        neighbor_nodes[left].append(right)
        neighbor_nodes[right].append(left)
    unseen = set(nodes)
    components = 0
    while unseen:
        components += 1
        queue = deque([unseen.pop()])
        while queue:
            current = queue.popleft()
            for neighbor in neighbor_nodes[current]:
                if neighbor in unseen:
                    unseen.remove(neighbor)
                    queue.append(neighbor)
    return components


@dataclass(frozen=True)
class ConnectionCandidate:
    source_id: str
    network_node_id: str | None
    permission: Literal["allowed", "forbidden", "unknown"]
    available_capacity_kw: float | None = None
    capacity_basis: Literal["net_available", "gross_with_separate_reservations", "unknown"] = (
        "unknown"
    )
    reserved_capacity_kw: float | None = None
    valid_from: date | None = None
    valid_to: date | None = None
    connector_length_m: float = 0


@dataclass(frozen=True)
class CandidateDecision:
    source_id: str
    eligible: bool
    check_status: Literal["passed", "failed", "insufficient_data"]
    reasons: tuple[str, ...]
    effective_capacity_kw: float | None


@dataclass(frozen=True)
class CandidateScreeningReport:
    selected: tuple[CandidateDecision, ...]
    rejected: tuple[CandidateDecision, ...]
    evaluated_count: int
    limit: int


def screen_candidate(
    candidate: ConnectionCandidate,
    *,
    planning_date: date,
    requested_load_kw: float | None,
    geometry_only: bool,
    mode: Literal["strict", "exploratory"] = "strict",
    allowed_assumptions: frozenset[str] = frozenset(),
) -> CandidateDecision:
    reasons: list[str] = []
    if candidate.network_node_id is None:
        return CandidateDecision(
            candidate.source_id,
            False,
            "failed",
            ("EXPLICIT_CONNECTION_NODE_REQUIRED",),
            None,
        )
    if candidate.permission == "forbidden":
        return CandidateDecision(
            candidate.source_id,
            False,
            "failed",
            ("PERMISSION_FORBIDDEN",),
            None,
        )
    if candidate.permission == "unknown":
        reasons.append("PERMISSION_UNKNOWN")
        assumption_id = f"candidate-permission:{candidate.source_id}"
        if mode != "exploratory" or assumption_id not in allowed_assumptions:
            return CandidateDecision(
                candidate.source_id,
                False,
                "failed",
                tuple(reasons),
                None,
            )
    if candidate.valid_from is not None and planning_date < candidate.valid_from:
        reasons.append("NOT_YET_AVAILABLE")
    if candidate.valid_to is not None and planning_date > candidate.valid_to:
        reasons.append("NO_LONGER_AVAILABLE")

    effective_capacity = candidate.available_capacity_kw
    if (
        effective_capacity is not None
        and candidate.capacity_basis == "gross_with_separate_reservations"
        and candidate.reserved_capacity_kw is not None
    ):
        effective_capacity -= candidate.reserved_capacity_kw
    if "NOT_YET_AVAILABLE" in reasons or "NO_LONGER_AVAILABLE" in reasons:
        return CandidateDecision(
            candidate.source_id,
            False,
            "failed",
            tuple(reasons),
            effective_capacity,
        )
    if requested_load_kw is not None:
        if effective_capacity is None or candidate.capacity_basis == "unknown":
            reasons.append("CAPACITY_NOT_PROVIDED")
        elif requested_load_kw > effective_capacity:
            reasons.append("CAPACITY_INSUFFICIENT")
            return CandidateDecision(
                candidate.source_id, False, "failed", tuple(reasons), effective_capacity
            )
    elif not geometry_only:
        reasons.append("REQUESTED_LOAD_NOT_PROVIDED")
    if reasons:
        return CandidateDecision(
            candidate.source_id,
            geometry_only and not any(reason.startswith("NOT_") for reason in reasons),
            "insufficient_data",
            tuple(reasons),
            effective_capacity,
        )
    return CandidateDecision(candidate.source_id, True, "passed", (), effective_capacity)


def screen_candidates(
    candidates: tuple[ConnectionCandidate, ...],
    *,
    planning_date: date,
    requested_load_kw: float | None,
    geometry_only: bool,
    limit: int,
    mode: Literal["strict", "exploratory"] = "strict",
    allowed_assumptions: frozenset[str] = frozenset(),
) -> CandidateScreeningReport:
    if limit <= 0:
        raise ValueError("candidate limit must be positive")
    if any(candidate.connector_length_m < 0 for candidate in candidates):
        raise ValueError("candidate connector length must be non-negative")
    ordered = sorted(candidates, key=lambda item: (item.connector_length_m, item.source_id))
    decisions = tuple(
        screen_candidate(
            candidate,
            planning_date=planning_date,
            requested_load_kw=requested_load_kw,
            geometry_only=geometry_only,
            mode=mode,
            allowed_assumptions=allowed_assumptions,
        )
        for candidate in ordered
    )
    eligible = tuple(decision for decision in decisions if decision.eligible)
    return CandidateScreeningReport(
        selected=eligible[:limit],
        rejected=tuple(decision for decision in decisions if not decision.eligible),
        evaluated_count=len(decisions),
        limit=limit,
    )
