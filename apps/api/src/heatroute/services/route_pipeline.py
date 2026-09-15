from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import date
from decimal import Decimal
from importlib.metadata import version
from typing import Any, cast

from pyproj import CRS, Transformer
from shapely.geometry import LineString, shape
from shapely.geometry.base import BaseGeometry
from shapely.ops import transform

from heatroute.domain.constraints import CrossingEvent, CrossingPortal, EntryGate, MetricFeature
from heatroute.domain.construction import construction_method as get_construction_method
from heatroute.domain.costing import (
    CostCatalogDefinition,
    CostReport,
    build_quantity_report,
    compare_costs,
    price_quantities,
)
from heatroute.domain.network import CandidateDecision
from heatroute.domain.routing.demo import DemoComputation, compute_demo_route
from heatroute.domain.vertical import (
    VerticalCrossing,
    VerticalProfilePoint,
    validate_vertical_profile,
)


@dataclass(frozen=True)
class RouteEndpoint:
    candidate_id: str | None
    metric_start: tuple[float, float] | None
    metric_goal: tuple[float, float] | None
    entry_gate: EntryGate | None = None
    entry_connector: LineString | None = None
    candidate_decisions: tuple[CandidateDecision, ...] = ()


@dataclass(frozen=True)
class RoutePipelineResult:
    outcome: str
    search_completion: str
    alternatives: tuple[dict[str, Any], ...]
    statistics: dict[str, Any]
    error_code: str | None
    optimality_scope: str
    runtime_library_versions: dict[str, str]
    findings_summary: dict[str, int]


def execute_route_pipeline(
    snapshot: dict[str, Any],
    algorithm: str,
    *,
    working_crs: str,
    endpoints: tuple[RouteEndpoint, ...],
    rule_profile_definition: dict[str, Any] | None = None,
    cost_catalog_definition: dict[str, Any] | None = None,
    constraint_features: tuple[MetricFeature, ...] = (),
    constraint_portals: tuple[CrossingPortal, ...] = (),
    constraint_coverage: tuple[MetricFeature, ...] = (),
    planning_date: date | None = None,
    should_cancel: Callable[[], bool] | None = None,
    on_progress: Callable[[int], None] | None = None,
) -> RoutePipelineResult:
    objectives = tuple(str(value) for value in snapshot.get("objective_profiles", ["shortest"]))
    max_alternatives = int(snapshot.get("search_settings", {}).get("max_alternatives", 3))
    construction_method = next(iter(snapshot.get("construction_methods", [])), "open_trench")
    catalog = (
        None
        if cost_catalog_definition is None
        else CostCatalogDefinition.from_dict(cost_catalog_definition)
    )
    method_costs = _method_marginal_rates(
        catalog,
        circuit_layout=str(snapshot.get("circuit_layout", "paired")),
        corridor_width_m=float(snapshot["corridor_width_m"]),
        methods={construction_method, *(portal.method for portal in constraint_portals)},
    )
    default_cost = method_costs.get(construction_method, 1.0)
    minimum_cost = min(method_costs.values()) if method_costs else None
    accepted: list[dict[str, Any]] = []
    search_runs: list[dict[str, Any]] = []
    terminal: DemoComputation | None = None

    for endpoint in endpoints:
        for objective in objectives:
            if should_cancel is not None and should_cancel():
                return _result(
                    "cancelled",
                    "cancelled",
                    accepted,
                    search_runs,
                    None,
                    endpoints,
                    catalog,
                )
            computation = compute_demo_route(
                snapshot,
                algorithm,
                rule_profile_definition=rule_profile_definition,
                constraint_features=constraint_features,
                constraint_portals=constraint_portals,
                constraint_coverage=constraint_coverage,
                candidate_decisions=endpoint.candidate_decisions,
                planning_date=planning_date,
                working_crs=working_crs,
                metric_start=endpoint.metric_start,
                metric_goal=endpoint.metric_goal,
                entry_gate=endpoint.entry_gate,
                entry_connector=endpoint.entry_connector,
                objective=cast(Any, objective),
                construction_mode=construction_method,
                default_cost_per_m=default_cost,
                minimum_cost_per_m=minimum_cost,
                method_cost_per_m=method_costs,
                should_cancel=should_cancel,
                on_progress=on_progress,
            )
            terminal = computation
            search_runs.append(computation.statistics)
            if computation.alternative is not None:
                _merge_or_append(
                    accepted, computation.alternative, objective, endpoint.candidate_id
                )
        if len(accepted) >= max_alternatives:
            break

    refinement = snapshot.get("search_settings", {}).get("refinement_resolution_m")
    resolution = float(snapshot.get("search_settings", {}).get("resolution_m", 20))
    if not accepted and refinement is not None and float(refinement) < resolution:
        refined_snapshot = {
            **snapshot,
            "search_settings": {
                **snapshot["search_settings"],
                "resolution_m": float(refinement),
                "refinement_resolution_m": None,
            },
        }
        endpoint = endpoints[0]
        computation = compute_demo_route(
            refined_snapshot,
            algorithm,
            rule_profile_definition=rule_profile_definition,
            constraint_features=constraint_features,
            constraint_portals=constraint_portals,
            constraint_coverage=constraint_coverage,
            candidate_decisions=endpoint.candidate_decisions,
            planning_date=planning_date,
            working_crs=working_crs,
            metric_start=endpoint.metric_start,
            metric_goal=endpoint.metric_goal,
            entry_gate=endpoint.entry_gate,
            entry_connector=endpoint.entry_connector,
            objective="shortest",
            construction_mode=construction_method,
            default_cost_per_m=default_cost,
            minimum_cost_per_m=minimum_cost,
            method_cost_per_m=method_costs,
            should_cancel=should_cancel,
            on_progress=on_progress,
        )
        computation.statistics["refinement"] = {
            "attempted": True,
            "from_resolution_m": resolution,
            "to_resolution_m": float(refinement),
        }
        terminal = computation
        search_runs.append(computation.statistics)
        if computation.alternative is not None:
            _merge_or_append(
                accepted,
                computation.alternative,
                "refined_shortest",
                endpoint.candidate_id,
            )

    # Generate only alternatives that the graph actually supports. A repeated geometry ends the
    # search instead of inventing another option.
    penalty_geometries: list[BaseGeometry] = []
    penalty_buffer = max(
        float(snapshot["corridor_width_m"]) / 2,
        float(snapshot["search_settings"]["resolution_m"]) * 0.25,
    )
    for item in accepted:
        penalty_geometries.append(_metric_centerline(item, working_crs).buffer(penalty_buffer))
    attempts = 0
    penalized_slots = max(0, max_alternatives - len(objectives))
    while accepted and len(accepted) < max_alternatives and attempts < min(1, penalized_slots):
        attempts += 1
        endpoint = endpoints[0]
        computation = compute_demo_route(
            snapshot,
            algorithm,
            rule_profile_definition=rule_profile_definition,
            constraint_features=constraint_features,
            constraint_portals=constraint_portals,
            constraint_coverage=constraint_coverage,
            candidate_decisions=endpoint.candidate_decisions,
            planning_date=planning_date,
            working_crs=working_crs,
            metric_start=endpoint.metric_start,
            metric_goal=endpoint.metric_goal,
            entry_gate=endpoint.entry_gate,
            entry_connector=endpoint.entry_connector,
            objective="shortest",
            construction_mode=construction_method,
            default_cost_per_m=default_cost,
            minimum_cost_per_m=minimum_cost,
            method_cost_per_m=method_costs,
            reuse_penalty_geometries=tuple(penalty_geometries),
            reuse_penalty_per_m=max(10.0, default_cost * 10.0),
            should_cancel=should_cancel,
            on_progress=on_progress,
        )
        terminal = computation
        search_runs.append(computation.statistics)
        if computation.alternative is None:
            break
        before = len(accepted)
        _merge_or_append(
            accepted,
            computation.alternative,
            "penalized_alternative",
            endpoint.candidate_id,
        )
        if len(accepted) == before:
            break
        penalty_geometries.append(
            _metric_centerline(computation.alternative, working_crs).buffer(penalty_buffer)
        )

    accepted = accepted[:max_alternatives]
    _attach_quantities_and_costs(
        accepted,
        snapshot=snapshot,
        catalog=catalog,
        portals=constraint_portals,
        working_crs=working_crs,
        construction_method=construction_method,
    )
    if accepted and terminal is not None and terminal.outcome == "budget_exceeded":
        outcome = "budget_exceeded"
        completion = "budget_exhausted"
        error_code = terminal.error_code
    elif accepted:
        outcome = "routes_found"
        completion = "complete"
        error_code = None
    elif terminal is None:
        outcome, completion, error_code = "no_route_in_model", "complete", "NO_ENDPOINTS"
    else:
        outcome, completion, error_code = (
            terminal.outcome,
            terminal.search_completion,
            terminal.error_code,
        )
    return _result(outcome, completion, accepted, search_runs, error_code, endpoints, catalog)


def _result(
    outcome: str,
    completion: str,
    alternatives: list[dict[str, Any]],
    searches: list[dict[str, Any]],
    error_code: str | None,
    endpoints: tuple[RouteEndpoint, ...],
    catalog: CostCatalogDefinition | None,
) -> RoutePipelineResult:
    findings: dict[str, int] = {}
    for item in alternatives:
        for code in item["validation_report"].get("finding_codes", []):
            findings[str(code)] = findings.get(str(code), 0) + 1
        profile = item["validation_report"].get("rule_profile") or {}
        for finding in profile.get("findings", []):
            code = str(finding["code"])
            findings[code] = findings.get(code, 0) + 1
        vertical = item["validation_report"].get("vertical_geometry") or {}
        for finding in vertical.get("findings", []):
            code = str(finding["code"])
            findings[code] = findings.get(code, 0) + 1
        construction = item["validation_report"].get("construction_method") or {}
        if construction.get("status") == "insufficient_data":
            code = "CONSTRUCTION_METHOD_INPUTS_MISSING"
            findings[code] = findings.get(code, 0) + 1
    return RoutePipelineResult(
        outcome,
        completion,
        tuple(alternatives),
        {
            "searches": searches,
            "expanded_states": sum(int(item.get("expanded_states", 0)) for item in searches),
            "alternative_count": len(alternatives),
            "candidate_scope": {
                "kind": (
                    "selected_candidates"
                    if any(item.candidate_id for item in endpoints)
                    else "scenario_endpoints"
                ),
                "count": len(endpoints),
            },
        },
        error_code,
        (
            "candidate_reranking"
            if catalog is not None and any(rate.per in {"event", "item"} for rate in catalog.items)
            else (
                "selected_candidates"
                if any(item.candidate_id for item in endpoints)
                else "scenario_endpoints"
            )
        ),
        {name: version(name) for name in ("shapely", "pyproj", "sqlalchemy")},
        findings,
    )


def _merge_or_append(
    alternatives: list[dict[str, Any]],
    raw: dict[str, Any],
    objective: str,
    candidate_id: str | None,
) -> None:
    for existing in alternatives:
        same_candidate = existing.get("candidate_id") == candidate_id
        same_geometry = existing["geometry_hash"] == raw["geometry_hash"]
        if not same_geometry and same_candidate:
            left = shape(existing["corridor_wgs84"])
            right = shape(raw["corridor_wgs84"])
            union = left.union(right).area
            same_geometry = union > 0 and left.intersection(right).area / union >= 0.90
        if same_geometry and same_candidate:
            if objective not in existing["objective_tags"]:
                existing["objective_tags"].append(objective)
            return
    item = dict(raw)
    item["objective_tags"] = [objective]
    item["candidate_id"] = candidate_id
    if candidate_id is not None:
        item["metrics"] = {
            **item["metrics"],
            "connection_candidate_id": candidate_id,
        }
    alternatives.append(item)


def _method_marginal_rates(
    catalog: CostCatalogDefinition | None,
    *,
    circuit_layout: str,
    corridor_width_m: float,
    methods: set[str],
) -> dict[str, float]:
    if catalog is None:
        return {}
    pipe_count = 2 if circuit_layout == "paired" else 1
    result: dict[str, float] = {}
    for method in methods:
        total = Decimal(0)
        for rate in catalog.items:
            if rate.applies_to_method not in {"all", method}:
                continue
            multiplier = {
                "corridor_m": Decimal(1),
                "pipe_m": Decimal(pipe_count),
                "m2": Decimal(str(corridor_width_m)),
            }.get(rate.per, Decimal(0))
            total += rate.rate * multiplier
        result[method] = float(total)
    return result


def _metric_centerline(item: dict[str, Any], working_crs: str) -> LineString:
    transformer = Transformer.from_crs(
        "EPSG:4326", CRS.from_user_input(working_crs), always_xy=True
    )
    geometry = transform(transformer.transform, shape(item["centerline_wgs84"]))
    if not isinstance(geometry, LineString):
        raise ValueError("route centerline must be a LineString")
    return geometry


def _attach_quantities_and_costs(
    alternatives: list[dict[str, Any]],
    *,
    snapshot: dict[str, Any],
    catalog: CostCatalogDefinition | None,
    portals: tuple[CrossingPortal, ...],
    working_crs: str,
    construction_method: str,
) -> None:
    cost_reports: list[CostReport] = []
    for item in alternatives:
        centerline = _metric_centerline(item, working_crs)
        crossing_events = _crossing_events(item, portals, working_crs)
        quantities = build_quantity_report(
            centerline,
            circuit_layout=str(snapshot.get("circuit_layout", "paired")),
            default_method=construction_method,
            portals=portals,
            crossing_events=crossing_events,
            corridor_width_m=float(snapshot["corridor_width_m"]),
        )
        cost = price_quantities(quantities, catalog)
        cost_reports.append(cost)
        item["segments"] = [
            _jsonable(asdict(segment), omit={"geometry"}) for segment in quantities.segments
        ]
        item["quantity_items"] = [_jsonable(asdict(quantity)) for quantity in quantities.items]
        item["cost_breakdown"] = _jsonable(asdict(cost))
        item["assumptions"] = list(snapshot.get("explicit_assumptions", []))
        item["postprocessing_log"] = [item["metrics"].get("postprocessing", {})]
        _attach_vertical_validation(item, snapshot)
        _attach_construction_validation(item, snapshot, construction_method)
        item["validation_report"]["explanations"] = _explanations(item)
    if not cost_reports:
        return
    baseline = cost_reports[0]
    for item, report in zip(alternatives, cost_reports, strict=True):
        item["comparison"] = _jsonable(asdict(compare_costs(baseline, report, same_model=True)))


def _attach_vertical_validation(item: dict[str, Any], snapshot: dict[str, Any]) -> None:
    raw_profile = snapshot.get("vertical_profile") or []
    if not raw_profile:
        item["validation_report"]["vertical_geometry"] = {
            "status": "not_performed",
            "findings": [],
        }
        return
    result = validate_vertical_profile(
        tuple(
            VerticalProfilePoint(
                chainage_m=float(point["chainage_m"]),
                elevation_m=float(point["elevation_m"]),
            )
            for point in raw_profile
        ),
        tuple(
            VerticalCrossing(
                crossing_id=str(crossing["crossing_id"]),
                chainage_m=float(crossing["chainage_m"]),
                vertical_datum=crossing.get("vertical_datum"),
                elevation_m=crossing.get("elevation_m"),
                surface_elevation_m=crossing.get("surface_elevation_m"),
                depth_m=crossing.get("depth_m"),
                outside_diameter_m=crossing.get("outside_diameter_m"),
            )
            for crossing in snapshot.get("vertical_crossings", [])
        ),
        vertical_datum=str(snapshot["vertical_datum"]),
        route_outside_diameter_m=float(snapshot["route_outside_diameter_m"]),
        minimum_clearance_m=float(snapshot["minimum_vertical_clearance_m"]),
        maximum_grade_percent=float(snapshot["maximum_grade_percent"]),
    )
    item["validation_report"]["vertical_geometry"] = _jsonable(asdict(result))


def _attach_construction_validation(
    item: dict[str, Any], snapshot: dict[str, Any], method_code: str
) -> None:
    method = get_construction_method(method_code)
    supplied = (snapshot.get("construction_method_inputs") or {}).get(method_code, {})
    missing = [
        name
        for name in method.required_inputs
        if supplied.get(name) is None or supplied.get(name) == ""
    ]
    item["validation_report"]["construction_method"] = {
        "code": method.code,
        "label": method.label,
        "status": "insufficient_data" if missing else "passed",
        "missing_inputs": missing,
        "applicable_to": list(method.applicable_to),
    }


def _crossing_events(
    item: dict[str, Any], portals: tuple[CrossingPortal, ...], working_crs: str
) -> tuple[CrossingEvent, ...]:
    profile = item["validation_report"].get("rule_profile") or {}
    raw_events = profile.get("crossing_events", [])
    by_id = {portal.id: portal for portal in portals}
    transformer = Transformer.from_crs("EPSG:4326", working_crs, always_xy=True)
    result: list[CrossingEvent] = []
    for raw in raw_events:
        portal = by_id.get(str(raw["portal_id"]))
        if portal is None:
            continue
        geometry = transform(transformer.transform, shape(raw["geometry_wgs84"]))
        result.append(
            CrossingEvent(
                str(raw["event_type"]),
                str(raw["rule_id"]),
                str(raw["feature_id"]),
                portal.id,
                geometry,
                int(raw.get("quantity", 1)),
            )
        )
    return tuple(result)


def _jsonable(value: Any, *, omit: set[str] | None = None) -> Any:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, dict):
        return {
            key: _jsonable(item) for key, item in value.items() if omit is None or key not in omit
        }
    if isinstance(value, tuple | list):
        return [_jsonable(item) for item in value]
    return value


def _explanations(item: dict[str, Any]) -> list[dict[str, str]]:
    templates = {
        "valid_in_model": (
            "Маршрут прошёл независимую проверку в выбранной модели данных.",
            "Это не заменяет гидравлический расчёт и проверку вертикального профиля.",
        ),
        "partial_cost": (
            "Смета неполная: для части измеренных позиций нет ставки каталога.",
            "Добавьте ставки для перечисленных unpriced_items и выполните новый revision.",
        ),
    }
    result = [
        {
            "code": "valid_in_model",
            "summary": templates["valid_in_model"][0],
            "action": templates["valid_in_model"][1],
        }
    ]
    if item.get("cost_breakdown", {}).get("status") == "partial":
        result.append(
            {
                "code": "partial_cost",
                "summary": templates["partial_cost"][0],
                "action": templates["partial_cost"][1],
            }
        )
    return result
