import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import UTC, date, datetime
from typing import Any
from uuid import UUID

from pyproj import CRS
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from heatroute.config import get_settings
from heatroute.domain.network import CandidateScreeningReport
from heatroute.models import (
    CalculationRun,
    CostCatalog,
    CostCatalogVersion,
    Job,
    OutboxEvent,
    Project,
    RuleProfile,
    RuleProfileVersion,
    RunEvent,
    Scenario,
    ScenarioRevision,
)
from heatroute.services.cost_catalogs import validate_catalog_definition
from heatroute.services.model_context import (
    MaterializedModel,
    load_materialized_model,
    resolve_building_route_endpoints,
    resolve_version_selection,
    screen_materialized_candidates,
)
from heatroute.services.rule_profiles import definition_hash, validate_definition
from heatroute.workers.celery_app import celery_app


class IdempotencyConflictError(ValueError):
    pass


class PreflightNotReadyError(ValueError):
    def __init__(self, findings: list[dict[str, Any]]) -> None:
        super().__init__("scenario revision did not pass preflight")
        self.findings = findings


class QueueOverloadedError(RuntimeError):
    pass


@dataclass(frozen=True)
class PreflightResult:
    ready: bool
    findings: list[dict[str, Any]]


def _screen_revision_candidates(
    model: MaterializedModel, snapshot: dict[str, Any]
) -> tuple[CandidateScreeningReport | None, tuple[str, ...]]:
    selected_ids = tuple(str(item) for item in snapshot.get("connection_candidate_ids", []))
    available_ids = {candidate.source_id for candidate in model.candidates}
    missing_ids = tuple(item for item in selected_ids if item not in available_ids)
    if not selected_ids:
        return None, missing_ids
    planning_date = date.fromisoformat(str(snapshot["planning_date"]))
    report = screen_materialized_candidates(
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
        selected_source_ids=selected_ids,
    )
    return report, missing_ids


def append_run_event(
    session: Session,
    *,
    run_id: UUID,
    event_type: str,
    phase: str,
    payload: dict[str, Any] | None = None,
) -> RunEvent:
    event = RunEvent(
        run_id=run_id,
        event_type=event_type,
        phase=phase,
        payload=payload or {},
    )
    session.add(event)
    return event


def canonical_hash(value: dict[str, Any]) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode()).hexdigest()


def evaluate_revision_preflight(
    session: Session,
    revision: ScenarioRevision,
) -> PreflightResult:
    scenario = session.get(Scenario, revision.scenario_id)
    if scenario is None:
        raise LookupError("scenario not found")
    project = session.get(Project, scenario.project_id)
    if project is None:
        raise LookupError("project not found")

    findings: list[dict[str, Any]] = []
    if not project.working_crs:
        findings.append(
            {
                "code": "WORKING_CRS_MISSING",
                "severity": "error",
                "blocking": True,
                "field": "project.working_crs",
                "message": "Project working CRS is required before calculation.",
            }
        )
    else:
        try:
            crs = CRS.from_user_input(project.working_crs)
            uses_metres = bool(crs.axis_info) and all(
                axis.unit_name.lower() in {"metre", "meter", "metres", "meters"}
                for axis in crs.axis_info[:2]
            )
            if not crs.is_projected or not uses_metres:
                findings.append(
                    {
                        "code": "WORKING_CRS_NOT_METRIC_PROJECTED",
                        "severity": "error",
                        "blocking": True,
                        "field": "project.working_crs",
                        "message": "Project working CRS must be projected and use metres.",
                    }
                )
        except Exception:
            findings.append(
                {
                    "code": "WORKING_CRS_INVALID",
                    "severity": "error",
                    "blocking": True,
                    "field": "project.working_crs",
                    "message": "Project working CRS could not be parsed.",
                }
            )
    if not project.crs_confirmed:
        findings.append(
            {
                "code": "WORKING_CRS_UNCONFIRMED",
                "severity": "error",
                "blocking": True,
                "field": "project.crs_confirmed",
                "message": "Project working CRS must be explicitly confirmed.",
            }
        )

    snapshot = revision.input_snapshot
    version_selection = resolve_version_selection(
        session,
        project=project,
        selected_version_ids=[
            str(value) for value in snapshot.get("selected_dataset_version_ids", [])
        ],
        scenario_revision_id=revision.id,
        scenario_input_hash=revision.input_hash,
    )
    findings.extend(finding.as_preflight() for finding in version_selection.findings)
    materialized_model: MaterializedModel | None = None
    if (
        version_selection.versions
        and project.working_crs
        and project.crs_confirmed
        and not any(finding.blocking for finding in version_selection.findings)
    ):
        try:
            model = load_materialized_model(
                session,
                version_ids=tuple(version.id for version in version_selection.versions),
                working_crs=project.working_crs,
            )
        except (TypeError, ValueError) as error:
            findings.append(
                {
                    "code": "MODEL_CONTEXT_MATERIALIZATION_FAILED",
                    "severity": "error",
                    "blocking": True,
                    "field": "selected_dataset_version_ids",
                    "message": str(error),
                }
            )
        else:
            materialized_model = model
            findings.extend(finding.as_preflight() for finding in model.findings)
            findings.extend(
                {
                    "code": topology_finding.code,
                    "severity": topology_finding.severity,
                    "blocking": topology_finding.severity == "error",
                    "field": "selected_dataset_version_ids",
                    "message": topology_finding.message,
                }
                for topology_finding in model.topology.findings
            )
    rule_profile_version_id = snapshot.get("rule_profile_version_id")
    if version_selection.versions and rule_profile_version_id is None:
        findings.append(
            {
                "code": "RULE_PROFILE_VERSION_REQUIRED",
                "severity": "error",
                "blocking": True,
                "field": "rule_profile_version_id",
                "message": "Canonical dataset versions require an explicit rule profile version.",
            }
        )
    if version_selection.versions and snapshot.get("planning_date") is None:
        findings.append(
            {
                "code": "PLANNING_DATE_REQUIRED",
                "severity": "error",
                "blocking": True,
                "field": "planning_date",
                "message": "Canonical dataset versions require an explicit planning date.",
            }
        )
    if (
        materialized_model is not None
        and snapshot.get("connection_candidate_ids")
        and snapshot.get("planning_date") is not None
    ):
        candidate_report, missing_candidate_ids = _screen_revision_candidates(
            materialized_model, snapshot
        )
        for candidate_id in missing_candidate_ids:
            findings.append(
                {
                    "code": "CONNECTION_CANDIDATE_NOT_FOUND",
                    "severity": "error",
                    "blocking": True,
                    "field": "connection_candidate_ids",
                    "message": f"Selected connection candidate {candidate_id!r} was not found.",
                }
            )
        if candidate_report is not None:
            findings.extend(
                {
                    "code": decision.reasons[0],
                    "severity": "warning",
                    "blocking": False,
                    "field": "connection_candidate_ids",
                    "message": (
                        f"Connection candidate {decision.source_id!r} was rejected: "
                        f"{', '.join(decision.reasons)}."
                    ),
                }
                for decision in candidate_report.rejected
            )
            if not candidate_report.selected:
                findings.append(
                    {
                        "code": "NO_ELIGIBLE_CANDIDATES",
                        "severity": "error",
                        "blocking": True,
                        "field": "connection_candidate_ids",
                        "message": "No selected connection candidate passed screening.",
                    }
                )
            elif snapshot.get("input_mode") == "building_to_network" and project.working_crs:
                endpoint_resolution = resolve_building_route_endpoints(
                    materialized_model,
                    snapshot=snapshot,
                    candidate_source_id=candidate_report.selected[0].source_id,
                    working_crs=project.working_crs,
                )
                findings.extend(finding.as_preflight() for finding in endpoint_resolution.findings)
    if rule_profile_version_id is not None:
        selected_profile = session.execute(
            select(RuleProfileVersion, RuleProfile)
            .join(RuleProfile, RuleProfile.id == RuleProfileVersion.profile_id)
            .where(
                RuleProfileVersion.id == UUID(str(rule_profile_version_id)),
                RuleProfile.project_id == project.id,
                RuleProfile.workspace_id == project.workspace_id,
            )
        ).one_or_none()
        if selected_profile is None:
            findings.append(
                {
                    "code": "RULE_PROFILE_VERSION_NOT_FOUND",
                    "severity": "error",
                    "blocking": True,
                    "field": "rule_profile_version_id",
                    "message": "Selected rule profile version is not available in this project.",
                }
            )
        else:
            profile_version, profile = selected_profile
            try:
                validate_definition(
                    profile_version.definition,
                    expected_revision=profile_version.revision,
                    expected_name=profile.name,
                )
                hash_matches = profile_version.definition_hash == definition_hash(
                    profile_version.definition
                )
            except ValueError:
                hash_matches = False
            if not hash_matches:
                findings.append(
                    {
                        "code": "RULE_PROFILE_VERSION_INVALID",
                        "severity": "error",
                        "blocking": True,
                        "field": "rule_profile_version_id",
                        "message": "Selected rule profile version failed integrity validation.",
                    }
                )
            elif profile_version.status == "draft":
                findings.append(
                    {
                        "code": "DRAFT_RULE_PROFILE",
                        "severity": "warning",
                        "blocking": False,
                        "field": "rule_profile_version_id",
                        "message": "The selected rule profile is a draft and is not reviewed.",
                    }
                )
    input_mode = snapshot.get("input_mode")
    if input_mode not in {"point_to_point_demo", "building_to_network"}:
        findings.append(
            {
                "code": "INPUT_MODE_UNSUPPORTED",
                "severity": "error",
                "blocking": True,
                "field": "input_mode",
                "message": "The requested scenario input mode is not supported.",
            }
        )
    elif input_mode == "point_to_point_demo" and project.source_mode != "synthetic":
        findings.append(
            {
                "code": "SYNTHETIC_MODE_REQUIRED",
                "severity": "error",
                "blocking": True,
                "field": "project.source_mode",
                "message": "point_to_point_demo is allowed only for synthetic projects.",
            }
        )
    if input_mode == "point_to_point_demo" and snapshot.get("entry_point_wgs84") == snapshot.get(
        "goal_point_wgs84"
    ):
        findings.append(
            {
                "code": "ENTRY_EQUALS_GOAL",
                "severity": "error",
                "blocking": True,
                "field": "goal_point_wgs84",
                "message": "Entry and goal points must be different.",
            }
        )
    cost_catalog_version_id = snapshot.get("cost_catalog_version_id")
    objectives = set(snapshot.get("objective_profiles", ["shortest"]))
    if "estimated_cost" in objectives and cost_catalog_version_id is None:
        findings.append(
            {
                "code": "COST_CATALOG_VERSION_REQUIRED",
                "severity": "error",
                "blocking": True,
                "field": "cost_catalog_version_id",
                "message": "The estimated_cost objective requires an immutable cost catalog.",
            }
        )
    if cost_catalog_version_id is not None:
        selected_catalog = session.execute(
            select(CostCatalogVersion, CostCatalog)
            .join(CostCatalog, CostCatalog.id == CostCatalogVersion.catalog_id)
            .where(
                CostCatalogVersion.id == UUID(str(cost_catalog_version_id)),
                CostCatalog.project_id == project.id,
                CostCatalog.workspace_id == project.workspace_id,
            )
        ).one_or_none()
        if selected_catalog is None:
            findings.append(
                {
                    "code": "COST_CATALOG_VERSION_NOT_FOUND",
                    "severity": "error",
                    "blocking": True,
                    "field": "cost_catalog_version_id",
                    "message": "Selected cost catalog version is not available in this project.",
                }
            )
        else:
            catalog_version, catalog = selected_catalog
            parsed_catalog = None
            try:
                parsed_catalog = validate_catalog_definition(
                    catalog_version.definition,
                    expected_revision=catalog_version.revision,
                )
                valid_hash = catalog_version.definition_hash == definition_hash(
                    catalog_version.definition
                )
            except ValueError:
                valid_hash = False
            if not valid_hash:
                findings.append(
                    {
                        "code": "COST_CATALOG_VERSION_INVALID",
                        "severity": "error",
                        "blocking": True,
                        "field": "cost_catalog_version_id",
                        "message": "Selected cost catalog version failed integrity validation.",
                    }
                )
            elif "estimated_cost" in objectives and parsed_catalog is not None:
                construction_methods = snapshot.get("construction_methods") or ["open_trench"]
                required = {
                    ("pipe_m", "m", "all"),
                    ("event", "event", "demo_tie_in"),
                    *(
                        (basis, unit, str(method))
                        for method in construction_methods
                        for basis, unit in (("corridor_m", "m"), ("m2", "m2"))
                    ),
                }
                available = {
                    (rate.per, rate.quantity_unit, rate.applies_to_method)
                    for rate in parsed_catalog.items
                }
                missing = sorted(required - available)
                if missing:
                    findings.append(
                        {
                            "code": "COST_CATALOG_INCOMPLETE_FOR_OBJECTIVE",
                            "severity": "error",
                            "blocking": True,
                            "field": "cost_catalog_version_id",
                            "message": (
                                "The estimated_cost objective has no rates for: "
                                + ", ".join("/".join(item) for item in missing)
                            ),
                        }
                    )
            elif catalog_version.status in {"synthetic", "draft"}:
                findings.append(
                    {
                        "code": "NON_REVIEWED_COST_CATALOG",
                        "severity": "warning",
                        "blocking": False,
                        "field": "cost_catalog_version_id",
                        "message": (
                            f"Cost catalog {catalog.name!r} is labelled {catalog_version.status}."
                        ),
                    }
                )
    if not snapshot.get("explicit_assumptions"):
        findings.append(
            {
                "code": "EXPLICIT_ASSUMPTIONS_EMPTY",
                "severity": "warning",
                "blocking": False,
                "field": "explicit_assumptions",
                "message": "Record the assumptions of the synthetic calculation.",
            }
        )
    findings.append(
        {
            "code": "ENGINEERING_CHECKS_NOT_PERFORMED",
            "severity": "info",
            "blocking": False,
            "field": None,
            "message": "Hydraulics and vertical geometry are not performed in this slice.",
        }
    )
    return PreflightResult(
        ready=not any(bool(finding["blocking"]) for finding in findings),
        findings=findings,
    )


def create_revision_run(
    session: Session,
    *,
    revision: ScenarioRevision,
    algorithm: str,
    idempotency_key: str,
    request_hash: str | None = None,
) -> CalculationRun:
    scenario = session.get(Scenario, revision.scenario_id)
    if scenario is None:
        raise LookupError("scenario not found")
    project = session.get(Project, scenario.project_id)
    if project is None:
        raise LookupError("project not found")

    fingerprint = request_hash or canonical_hash(
        {
            "scenario_revision_id": str(revision.id),
            "input_hash": revision.input_hash,
            "algorithm": algorithm,
        }
    )
    existing = session.scalar(
        select(CalculationRun).where(
            CalculationRun.workspace_id == project.workspace_id,
            CalculationRun.idempotency_key == idempotency_key,
        )
    )
    if existing is not None:
        if existing.input_hash != fingerprint:
            raise IdempotencyConflictError("idempotency key was used for a different request")
        return existing

    preflight = evaluate_revision_preflight(session, revision)
    if not preflight.ready:
        raise PreflightNotReadyError(preflight.findings)

    active_jobs = session.scalar(
        select(func.count(Job.id)).where(
            Job.kind == "route_calculation",
            Job.state.in_(("queued", "running", "cancel_requested")),
        )
    )
    if int(active_jobs or 0) >= get_settings().max_queued_route_jobs:
        raise QueueOverloadedError("route calculation queue is at configured capacity")

    version_selection = resolve_version_selection(
        session,
        project=project,
        selected_version_ids=[
            str(value) for value in revision.input_snapshot.get("selected_dataset_version_ids", [])
        ],
        scenario_revision_id=revision.id,
        scenario_input_hash=revision.input_hash,
    )
    versions_snapshot = dict(version_selection.versions_snapshot)
    if revision.input_snapshot.get("connection_candidate_ids"):
        if project.working_crs is None:
            raise RuntimeError("project working CRS is missing after successful preflight")
        materialized_model = load_materialized_model(
            session,
            version_ids=tuple(version.id for version in version_selection.versions),
            working_crs=project.working_crs,
        )
        candidate_report, _missing_candidate_ids = _screen_revision_candidates(
            materialized_model, revision.input_snapshot
        )
        if candidate_report is not None:
            versions_snapshot["candidate_screening"] = asdict(candidate_report)
    selected_rule_profile_id = revision.input_snapshot.get("rule_profile_version_id")
    if selected_rule_profile_id is not None:
        rule_row = session.execute(
            select(RuleProfileVersion, RuleProfile)
            .join(RuleProfile, RuleProfile.id == RuleProfileVersion.profile_id)
            .where(
                RuleProfileVersion.id == UUID(str(selected_rule_profile_id)),
                RuleProfile.project_id == project.id,
                RuleProfile.workspace_id == project.workspace_id,
            )
        ).one()
        rule_version, rule_profile = rule_row
        versions_snapshot["rule_profile"] = {
            "id": str(rule_version.id),
            "profile_id": str(rule_profile.id),
            "revision": rule_version.revision,
            "definition_hash": rule_version.definition_hash,
            "status": rule_version.status,
        }
    selected_cost_catalog_id = revision.input_snapshot.get("cost_catalog_version_id")
    if selected_cost_catalog_id is not None:
        cost_row = session.execute(
            select(CostCatalogVersion, CostCatalog)
            .join(CostCatalog, CostCatalog.id == CostCatalogVersion.catalog_id)
            .where(CostCatalogVersion.id == UUID(str(selected_cost_catalog_id)))
        ).one()
        cost_version, cost_catalog = cost_row
        versions_snapshot["cost_catalog"] = {
            "id": str(cost_version.id),
            "catalog_id": str(cost_catalog.id),
            "revision": cost_version.revision,
            "definition_hash": cost_version.definition_hash,
            "status": cost_version.status,
        }
    versions_snapshot.pop("manifest_hash", None)
    versions_snapshot["manifest_hash"] = definition_hash(versions_snapshot)
    cache_key = canonical_hash(
        {
            "scenario_input_hash": revision.input_hash,
            "algorithm": algorithm,
            "algorithm_version": "grid-v2",
            "versions_manifest_hash": versions_snapshot["manifest_hash"],
        }
    )

    job = Job(
        workspace_id=project.workspace_id,
        kind="route_calculation",
        state="queued",
        phase="queued",
        payload={},
    )
    session.add(job)
    session.flush()
    snapshot = revision.input_snapshot
    run = CalculationRun(
        workspace_id=project.workspace_id,
        project_id=project.id,
        scenario_revision_id=revision.id,
        job_id=job.id,
        input_hash=fingerprint,
        idempotency_key=idempotency_key,
        job_state="queued",
        outcome="pending",
        phase="queued",
        algorithm_name=algorithm,
        algorithm_version="grid-v2",
        versions_snapshot=versions_snapshot,
        parameters=snapshot,
        statistics={},
        assumptions=list(snapshot.get("explicit_assumptions", [])),
        cache_info={"key": cache_key, "hit": False},
    )
    session.add(run)
    session.flush()
    job.payload = {"run_id": str(run.id)}
    event = OutboxEvent(
        aggregate_type="calculation_run",
        aggregate_id=run.id,
        event_type="run.requested",
        payload={"run_id": str(run.id)},
        state="pending",
        attempts=0,
    )
    session.add(event)
    append_run_event(
        session,
        run_id=run.id,
        event_type="run.queued",
        phase="queued",
        payload={"job_id": str(job.id)},
    )
    session.commit()
    publish_outbox_event(session, event.id)
    session.refresh(run)
    return run


def publish_outbox_event(session: Session, event_id: UUID) -> bool:
    event = session.get(OutboxEvent, event_id)
    if event is None or event.state == "published":
        return False
    task_route = {
        "run.requested": ("heatroute.compute_route", "compute"),
        "dataset_import.inspect_requested": ("heatroute.inspect_dataset", "ingest"),
        "dataset_import.validate_requested": ("heatroute.validate_dataset", "ingest"),
        "dataset_import.publish_requested": ("heatroute.publish_dataset", "ingest"),
        "engineering.hydraulics_requested": ("heatroute.calculate_hydraulics", "compute"),
    }.get(event.event_type)
    if task_route is None:
        event.attempts += 1
        event.last_error = "UnsupportedOutboxEvent"
        session.commit()
        return False
    task_name, queue = task_route
    try:
        celery_app.send_task(
            task_name,
            args=[str(event.aggregate_id)],
            queue=queue,
            task_id=str(event.id),
        )
    except Exception as error:
        event.attempts += 1
        event.last_error = type(error).__name__
        session.commit()
        return False
    event.state = "published"
    event.attempts += 1
    event.published_at = datetime.now(UTC)
    event.last_error = None
    session.commit()
    return True
